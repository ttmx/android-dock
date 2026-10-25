package dev.tteles.dock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Toys
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tteles.dock.ha.EntityState
import dev.tteles.dock.ha.HaStatus
import dev.tteles.dock.system.NowPlaying
import org.json.JSONObject

private val TileShape = RoundedCornerShape(28.dp)

@Composable
fun Tile(modifier: Modifier, active: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(TileShape)
            .background(if (active) DockColors.TileActive else DockColors.Tile)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
    ) { content() }
}

fun batteryColor(level: Int, charging: Boolean) = when {
    charging || level > 50 -> DockColors.Green
    level > 20 -> DockColors.Yellow
    else -> DockColors.Red
}

@Composable
fun Ring(
    fraction: Float,
    color: Color,
    modifier: Modifier,
    track: Color = color.copy(alpha = 0.18f),
    content: @Composable () -> Unit,
) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(
                color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

@Composable
fun MissingTile(id: String, status: HaStatus, modifier: Modifier) {
    Tile(modifier) {
        Column {
            Text(id, color = DockColors.Dim, fontSize = 14.sp)
            Text(
                if (status == HaStatus.Connected) "Entity not found" else "Waiting for Home Assistant…",
                color = DockColors.Dim, fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    desc: String,
    tint: Color = Color.White,
    bg: Color = Color(0xFF3A3A3C),
    size: Dp = 52.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.54f)) }
}

@Composable
fun ClimateTile(e: EntityState, call: CallService, modifier: Modifier) {
    val on = e.state != "off" && e.available
    val target = e.attrDouble("temperature")
    val current = e.attrDouble("current_temperature")
    val step = e.attrDouble("target_temp_step") ?: 0.5
    // Show the requested setpoint straight away instead of waiting for HA to echo it back.
    var pending by remember(target) { mutableStateOf<Double?>(null) }
    val shown = pending ?: target
    val accent = when (e.state) {
        "heat" -> DockColors.Orange
        "off" -> DockColors.Dim
        else -> DockColors.Cyan
    }
    Tile(modifier, active = on) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 230.dp || maxHeight < 140.dp
            val btn = if (compact) 42.dp else 52.dp
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!compact) {
                        Icon(Icons.Rounded.AcUnit, null, tint = accent, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(e.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            prettyState(e.state) + (current?.let { " · ${formatNumber(it)}°" } ?: ""),
                            color = DockColors.Dim, fontSize = 14.sp, maxLines = 1,
                        )
                    }
                    RoundButton(
                        Icons.Rounded.PowerSettingsNew, if (on) "Turn off" else "Turn on",
                        tint = if (on) Color.Black else Color.White,
                        bg = if (on) accent else Color(0xFF3A3A3C),
                        size = btn,
                    ) { call("climate", if (on) "turn_off" else "turn_on", e.id, null) }
                }
                Spacer(Modifier.weight(1f))
                if (shown != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Rounded.Remove, "Colder", size = btn) {
                            val t = shown - step
                            pending = t
                            call("climate", "set_temperature", e.id, JSONObject().put("temperature", t))
                        }
                        Text(
                            "${formatNumber(shown)}°",
                            color = if (on) Color.White else DockColors.Dim,
                            fontSize = if (compact) 30.sp else 44.sp, fontWeight = FontWeight.Light,
                            maxLines = 1, softWrap = false,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                        RoundButton(Icons.Rounded.Add, "Warmer", size = btn) {
                            val t = shown + step
                            pending = t
                            call("climate", "set_temperature", e.id, JSONObject().put("temperature", t))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CoverTile(e: EntityState, call: CallService, modifier: Modifier) {
    val position = e.attrDouble("current_position")?.toInt()
    val closedFraction = 1f - (position ?: if (e.state == "closed") 0 else 100) / 100f
    val buttons = @Composable { size: Dp ->
        RoundButton(Icons.Rounded.KeyboardArrowUp, "Open", size = size) { call("cover", "open_cover", e.id, null) }
        RoundButton(Icons.Rounded.Stop, "Stop", size = size) { call("cover", "stop_cover", e.id, null) }
        RoundButton(Icons.Rounded.KeyboardArrowDown, "Close", size = size) { call("cover", "close_cover", e.id, null) }
    }
    Tile(modifier, active = e.isOn()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Tall tiles stack the buttons on the right; short (landscape) tiles put them in a row.
            val tall = maxHeight >= 180.dp
            val btn = if (tall) 52.dp else if (maxWidth < 200.dp || maxHeight < 110.dp) 40.dp else 46.dp
            val window = maxWidth >= 200.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(e.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = if (tall) 2 else 1, overflow = TextOverflow.Ellipsis)
                    // Just the position, unless it's moving or the cover can't report one.
                    val moving = e.state == "opening" || e.state == "closing"
                    Text(
                        listOfNotNull(prettyState(e.state).takeIf { moving || position == null }, position?.let { "$it%" })
                            .joinToString(" · "),
                        color = DockColors.Dim, fontSize = 14.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (window || tall) BlindWindow(closedFraction, if (tall) 56.dp else 40.dp, if (tall) 64.dp else btn)
                        if (!tall) {
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons(btn) }
                        }
                    }
                }
                if (tall) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(btn) }
            }
        }
    }
}

