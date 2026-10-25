package dev.tteles.dock.system

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DockNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: Icon?,
    val contentIntent: PendingIntent?,
    val clearable: Boolean,
)

data class NowPlaying(
    val app: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val controller: MediaController,
)

class DockNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refresh()
        Media.start(this)
    }

    override fun onListenerDisconnected() {
        instance = null
        Media.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val list = try { activeNotifications } catch (e: SecurityException) { null } ?: return
        _notifications.value = list
            .filter { it.packageName != packageName }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !it.isOngoing }
            .mapNotNull { it.toDock() }
            .sortedByDescending { it.time }
    }

    private fun StatusBarNotification.toDock(): DockNotification? {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val appName = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) { packageName }
        return DockNotification(
            key, packageName, appName, title, text, postTime,
            notification.smallIcon, notification.contentIntent, isClearable,
        )
    }

    companion object {
        private var instance: DockNotificationListener? = null
        private val _notifications = MutableStateFlow<List<DockNotification>>(emptyList())
        val notifications: StateFlow<List<DockNotification>> = _notifications

        fun dismiss(key: String) {
            instance?.cancelNotification(key)
        }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, DockNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Follows whichever media session is active, using the notification listener's privileges. */
object Media {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private var manager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list.orEmpty())
    }

    fun start(service: NotificationListenerService) {
        appContext = service.applicationContext
        val m = service.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(service, DockNotificationListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            manager = m
            track(m.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Listener access got revoked between connect and now.
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        track(emptyList())
    }

    private fun track(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        // Prefer something actually playing, otherwise the most recent paused session.
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        val md = c?.metadata
        _nowPlaying.value = if (c == null || md == null) null else NowPlaying(
            app = try {
                appContext.packageManager.getApplicationLabel(
                    appContext.packageManager.getApplicationInfo(c.packageName, 0)
                ).toString()
            } catch (e: Exception) { c.packageName },
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            controller = c,
        )
    }
}
