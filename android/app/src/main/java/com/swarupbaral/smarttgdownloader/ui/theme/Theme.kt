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
    error = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = Violet,
    secondary = VioletLight,
    background = Color(0xFFFDFDFF),
    surface = Color(0xFFF6F6FB),
    error = Color(0xFFB3261E)
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
