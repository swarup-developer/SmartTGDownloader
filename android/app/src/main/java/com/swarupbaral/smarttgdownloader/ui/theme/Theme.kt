package com.swarupbaral.smarttgdownloader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.swarupbaral.smarttgdownloader.data.ThemeMode

val Violet = Color(0xFF6C63FF)
val VioletLight = Color(0xFF8B80FF)
val SurfaceDark = Color(0xFF1B1E2B)
val BackgroundDark = Color(0xFF12141C)
val ErrorRed = Color(0xFFCF6679)

private val DarkColorScheme = darkColorScheme(
    primary = Violet,
    secondary = VioletLight,
    background = BackgroundDark,
    surface = SurfaceDark,
    error = ErrorRed,
    // The container tones are set explicitly, and this is the part that had been
    // missing: `surface` and `background` were the app's navy, but everything
    // drawn in a container tone — filled cards, menus, the sheet, the section
    // rows — falls back to the stock neutral greys. Next to a navy background
    // those read as a different app's surfaces. They are now tones of the same
    // navy, so a card looks like it belongs to the screen it sits on.
    surfaceVariant = Color(0xFF2C3047),
    surfaceContainerLowest = Color(0xFF0D0F16),
    surfaceContainerLow = Color(0xFF171A26),
    surfaceContainer = Color(0xFF1F2331),
    surfaceContainerHigh = Color(0xFF262B3B),
    surfaceContainerHighest = Color(0xFF2C3047),
    onSurfaceVariant = Color(0xFFC9CCE0),
    outlineVariant = Color(0xFF3A3F57)
)

private val LightColorScheme = lightColorScheme(
    primary = Violet,
    secondary = VioletLight,
    background = Color(0xFFFDFDFF),
    surface = Color(0xFFF6F6FB),
    error = Color(0xFFB3261E),
    // The same roles, tinted towards the brand violet rather than the stock
    // warm greys, which are visibly beige against this near-white background.
    surfaceVariant = Color(0xFFE7E5F5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F6FD),
    surfaceContainer = Color(0xFFF1F0FA),
    surfaceContainerHigh = Color(0xFFE9E8F4),
    surfaceContainerHighest = Color(0xFFE2E1EF),
    onSurfaceVariant = Color(0xFF45465A),
    outlineVariant = Color(0xFFC7C6DA)
)

/** Resolves a [ThemeMode] to an actual dark/light decision. */
@Composable
fun isAppDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun SmartTGTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = isAppDarkTheme(themeMode)
    val context = LocalContext.current
    val colorScheme = when {
        // Material You wallpaper palette, Android 12+ only.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
