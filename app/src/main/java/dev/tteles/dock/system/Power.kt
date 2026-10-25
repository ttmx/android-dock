package dev.tteles.dock.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class PowerState(
    val level: Int = 0,
    val plugged: Int = 0,
    val charging: Boolean = false,
    val full: Boolean = false,
    /** Watts going into the battery, when the device reports it. */
    val watts: Double? = null,
    val temperatureC: Double? = null,
    /** Milliseconds until full, or null if the system doesn't know. */
    val timeToFullMs: Long? = null,
) {
    val wireless get() = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    val pluggedIn get() = plugged != 0
    val source get() = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_AC -> "Wall"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "Battery"
    }
}

object Power {
    fun parse(context: Context, intent: Intent): PowerState {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // Some devices report microamps, a few report milliamps, and the sign convention varies.
        val currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val amps = when {
            currentRaw == Int.MIN_VALUE || currentRaw == 0 -> null
            kotlin.math.abs(currentRaw) > 20_000 -> kotlin.math.abs(currentRaw) / 1_000_000.0
            else -> kotlin.math.abs(currentRaw) / 1_000.0
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val remaining = bm.computeChargeTimeRemaining()
        return PowerState(
            level = level * 100 / scale,
            plugged = plugged,
            charging = charging,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            watts = if (charging && amps != null && voltageMv > 0) amps * voltageMv / 1000.0 else null,
            temperatureC = if (tenthsC != Int.MIN_VALUE) tenthsC / 10.0 else null,
            timeToFullMs = remaining.takeIf { it > 0 },
        )
    }

    fun current(context: Context): PowerState? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { parse(context, it) }

    fun flow(context: Context): Flow<PowerState> = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                trySend(parse(c, i))
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
