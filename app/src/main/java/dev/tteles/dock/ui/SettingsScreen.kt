package dev.tteles.dock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tteles.dock.DockConfig
import dev.tteles.dock.ha.EntityState
import dev.tteles.dock.splitIds
import kotlinx.coroutines.launch

data class SetupItem(val title: String, val why: String, val granted: Boolean, val grant: () -> Unit)

/** What a field accepts, plus a score so likely candidates float to the top of the picker. */
class EntityKind(val domains: Set<String>, val score: (EntityState) -> Int = { 0 })

private fun EntityState.text() = (id + " " + name).lowercase()
private fun EntityState.has(vararg words: String) = words.any { text().contains(it) }
private val carWords = arrayOf("car", "tesla", "ev_", "vehicle", "model", "leaf", "ioniq", "kia", "bmw", "polestar", "id.", "mg", "byd")

object Kinds {
    val carBattery = EntityKind(setOf("sensor")) {
        (if (it.attr("device_class") == "battery") 2 else 0) + (if (it.has(*carWords)) 3 else 0) +
            (if (it.has("battery_level", "soc", "state_of_charge")) 2 else 0) - (if (it.has("phone", "pixel")) 4 else 0)
    }
    val carChargeTime = EntityKind(setOf("sensor")) {
        (if (it.has("time_to_full", "time to full", "charge_complete", "charging_end", "remaining_charg", "time_charge")) 5 else 0) +
            (if (it.attr("device_class") in setOf("duration", "timestamp") && it.has("charg")) 3 else 0) +
            (if (it.has(*carWords)) 1 else 0)
    }
    val carCharging = EntityKind(setOf("binary_sensor", "sensor")) {
        (if (it.attr("device_class") in setOf("battery_charging", "plug")) 3 else 0) +
            (if (it.has("charging", "charger", "charge_state", "plugged")) 3 else 0) + (if (it.has(*carWords)) 1 else 0)
    }
    val carRange = EntityKind(setOf("sensor")) {
        (if (it.has("range")) 4 else 0) + (if (it.attr("device_class") == "distance") 2 else 0) + (if (it.has(*carWords)) 1 else 0)
    }
    val climate = EntityKind(setOf("climate", "switch", "fan"), score = {
        (if (it.domain == "climate") 3 else 0) + (if (it.has("ac", "air", "aircon", "hvac")) 2 else 0)
    })
    val cover = EntityKind(setOf("cover")) { if (it.has("blind", "shade", "shutter", "estore", "curtain")) 2 else 0 }
    val toggle = EntityKind(
        setOf("light", "switch", "fan", "input_boolean", "scene", "script", "lock", "button", "input_button", "automation", "cover", "climate")
    ) { if (it.domain in setOf("light", "switch", "scene")) 1 else 0 }
    val weather = EntityKind(setOf("weather")) { if (it.has("home", "forecast")) 1 else 0 }
}

