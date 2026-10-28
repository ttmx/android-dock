package dev.tteles.dock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.tteles.dock.DockConfig
import dev.tteles.dock.R
import dev.tteles.dock.ha.EntityState
import dev.tteles.dock.ha.HaStatus
import dev.tteles.dock.system.DockNotification
import dev.tteles.dock.system.NowPlaying
import dev.tteles.dock.system.PowerState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Nunito Sans Black, cut down to digits and colon (see third_party/fonts). Every time is 2.685em wide. */
private val ClockFont = FontFamily(Font(R.font.nunito_sans_black_digits))
const val CLOCK_EM_WIDTH = 2.685f

/**
 * The digits run from 0.717em above the baseline to 0.012em below it, but the font's line is 1.364em
 * tall. The clock takes up just the digits (with a hair to spare) so layouts can size it to fit.
 */
private const val CLOCK_EM_ASCENT = 0.74f
const val CLOCK_EM_HEIGHT = 0.8f

@Composable
fun BigClock(now: LocalDateTime, use24h: Boolean, size: TextUnit) {
    Text(
        now.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm")),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = size,
        fontFamily = ClockFont,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.layout { measurable, constraints ->
            val text = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val em = size.toPx()
            layout(text.width, (em * CLOCK_EM_HEIGHT).roundToInt()) {
                text.place(0, (em * CLOCK_EM_ASCENT).roundToInt() - text[FirstBaseline])
            }
        },
    )
}

/** The biggest clock that fits in [width] by [height]. */
@Composable
fun clockSize(width: Dp, height: Dp = Dp.Infinity): TextUnit = with(LocalDensity.current) {
    minOf(width / (CLOCK_EM_WIDTH + 0.1f), height / CLOCK_EM_HEIGHT).toSp()
}

/**
 * "Sun 25", accent day and muted number, plus weather and what's playing when there's any. The
 * weather drops to a second line when there's no room for it beside the date.
 */
@Composable
fun DateLine(
    now: LocalDateTime,
    config: DockConfig,
    states: Map<String, EntityState>,
    nowPlaying: NowPlaying?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val weather = states[config.weather]?.takeIf { it.available && config.haConfigured }
    Column(modifier) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold)) {
                    append(now.format(DateTimeFormatter.ofPattern("EEE")))
                }
                withStyle(SpanStyle(color = colors.onSurfaceVariant)) {
                    // Non-breaking spaces so a wrap only ever happens between the date and the weather.
                    append("\u00A0${now.dayOfMonth}")
                    if (weather != null) {
                        append("   ${weatherEmoji(weather.state)}")
                        weather.attrDouble("temperature")?.let { append("\u00A0${formatNumber(it)}°") }
                    }
                }
            },
            fontSize = 34.sp,
            lineHeight = 40.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        nowPlaying?.takeIf { it.playing }?.let {
            Text(
                "♪  ${it.title}" + if (it.artist.isNotBlank()) " — ${it.artist}" else "",
                color = colors.onSurfaceVariant, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A battery ring with the number inside and a tiny caption underneath. */
@Composable
private fun BatteryRing(level: Int?, charging: Boolean, icon: ImageVector, caption: String?, ring: Dp = 76.dp) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        level == null -> colors.outline
        level <= 20 && !charging -> DockColors.Red
        else -> colors.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Ring((level ?: 0) / 100f, color, Modifier.size(ring), track = colors.surfaceContainerHighest) {
            Text(
                level?.toString() ?: "—",
                color = colors.onSurface, fontSize = (ring.value * 0.27f).sp, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Fixed height so rings line up whether or not their caption has text.
        Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            if (charging) Icon(Icons.Rounded.Bolt, "Charging", tint = colors.primary, modifier = Modifier.size(14.dp))
            if (caption != null) {
                Spacer(Modifier.width(3.dp))
                Text(caption, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun PhoneRing(power: PowerState?) {
    val caption = power?.takeIf { it.charging }?.timeToFullMs?.let { formatDuration(it) }
    BatteryRing(power?.level, power?.charging == true, Icons.Rounded.Smartphone, caption)
}

@Composable
fun CarRing(config: DockConfig, states: Map<String, EntityState>, haStatus: HaStatus) {
    val battery = states[config.carBattery]?.takeIf { it.available }
    val chargeTime = chargeTimeRemainingMs(states[config.carChargeTime])
    val charging = states[config.carCharging]?.takeIf { it.available }?.chargingLike() ?: (chargeTime != null)
    val caption = when {
        battery == null -> when (haStatus) {
            HaStatus.AuthFailed -> "Bad token"
            HaStatus.Error -> "HA offline"
            HaStatus.Connected -> "Not found"
            else -> null
        }
        charging -> chargeTime?.let { formatDuration(it) }
        else -> states[config.carRange]?.takeIf { it.available }?.let { r ->
            r.state.toDoubleOrNull()?.let { "${formatNumber(it)} ${r.unit}".trim() }
        }
    }
    BatteryRing(battery?.state?.toDoubleOrNull()?.toInt(), charging, Icons.Rounded.ElectricCar, caption)
}

private val PeekRowHeight = 64.dp

/** The latest notifications, as many as fit. Tap to open, swipe to dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPeek(
    notifications: List<DockNotification>,
    open: (DockNotification) -> Unit,
    dismiss: (DockNotification) -> Unit,
    modifier: Modifier,
) {
    // Always take the space, even when empty, so the clock beside it doesn't jump when one arrives.
    BoxWithConstraints(modifier) {
        if (notifications.isEmpty()) return@BoxWithConstraints
        val gap = 8.dp
        val fit = ((maxHeight + gap) / (PeekRowHeight + gap)).toInt().coerceAtLeast(1)
        // Leave room for the "+N" line when the list doesn't fit.
        val shown = if (notifications.size > fit) notifications.take((fit - 1).coerceAtLeast(1)) else notifications
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { n ->
                key(n.key) {
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
                        if (it != SwipeToDismissBoxValue.Settled) dismiss(n)
                        it != SwipeToDismissBoxValue.Settled
                    })
                    SwipeToDismissBox(
                        state = state,
                        backgroundContent = {},
                        enableDismissFromStartToEnd = n.clearable,
                        enableDismissFromEndToStart = n.clearable,
                    ) { PeekRow(n, open) }
                }
            }
            val more = notifications.size - shown.size
            if (more > 0) {
                Text(
                    "+$more", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PeekRow(n: DockNotification, open: (DockNotification) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val icon = remember(n.key, n.icon) {
        runCatching { n.icon?.loadDrawable(context)?.toBitmap(48, 48)?.asImageBitmap() }.getOrNull()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(PeekRowHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceContainerLow)
            .clickable { open(n) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            val tint = ColorFilter.tint(colors.onPrimaryContainer)
            if (icon != null) Image(icon, null, Modifier.size(18.dp), colorFilter = tint)
            else Icon(Icons.Rounded.Notifications, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.appName, color = colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · ${formatRelative(n.time)}", color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
            Text(
                listOf(n.title, n.text).filter { it.isNotBlank() && it != n.appName }.joinToString(" — ").ifBlank { n.appName },
                color = colors.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
