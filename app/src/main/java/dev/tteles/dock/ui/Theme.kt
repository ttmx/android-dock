package dev.tteles.dock.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DockColors {
    val Tile = Color(0xFF1C1C1E)
    val TileActive = Color(0xFF2C2C2E)
    val Dim = Color(0xFF8E8E93)
    val Green = Color(0xFF34C759)
    val Yellow = Color(0xFFFFCC00)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Orange = Color(0xFFFF9F0A)
    val Cyan = Color(0xFF64D2FF)
}

/** Material You colours from the wallpaper where available, always on a pure black (OLED) background. */
@Composable
fun DockTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        darkColorScheme(primary = DockColors.Orange, secondary = DockColors.Blue)
    }
    MaterialTheme(
        colorScheme = scheme.copy(background = Color.Black, surface = Color.Black),
        content = content,
    )
}
