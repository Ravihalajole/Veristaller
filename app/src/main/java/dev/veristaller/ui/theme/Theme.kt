package dev.veristaller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = VeriPalette.Dark.primary,
    onPrimary = VeriPalette.Dark.onPrimary,
    primaryContainer = Color(0xFF1D3A52),
    onPrimaryContainer = VeriPalette.Dark.primary,
    secondary = VeriPalette.Dark.info,
    onSecondary = Color(0xFF1A1030),
    background = VeriPalette.Dark.bg,
    onBackground = VeriPalette.Dark.textPrimary,
    surface = VeriPalette.Dark.surfaceLow,
    onSurface = VeriPalette.Dark.textPrimary,
    surfaceVariant = VeriPalette.Dark.surfaceHigh,
    onSurfaceVariant = VeriPalette.Dark.textSecondary,
    outline = VeriPalette.Dark.outline,
    error = VeriPalette.Dark.danger,
    onError = Color(0xFF3A0A0A),
)

private val LightColors = lightColorScheme(
    primary = VeriPalette.Light.primary,
    onPrimary = VeriPalette.Light.onPrimary,
    primaryContainer = Color(0xFFD6EAFB),
    onPrimaryContainer = Color(0xFF001D32),
    secondary = VeriPalette.Light.info,
    onSecondary = Color(0xFFFFFFFF),
    background = VeriPalette.Light.bg,
    onBackground = VeriPalette.Light.textPrimary,
    surface = VeriPalette.Light.surfaceLow,
    onSurface = VeriPalette.Light.textPrimary,
    surfaceVariant = VeriPalette.Light.surfaceHigh,
    onSurfaceVariant = VeriPalette.Light.textSecondary,
    outline = VeriPalette.Light.outline,
    error = VeriPalette.Light.danger,
    onError = Color(0xFFFFFFFF),
)

@Composable
fun VeristallerTheme(
    dynamicColor: Boolean = false,
    theme: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalVeriDark provides dark) {
        MaterialTheme(
            colorScheme = colors,
            typography = VeriTypography,
            content = content,
        )
    }
}
