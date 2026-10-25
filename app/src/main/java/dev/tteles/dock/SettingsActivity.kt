package dev.tteles.dock

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tteles.dock.system.ChargingMonitorService
import dev.tteles.dock.system.DockNotificationListener
import dev.tteles.dock.system.Updater
import dev.tteles.dock.ui.DockTheme
import dev.tteles.dock.ui.SettingsScreen
import dev.tteles.dock.ui.SetupItem

class SettingsActivity : ComponentActivity() {
    private val app get() = application as DockApp
    // Bumped on resume so permission checkmarks refresh after a trip to system settings.
    private var resumes by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val config by app.prefs.config.collectAsStateWithLifecycle()
            @Suppress("UNUSED_EXPRESSION") resumes
            DockTheme {
                SettingsScreen(
                    config = config,
                    setup = setupItems(),
                    update = { block ->
                        val before = app.prefs.config.value
                        app.prefs.update(block)
                        val after = app.prefs.config.value
                        if (before.haUrl != after.haUrl || before.haToken != after.haToken) app.ha.reconnect()
                    },
                    loadEntities = app.ha::fetchStates,
                    preview = { DockActivity.start(this) },
                    version = BuildConfig.VERSION_NAME,
                    checkForUpdate = { Updater.checkAndInstall(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        ChargingMonitorService.start(this)
    }

    private fun setupItems(): List<SetupItem> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return buildList {
            if (Build.VERSION.SDK_INT >= 33) add(
                SetupItem(
                    "Notifications", "Keeps the charger watcher running",
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                ) { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
            add(
                SetupItem(
                    "Display over other apps", "Lets the dock pop up by itself when you start charging",
                    Settings.canDrawOverlays(this@SettingsActivity),
                ) { open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true) }
            )
            if (Build.VERSION.SDK_INT >= 34) add(
                SetupItem(
                    "Full-screen alerts", "Lets the dock appear over the lock screen",
                    nm.canUseFullScreenIntent(),
                ) { open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true) }
            )
            add(
                SetupItem(
                    "Notification access", "Shows your notifications and what's playing",
                    DockNotificationListener.isEnabled(this@SettingsActivity),
                ) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            )
            add(
                SetupItem(
                    "Unrestricted battery", "Stops Android from killing the charger watcher",
                    pm.isIgnoringBatteryOptimizations(packageName),
                ) {
                    @Suppress("BatteryLife")
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
                }
            )
            add(
                SetupItem(
                    "Install updates", "Lets the app update itself from GitHub",
                    packageManager.canRequestPackageInstalls(),
                ) { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, withPackage = true) }
            )
        }
    }

    private fun open(action: String, withPackage: Boolean = false) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
}
