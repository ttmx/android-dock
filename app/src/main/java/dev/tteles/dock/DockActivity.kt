package dev.tteles.dock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

/** The dock opened by hand: a preview from settings, or for phones without a screen saver setting. */
class DockActivity : ComponentActivity() {
    private val session by lazy { DockSession(this, leave = ::unlockThen, close = ::finish) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { session.Content() }
    }

    override fun onStart() {
        super.onStart()
        session.start(window, lifecycleScope)
    }

    override fun onStop() {
        session.stop()
        super.onStop()
    }

    private fun unlockThen(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return action()
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()
        })
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
