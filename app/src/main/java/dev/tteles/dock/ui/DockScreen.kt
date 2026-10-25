package dev.tteles.dock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tteles.dock.DockConfig
import dev.tteles.dock.ha.EntityState
import dev.tteles.dock.ha.HaStatus
import dev.tteles.dock.system.DockNotification
import dev.tteles.dock.system.NowPlaying
import dev.tteles.dock.system.PowerState
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.random.Random

typealias CallService = (domain: String, service: String, entityId: String, data: JSONObject?) -> Unit

private const val CONTROLS_TIMEOUT_MS = 20_000L

@Composable
fun DockScreen(
    config: DockConfig,
    power: PowerState?,
    states: Map<String, EntityState>,
    haStatus: HaStatus,
    notifications: List<DockNotification>,
    notificationAccess: Boolean,
    nowPlaying: NowPlaying?,
    night: Boolean,
    callService: CallService,
    openNotification: (DockNotification) -> Unit,
    dismissNotification: (DockNotification) -> Unit,
    openSettings: () -> Unit,
    close: () -> Unit,
) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }
    // Nudge everything a few pixels every minute so the clock doesn't burn into an OLED panel.
    var shift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(now.minute) { shift = Random.nextInt(-6, 7) to Random.nextInt(-6, 7) }
    val dx by animateDpAsState(shift.first.dp, tween(3_000), label = "dx")
    val dy by animateDpAsState(shift.second.dp, tween(3_000), label = "dy")

    var controls by remember { mutableStateOf(false) }
    // Bumped on every touch inside the controls so they only fade out once you leave them alone.
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controls, touches) {
        if (controls) {
            delay(CONTROLS_TIMEOUT_MS)
            controls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (night) Modifier.nightFilter() else Modifier)
    ) {
        Ambient(
            now, config, power, states, haStatus, notifications, notificationAccess, nowPlaying,
            openNotification, dismissNotification,
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null) { controls = true }
                .offset { IntOffset(dx.roundToPx(), dy.roundToPx()) }
                .padding(horizontal = 28.dp, vertical = 20.dp),
        )
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut()) {
            ControlsLayer(
                config, states, haStatus, nowPlaying, callService,
                onTouch = { touches++ },
                hide = { controls = false },
                openSettings = openSettings,
                exit = close,
            )
        }
    }
}

/** iOS-style night mode: everything rendered in dim red so it's easy on the eyes in the dark. */
private fun Modifier.nightFilter() = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = 0.75f)
    .drawWithContent {
        // Paint black first: multiplying over transparent pixels would turn them red, not keep them off.
        drawRect(Color.Black)
        drawContent()
        drawRect(Color(0xFFFF1A1A), blendMode = BlendMode.Multiply)
    }

/**
 * The always-on view, Pixel-screensaver style: a huge clock with the date and battery rings under
 * it, and notifications alongside. Everything else waits behind a tap.
 */
@Composable
private fun Ambient(
    now: LocalDateTime,
    config: DockConfig,
    power: PowerState?,
    states: Map<String, EntityState>,
    haStatus: HaStatus,
    notifications: List<DockNotification>,
    notificationAccess: Boolean,
    nowPlaying: NowPlaying?,
    openNotification: (DockNotification) -> Unit,
    dismissNotification: (DockNotification) -> Unit,
    modifier: Modifier,
) {
    val showCar = config.haConfigured && config.carBattery.isNotBlank()
    val footer = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DateLine(now, config, states, nowPlaying)
            PhoneRing(power)
            if (showCar) CarRing(config, states, haStatus)
        }
    }
    val peek = @Composable { mod: Modifier ->
        if (notificationAccess) NotificationPeek(notifications, openNotification, dismissNotification, mod)
        else Box(mod)
    }
    BoxWithConstraints(modifier) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                BoxWithConstraints(Modifier.weight(1.25f).fillMaxHeight()) {
                    // Leave a little side margin and ~120dp under the clock for the footer.
                    val size = minOf(maxWidth.value / (CLOCK_EM_WIDTH + 0.1f), maxHeight.value - 120f).sp
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        BigClock(now, config.use24h, size)
                        Spacer(Modifier.height(12.dp))
                        footer()
                    }
                }
                peek(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp))
            }
        } else {
            val clockSize = (maxWidth.value / (CLOCK_EM_WIDTH + 0.1f)).sp
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(0.3f))
                BigClock(now, config.use24h, clockSize)
                Spacer(Modifier.height(8.dp))
                footer()
                Spacer(Modifier.height(28.dp))
                peek(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Everything you can poke at, on its own layer so the ambient view stays calm. Never scrolls. */
@Composable
private fun ControlsLayer(
    config: DockConfig,
    states: Map<String, EntityState>,
    haStatus: HaStatus,
    nowPlaying: NowPlaying?,
    callService: CallService,
    onTouch: () -> Unit,
    hide: () -> Unit,
    openSettings: () -> Unit,
    exit: () -> Unit,
) {
    val items = buildList<@Composable (Modifier) -> Unit> {
        if (config.haConfigured) {
            if (config.climate.isNotBlank()) add { m -> EntityTile(config.climate, "climate", states, haStatus, callService, m) }
            config.covers.forEach { id -> add { m -> EntityTile(id, "cover", states, haStatus, callService, m) } }
            config.toggles.forEach { id -> add { m -> EntityTile(id, "toggle", states, haStatus, callService, m) } }
        }
        if (nowPlaying != null) add { m -> NowPlayingTile(nowPlaying, m) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onTouch()
                    }
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = hide)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (config.haConfigured && haStatus in setOf(HaStatus.AuthFailed, HaStatus.Error)) {
                    Text(
                        if (haStatus == HaStatus.AuthFailed) "Home Assistant rejected the token" else "Home Assistant offline",
                        color = DockColors.Red, fontSize = 13.sp,
                    )
                }
                IconButton(onClick = openSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = DockColors.Dim) }
                IconButton(onClick = exit) { Icon(Icons.Rounded.Close, "Close dock", tint = DockColors.Dim) }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (!config.haConfigured) "Connect Home Assistant in settings ⚙️"
                        else "Pick your AC, blinds and switches in settings ⚙️",
                        color = DockColors.Dim, fontSize = 18.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                FitGrid(items, Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp))
            }
        }
    }
}

/**
 * Same-size tiles, centred, that always fit the space: a few items stay compact instead of
 * stretching, and many items shrink rather than scroll.
 */
@Composable
private fun FitGrid(items: List<@Composable (Modifier) -> Unit>, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val n = items.size
        val columns = when {
            landscape && n <= 4 -> n
            landscape -> (n + 1) / 2
            n <= 2 -> 1
            else -> 2
        }
        val rows = (n + columns - 1) / columns
        val gap = 12.dp
        val cellWidth = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(340.dp)
        val cellHeight = ((maxHeight - gap * (rows - 1)) / rows).coerceAtMost(168.dp)
        Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { it(Modifier.size(cellWidth, cellHeight)) }
                }
            }
        }
    }
}

@Composable
private fun EntityTile(
    id: String,
    kind: String,
    states: Map<String, EntityState>,
    haStatus: HaStatus,
    call: CallService,
    modifier: Modifier,
) {
    val e = states[id]
    when {
        e == null -> MissingTile(id, haStatus, modifier)
        kind == "climate" && e.domain == "climate" -> ClimateTile(e, call, modifier)
        kind == "cover" && e.domain == "cover" -> CoverTile(e, call, modifier)
        else -> ToggleTile(e, call, modifier)
    }
}
