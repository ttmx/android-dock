package dev.tteles.dock

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tteles.dock.system.DockNotification
import dev.tteles.dock.system.DockNotificationListener
import dev.tteles.dock.system.Media
import dev.tteles.dock.system.Power
import dev.tteles.dock.system.PowerState
import dev.tteles.dock.ui.DockScreen
import dev.tteles.dock.ui.DockTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The dock while it's on screen, shared by the screensaver and the preview activity.
 * [leave] runs an action that opens something else (unlocking first); [close] dismisses the dock.
 */
class DockSession(
    private val context: Context,
    private val leave: (action: () -> Unit) -> Unit,
    private val close: () -> Unit,
) : SensorEventListener {
    private val app = DockApp.instance
    private var power by mutableStateOf<PowerState?>(null)
    private var night by mutableStateOf(false)
    private var window: Window? = null
    private var powerJob: Job? = null

    fun start(window: Window, scope: CoroutineScope) {
        this.window = window
        showing++
        app.ha.acquire()
        power = Power.current(context)
        powerJob = scope.launch { Power.flow(context).collect { power = it } }
        val sm = context.getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        context.getSystemService(SensorManager::class.java).unregisterListener(this)
        powerJob?.cancel()
        app.ha.release()
        showing--
        window = null
    }

    @Composable
    fun Content() {
        val config by app.prefs.config.collectAsStateWithLifecycle()
        val states by app.ha.states.collectAsStateWithLifecycle()
        val status by app.ha.status.collectAsStateWithLifecycle()
        val notifications by DockNotificationListener.notifications.collectAsStateWithLifecycle()
        val nowPlaying by Media.nowPlaying.collectAsStateWithLifecycle()
        DockTheme {
            DockScreen(
                config = config,
                power = power,
                states = states,
                haStatus = status,
                notifications = notifications,
                notificationAccess = DockNotificationListener.isEnabled(context),
                nowPlaying = nowPlaying,
                night = night && config.nightMode,
                callService = app.ha::callService,
                openNotification = ::openNotification,
                dismissNotification = { DockNotificationListener.dismiss(it.key) },
                openSettings = {
                    leave {
                        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                close = close,
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values[0]
        // Hysteresis so a passing shadow doesn't flip the screen red and back.
        val newNight = if (night) lux < 12f else lux < 3f
        if (newNight == night) return
        night = newNight
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (night && app.prefs.config.value.nightMode) 0.01f
                else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun openNotification(n: DockNotification) {
        val pi = n.contentIntent ?: return
        leave {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            try {
                pi.send(context, 0, null, null, null, null, options)
            } catch (e: Exception) {
                // The app cancelled its intent; nothing sensible to open.
            }
        }
    }

    companion object {
        /** How many docks are on screen; updates wait until this is zero. Main thread only. */
        @Volatile
        var showing = 0
            private set
    }
}
