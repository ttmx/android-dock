package dev.tteles.dock

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.tteles.dock.system.ChargingMonitorService
import dev.tteles.dock.system.DockNotification
import dev.tteles.dock.system.DockNotificationListener
import dev.tteles.dock.system.Media
import dev.tteles.dock.system.Power
import dev.tteles.dock.system.PowerState
import dev.tteles.dock.ui.DockScreen
import dev.tteles.dock.ui.DockTheme
import kotlinx.coroutines.launch

class DockActivity : ComponentActivity(), SensorEventListener {
    private val app get() = application as DockApp
    private var power by mutableStateOf<PowerState?>(null)
    private var night by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        power = Power.current(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Power.flow(this@DockActivity).collect { p ->
                    val wasPlugged = power?.pluggedIn == true
                    power = p
                    if (wasPlugged && !p.pluggedIn && app.prefs.config.value.closeOnUnplug) finish()
                }
            }
        }

        setContent {
            val config by app.prefs.config.collectAsStateWithLifecycle()
            val states by app.ha.states.collectAsStateWithLifecycle()
            val status by app.ha.status.collectAsStateWithLifecycle()
            val notifications by DockNotificationListener.notifications.collectAsStateWithLifecycle()
            val nowPlaying by Media.nowPlaying.collectAsStateWithLifecycle()
            DockTheme { DockScreen(
                config = config,
                power = power,
                states = states,
                haStatus = status,
                notifications = notifications,
                notificationAccess = DockNotificationListener.isEnabled(this),
                nowPlaying = nowPlaying,
                night = night && config.nightMode,
                callService = app.ha::callService,
                openNotification = ::openNotification,
                dismissNotification = { DockNotificationListener.dismiss(it.key) },
                openSettings = {
                    unlockThen { startActivity(Intent(this, SettingsActivity::class.java)) }
                },
                close = { finish() },
            ) }
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        app.ha.acquire()
        getSystemService(NotificationManager::class.java).cancel(ChargingMonitorService.NOTIF_LAUNCH)
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStop() {
        isVisible = false
        app.ha.release()
        getSystemService(SensorManager::class.java).unregisterListener(this)
        super.onStop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        // Hysteresis so a passing shadow doesn't flip the screen red and back.
        val newNight = if (night) lux < 12f else lux < 3f
        if (newNight != night) {
            night = newNight
            window.attributes = window.attributes.apply {
                screenBrightness = if (night && app.prefs.config.value.nightMode) 0.01f
                else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun unlockThen(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return action()
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    private fun openNotification(n: DockNotification) {
        val pi = n.contentIntent ?: return
        unlockThen {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            try {
                pi.send(this, 0, null, null, null, null, options)
            } catch (e: Exception) {
                // The app cancelled its intent; nothing sensible to open.
            }
        }
    }

    companion object {
        @Volatile
        var isVisible = false
            private set

        fun start(context: Context) {
            context.startActivity(Intent(context, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
