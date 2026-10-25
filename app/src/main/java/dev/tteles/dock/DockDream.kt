package dev.tteles.dock

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.tteles.dock.system.Power

/**
 * The dock as Android's screen saver ("When to start: While charging"). The system starts it over
 * the lock screen when the phone charges and ends it when the phone comes off the charger.
 */
class DockDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var session: DockSession

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        // Opening something wakes the dream, which hands over to the lock screen and then the app.
        session = DockSession(this, leave = { action -> action(); wakeUp() }, close = ::wakeUp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        setContentView(ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DockDream)
            setViewTreeSavedStateRegistryOwner(this@DockDream)
            setContent { session.Content() }
        })
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // The system can't tell cables from wireless pads; bow out and let the screen sleep.
        if (DockApp.instance.prefs.config.value.wirelessOnly && Power.current(this)?.wireless == false) {
            finish()
            return
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        session.start(window, lifecycleScope)
    }

    override fun onDreamingStopped() {
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            session.stop()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
