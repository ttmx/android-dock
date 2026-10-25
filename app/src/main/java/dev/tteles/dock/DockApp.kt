package dev.tteles.dock

import android.app.Application
import android.app.NotificationManager
import dev.tteles.dock.ha.HomeAssistant
import dev.tteles.dock.system.UpdateWorker

class DockApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var ha: HomeAssistant
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        ha = HomeAssistant(prefs)
        UpdateWorker.schedule(this)
        // Left over from the old charger watcher.
        getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("monitor")
            deleteNotificationChannel("launch")
        }
    }

    companion object {
        lateinit var instance: DockApp
            private set
    }
}
