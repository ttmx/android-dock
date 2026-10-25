package dev.tteles.dock.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.tteles.dock.DockActivity
import dev.tteles.dock.DockApp
import dev.tteles.dock.R
import dev.tteles.dock.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android no longer delivers power-connected broadcasts to sleeping apps, so a small foreground
 * service listens for battery changes and pops the dock when the phone lands on a charger.
 */
class ChargingMonitorService : Service() {
    private var wasDocked: Boolean? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val power = Power.parse(context, intent)
            val cfg = DockApp.instance.prefs.config.value
            val docked = if (cfg.wirelessOnly) power.wireless else power.pluggedIn
            val previous = wasDocked
            wasDocked = docked
            // The first (sticky) broadcast just tells us where we are; only react to transitions.
            if (previous == false && docked && cfg.autoLaunch && !DockActivity.isVisible) {
                Log.i(TAG, "Docked on ${power.source}, launching")
                launchDock(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dock)
            .setContentTitle("Waiting for a charger")
            .setContentText("Dock opens when you put the phone on a wireless charger")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_MONITOR, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { updateLoop() }
    }

    /** This service is always running anyway, so it doubles as the update scheduler. */
    private suspend fun updateLoop() {
        delay(UPDATE_FIRST_CHECK_MS)
        while (true) {
            val cfg = DockApp.instance.prefs.config.value
            when {
                !cfg.autoUpdate || !packageManager.canRequestPackageInstalls() -> delay(UPDATE_INTERVAL_MS)
                // Installing restarts the app, so never pull the dock out from under someone.
                DockActivity.isVisible -> delay(UPDATE_RETRY_MS)
                else -> {
                    try {
                        Log.i(TAG, Updater.checkAndInstall(this))
                    } catch (e: Exception) {
                        Log.w(TAG, "Update check failed", e)
                    }
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DockMonitor"
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_LAUNCH = "launch"
        const val NOTIF_MONITOR = 1
        const val NOTIF_LAUNCH = 2
        private const val UPDATE_FIRST_CHECK_MS = 60_000L
        private const val UPDATE_INTERVAL_MS = 6 * 60 * 60_000L
        private const val UPDATE_RETRY_MS = 30 * 60_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChargingMonitorService::class.java))
            } catch (e: Exception) {
                // Starting from the background can be refused; the next app open or boot will retry.
                Log.w(TAG, "Could not start monitor", e)
            }
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITOR, "Charger monitor", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LAUNCH, "Dock launcher", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        fun launchDock(context: Context) {
            val intent = Intent(context, DockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            // Full-screen intent is the official way to show UI over the lock screen from the
            // background; DockActivity cancels it as soon as it shows up.
            val pi = PendingIntent.getActivity(
                context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(context, CHANNEL_LAUNCH)
                .setSmallIcon(R.drawable.ic_stat_dock)
                .setContentTitle("Docked")
                .setContentText("Tap to open the dock")
                .setCategory(Notification.CATEGORY_STATUS)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(30_000)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIF_LAUNCH, n)
            // Apps allowed to draw over others may start activities directly, which is snappier.
            if (Settings.canDrawOverlays(context)) {
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct launch refused", e)
                }
            }
        }
    }
}
