package dev.tteles.dock.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the charger monitor after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChargingMonitorService.start(context)
    }
}
