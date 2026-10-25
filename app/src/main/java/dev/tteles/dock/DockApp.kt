package dev.tteles.dock

import android.app.Application
import dev.tteles.dock.ha.HomeAssistant
import dev.tteles.dock.system.ChargingMonitorService

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
        ChargingMonitorService.start(this)
    }

    companion object {
        lateinit var instance: DockApp
            private set
    }
}