@Composable
fun SettingsScreen(
    config: DockConfig,
    setup: List<SetupItem>,
    update: ((DockConfig) -> DockConfig) -> Unit,
    loadEntities: suspend (url: String, token: String) -> Result<List<EntityState>>,
    preview: () -> Unit,
    version: String,
    checkForUpdate: suspend () -> String,
) {
    var entities by remember { mutableStateOf<List<EntityState>>(emptyList()) }
    var testStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val test = {
        testing = true
        scope.launch {
            val r = loadEntities(config.haUrl, config.haToken)
            entities = r.getOrDefault(emptyList())
            testStatus = r.fold(
                { true to "Connected · ${it.size} entities. Tap the list icons below to pick." },
                { false to (it.message ?: it.javaClass.simpleName) },
            )
            testing = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dock", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("StandBy for your Android, powered by Home Assistant", color = DockColors.Dim)
                }
                Button(onClick = preview) { Text("Open dock") }
            }

            Section("Setup")
            setup.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
                        tint = if (item.granted) DockColors.Green else DockColors.Orange,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White)
                        Text(item.why, color = DockColors.Dim, fontSize = 13.sp)
                    }
                    if (!item.granted) FilledTonalButton(onClick = item.grant) { Text("Allow") }
                }
            }

            Section("Home Assistant")
            Field("URL", config.haUrl, "http://homeassistant.local:8123", KeyboardType.Uri) { v -> update { it.copy(haUrl = v) } }
            Field("Long-lived access token", config.haToken, "Profile → Security → Create token", secret = true) { v ->
                update { it.copy(haToken = v) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { test() }, enabled = config.haConfigured && !testing) {
                    Text(if (testing) "Connecting…" else "Test & load entities")
                }
                Spacer(Modifier.width(12.dp))
                testStatus?.let { (ok, msg) ->
                    Text(msg, color = if (ok) DockColors.Green else DockColors.Red, fontSize = 13.sp)
                }
            }

            Section("Car")
            Field("Name", config.carName, "Car") { v -> update { it.copy(carName = v) } }
            EntityField("Battery level", config.carBattery, Kinds.carBattery, entities) { v -> update { it.copy(carBattery = v) } }
            EntityField(
                "Time until charged", config.carChargeTime, Kinds.carChargeTime, entities,
                help = "Minutes, hours, or a 'charge complete at' timestamp all work",
            ) { v -> update { it.copy(carChargeTime = v) } }
            EntityField(
                "Charging status (optional)", config.carCharging, Kinds.carCharging, entities,
                help = "Binary sensor or a sensor reading 'charging'",
            ) { v -> update { it.copy(carCharging = v) } }
            EntityField("Range (optional)", config.carRange, Kinds.carRange, entities) { v -> update { it.copy(carRange = v) } }

            Section("Home controls")
            EntityField("Air conditioning", config.climate, Kinds.climate, entities) { v -> update { it.copy(climate = v) } }
            EntityField(
                "Blinds", config.covers.joinToString(", "), Kinds.cover, entities, multi = true,
            ) { v -> update { it.copy(covers = v.splitIds()) } }
            EntityField(
                "Extra buttons", config.toggles.joinToString(", "), Kinds.toggle, entities, multi = true,
                help = "Lights, switches, scenes, scripts, locks…",
            ) { v -> update { it.copy(toggles = v.splitIds()) } }
            EntityField("Weather", config.weather, Kinds.weather, entities) { v -> update { it.copy(weather = v) } }

            Section("Behaviour")
            Toggle("Wireless chargers only", "Stay dark on cables", config.wirelessOnly) { v -> update { it.copy(wirelessOnly = v) } }
            Toggle("Red night mode", "Dims to red in a dark room", config.nightMode) { v -> update { it.copy(nightMode = v) } }
            Toggle("24-hour clock", null, config.use24h) { v -> update { it.copy(use24h = v) } }

            Section("Updates")
            Toggle("Install updates automatically", "From GitHub releases, never while the dock is open", config.autoUpdate) { v ->
                update { it.copy(autoUpdate = v) }
            }
            var updateStatus by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Version $version", color = DockColors.Dim, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    updateStatus = "Checking…"
                    scope.launch {
                        updateStatus = try { checkForUpdate() } catch (e: Exception) { e.message ?: "Update failed" }
                    }
                }) { Text("Check now") }
            }
            updateStatus?.let { Text(it, color = DockColors.Dim, fontSize = 13.sp) }
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.size(8.dp))
    Text(title.uppercase(), color = DockColors.Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    HorizontalDivider(color = DockColors.Tile)
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            if (subtitle != null) Text(subtitle, color = DockColors.Dim, fontSize = 13.sp)
        }
        Switch(value, onChange)
    }
}

/**
 * Text fields keep their own text while typing so trimming/splitting on save doesn't fight the
 * cursor; the stored value only seeds them.
 */
@Composable
private fun Field(
    label: String,
    initial: String,
    placeholder: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    help: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    textOverride: String? = null,
    onChange: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    if (textOverride != null && textOverride != text) text = textOverride
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EntityField(
    label: String,
    value: String,
    kind: EntityKind,
    entities: List<EntityState>,
    multi: Boolean = false,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var override by remember { mutableStateOf<String?>(null) }
    Field(
        label, value,
        placeholder = kind.domains.first() + ".…",
        help = help ?: if (entities.isEmpty()) "Test the connection above to pick from a list" else null,
        textOverride = override,
        trailing = {
            IconButton(onClick = { picking = true }, enabled = entities.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.List, "Pick entity")
            }
        },
        onChange = { override = null; onChange(it) },
    )
    if (picking) {
        EntityPicker(kind, entities, onDismiss = { picking = false }) { id ->
            picking = false
            val current = (override ?: value).splitIds()
            val next = if (multi) (current + id).distinct().joinToString(", ") else id
            override = next
            onChange(next)
        }
    }
}

@Composable
private fun EntityPicker(kind: EntityKind, entities: List<EntityState>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(entities, kind) {
        entities.filter { it.domain in kind.domains }.sortedWith(compareByDescending<EntityState> { kind.score(it) }.thenBy { it.id })
    }
    val shown = candidates.filter { query.isBlank() || it.text().contains(query.lowercase()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick an entity") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(e.id) }.padding(vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                                if (query.isBlank() && kind.score(e) > 0 && e == shown.firstOrNull()) {
                                    Text("suggested", color = DockColors.Orange, fontSize = 11.sp)
                                }
                            }
                            Text(
                                "${e.id} · ${e.state}${e.unit.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}",
                                color = DockColors.Dim, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                    }
                    if (shown.isEmpty()) item { Text("Nothing matches", color = DockColors.Dim) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}