/** A little window whose blind is drawn down to match the cover position. */
@Composable
private fun BlindWindow(closedFraction: Float, width: Dp, height: Dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(DockColors.Cyan.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(closedFraction.coerceIn(0.04f, 1f))
                .background(Color(0xFFD1D1D6))
        )
    }
}

/** Anything else: lights, switches, fans, scenes, scripts, locks, buttons. Tap to act. */
@Composable
fun ToggleTile(e: EntityState, call: CallService, modifier: Modifier) {
    val on = e.isOn()
    val icon = when (e.domain) {
        "light" -> Icons.Rounded.Lightbulb
        "lock" -> if (e.state == "locked") Icons.Rounded.Lock else Icons.Rounded.LockOpen
        "fan" -> Icons.Rounded.Toys
        "scene", "script", "button", "input_button" -> Icons.Rounded.PlayArrow
        "climate" -> Icons.Rounded.AcUnit
        else -> Icons.Rounded.Power
    }
    val action = {
        when (e.domain) {
            "scene" -> call("scene", "turn_on", e.id, null)
            "script" -> call("script", "turn_on", e.id, null)
            "button", "input_button" -> call(e.domain, "press", e.id, null)
            "lock" -> call("lock", if (e.state == "locked") "unlock" else "lock", e.id, null)
            "cover" -> call("cover", "toggle", e.id, null)
            else -> call("homeassistant", "toggle", e.id, null)
        }
    }
    Tile(modifier, active = on, onClick = action) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val badge = @Composable { size: Dp ->
                Box(
                    Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(if (on) DockColors.Yellow else Color(0xFF3A3A3C)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(size * 0.54f)) }
            }
            val labels = @Composable {
                Text(e.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (maxHeight < 200.dp) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    badge(42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column { labels() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    badge(52.dp)
                    Spacer(Modifier.weight(1f))
                    labels()
                }
            }
        }
    }
}

@Composable
fun NowPlayingTile(np: NowPlaying, modifier: Modifier) {
    val controls = np.controller.transportControls
    Tile(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val btn = if (maxHeight < 130.dp) 40.dp else 48.dp
            val showArt = maxWidth >= 220.dp
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (showArt) {
                    Box(
                        Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (np.art != null) {
                            Image(np.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Rounded.MusicNote, null, tint = DockColors.Dim, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(np.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.artist.ifBlank { np.app }, color = DockColors.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Rounded.SkipPrevious, "Previous", bg = Color.Transparent, size = btn) { controls.skipToPrevious() }
                        RoundButton(
                            if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (np.playing) "Pause" else "Play",
                            tint = Color.Black, bg = Color.White, size = btn,
                        ) { if (np.playing) controls.pause() else controls.play() }
                        RoundButton(Icons.Rounded.SkipNext, "Next", bg = Color.Transparent, size = btn) { controls.skipToNext() }
                    }
                }
            }
        }
    }
}
