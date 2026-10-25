package dev.tteles.dock

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        resumed = true
        resumes++
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun setupItems(): List<SetupItem> {
        val listener = DockNotificationListener.isEnabled(this)
        val notify = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val installs = packageManager.canRequestPackageInstalls()
        return listOf(
            SetupItem("Screen saver", "Pick Dock, show it while charging, and turn off Low light mode", isScreenSaver()) {
                open(Settings.ACTION_DREAM_SETTINGS)
            },
            SetupItem(
                "Notification access",
                if (listener) "Shows your notifications and what's playing"
                else "If Android blocks it: App info → ⋮ → Allow restricted settings",
                listener,
            ) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            SetupItem("Install updates", "Lets the app update itself from GitHub", installs && notify) {
                if (!installs) open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, withPackage = true)
                // Needed to ask for the first update's confirmation from the background.
                else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }

    /**
     * Whether the system screen saver is on, set to Dock, and starts while charging or docked. Pixel's
     * Low light mode swaps any screen saver for its own clock in the dark, so that has to be off too.
     */
    private fun isScreenSaver(): Boolean = runCatching {
        val cr = contentResolver
        val dock = ComponentName(this, DockDream::class.java)
        Settings.Secure.getInt(cr, "screensaver_enabled", 0) == 1 &&
            Settings.Secure.getString(cr, "screensaver_components").orEmpty().split(',')
                .any { ComponentName.unflattenFromString(it) == dock } &&
            (Settings.Secure.getInt(cr, "screensaver_activate_on_sleep", 0) == 1 ||
                Settings.Secure.getInt(cr, "screensaver_activate_on_dock", 0) == 1) &&
            runCatching { Settings.Secure.getInt(cr, "low_light_display_behavior_enabled", 0) }.getOrDefault(0) == 0
    }.onFailure { Log.w("Settings", "Can't read screen saver settings", it) }.getOrDefault(false)

    private fun open(action: String, withPackage: Boolean = false) {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.parse("package:$packageName")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        @Volatile
        var resumed = false
            private set
    }
}
