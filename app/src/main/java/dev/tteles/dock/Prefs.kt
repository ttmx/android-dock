package dev.tteles.dock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Everything the user configures. Entity ids are plain Home Assistant ids, lists are comma separated. */
data class DockConfig(
    val haUrl: String = "",
    val haToken: String = "",
    val carName: String = "Car",
    val carBattery: String = "",
    val carChargeTime: String = "",
    val carCharging: String = "",
    val carRange: String = "",
    val climate: String = "",
    val covers: List<String> = emptyList(),
    val toggles: List<String> = emptyList(),
    val weather: String = "",
    val showWeather: Boolean = true,
    val wirelessOnly: Boolean = true,
    val nightMode: Boolean = true,
    val use24h: Boolean = true,
    val autoUpdate: Boolean = true,
) {
    val haConfigured get() = haUrl.isNotBlank() && haToken.isNotBlank()
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("dock", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<DockConfig> = _config

    private fun read() = DockConfig(
        haUrl = sp.getString("haUrl", "")!!,
        haToken = sp.getString("haToken", "")!!,
        carName = sp.getString("carName", "Car")!!,
        carBattery = sp.getString("carBattery", "")!!,
        carChargeTime = sp.getString("carChargeTime", "")!!,
        carCharging = sp.getString("carCharging", "")!!,
        carRange = sp.getString("carRange", "")!!,
        climate = sp.getString("climate", "")!!,
        covers = sp.getString("covers", "")!!.splitIds(),
        toggles = sp.getString("toggles", "")!!.splitIds(),
        weather = sp.getString("weather", "")!!,
        showWeather = sp.getBoolean("showWeather", true),
        wirelessOnly = sp.getBoolean("wirelessOnly", true),
        nightMode = sp.getBoolean("nightMode", true),
        use24h = sp.getBoolean("use24h", true),
        autoUpdate = sp.getBoolean("autoUpdate", true),
    )

    fun update(block: (DockConfig) -> DockConfig) {
        val c = block(_config.value)
        sp.edit()
            .putString("haUrl", c.haUrl.trim().trimEnd('/'))
            .putString("haToken", c.haToken.trim())
            .putString("carName", c.carName)
            .putString("carBattery", c.carBattery.trim())
            .putString("carChargeTime", c.carChargeTime.trim())
            .putString("carCharging", c.carCharging.trim())
            .putString("carRange", c.carRange.trim())
            .putString("climate", c.climate.trim())
            .putString("covers", c.covers.joinToString(","))
            .putString("toggles", c.toggles.joinToString(","))
            .putString("weather", c.weather.trim())
            .putBoolean("showWeather", c.showWeather)
            .putBoolean("wirelessOnly", c.wirelessOnly)
            .putBoolean("nightMode", c.nightMode)
            .putBoolean("use24h", c.use24h)
            .putBoolean("autoUpdate", c.autoUpdate)
            .apply()
        _config.value = read()
    }
}

fun String.splitIds(): List<String> = split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
