package dev.tteles.dock.ui

import dev.tteles.dock.ha.EntityState
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000.0).roundToLong()
    if (totalMin < 1) return "<1 min"
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "$m min"
        m == 0L -> "$h h"
        else -> "$h h $m min"
    }
}

fun formatRelative(time: Long, now: Long = System.currentTimeMillis()): String {
    val min = (now - time) / 60_000
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 24 * 60 -> "${min / 60}h"
        else -> "${min / (24 * 60)}d"
    }
}

/**
 * Car integrations disagree wildly on how they report time-to-full: hours, minutes, seconds,
 * or a timestamp of when charging completes. Normalise all of those to milliseconds.
 */
fun chargeTimeRemainingMs(e: EntityState?, now: OffsetDateTime = OffsetDateTime.now()): Long? {
    if (e == null || !e.available) return null
    val n = e.state.toDoubleOrNull()
    if (n != null) {
        val ms = when (e.unit.lowercase()) {
            "h", "hr", "hrs", "hours" -> n * 3_600_000
            "s", "sec" -> n * 1_000
            "d" -> n * 86_400_000
            else -> n * 60_000 // "min" and unit-less; minutes is the common case
        }
        return ms.toLong().takeIf { it > 0 }
    }
    return try {
        Duration.between(now, OffsetDateTime.parse(e.state)).toMillis().takeIf { it > 0 }
    } catch (ex: DateTimeParseException) {
        null
    }
}

fun prettyState(s: String) = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun EntityState.isOn() = state in setOf("on", "open", "opening", "unlocked", "playing", "home", "true")

/**
 * Integrations spell "charging" many ways: "on", "Charging", "charging_ac", VW's
 * "CHARGE_STATE_CHARGING"… while "CHARGE_STATE_NOT_READY_FOR_CHARGING", "ready_for_charging",
 * "discharging" and "charging_complete" all mean it isn't.
 */
fun EntityState.chargingLike(): Boolean {
    val s = state.lowercase().replace('-', '_').replace(' ', '_')
    if (s == "on" || s == "true") return true
    if (!s.contains("charging") || s.contains("discharging")) return false
    return listOf("not", "ready", "complete", "finished", "stopped", "paused").none { s.contains(it) }
}

fun formatNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

fun weatherEmoji(condition: String) = when (condition) {
    "sunny" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rainy", "pouring" -> "🌧️"
    "lightning", "lightning-rainy" -> "⛈️"
    "snowy", "snowy-rainy", "hail" -> "🌨️"
    "windy", "windy-variant" -> "💨"
    else -> "🌡️"
}
