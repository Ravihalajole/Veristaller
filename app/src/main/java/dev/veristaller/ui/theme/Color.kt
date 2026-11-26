package dev.veristaller.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** False when the user selected the light palette. */
internal val LocalVeriDark = compositionLocalOf { true }

/**
 * Raw palette values. Screens read the composable tokens below; the Material
 * colour schemes read these directly so they never recurse into a composable.
 */
object VeriPalette {
    object Dark {
        val bg = Color(0xFF0E1116)
        val surfaceLow = Color(0xFF14181F)
        val surfaceHigh = Color(0xFF1C222B)
        val surfaceTop = Color(0xFF232B36)
        val outline = Color(0xFF2C3542)
        val textPrimary = Color(0xFFE6EAF2)
        val textSecondary = Color(0xFF9AA6B8)
        val textTertiary = Color(0xFF6F7C8F)
        val primary = Color(0xFF7CC4FF)
        val onPrimary = Color(0xFF00243D)
        val success = Color(0xFF5BE49B)
        val warning = Color(0xFFF5C451)
        val danger = Color(0xFFFF7A7A)
        val info = Color(0xFFB69CFF)
    }

    object Light {
        val bg = Color(0xFFF7F9FC)
        val surfaceLow = Color(0xFFFFFFFF)
        val surfaceHigh = Color(0xFFEEF2F7)
        val surfaceTop = Color(0xFFE3E9F1)
        val outline = Color(0xFFC6CFD9)
        val textPrimary = Color(0xFF111820)
        val textSecondary = Color(0xFF4B5563)
        val textTertiary = Color(0xFF6B7280)
        val primary = Color(0xFF0B5A8E)
        val onPrimary = Color(0xFFFFFFFF)
        val success = Color(0xFF147D4B)
        val warning = Color(0xFF8A5A00)
        val danger = Color(0xFFB3261E)
        val info = Color(0xFF5B3FA8)
    }
}

@Composable private fun pick(dark: Color, light: Color): Color =
    if (LocalVeriDark.current) dark else light

val Bg: Color @Composable get() = pick(VeriPalette.Dark.bg, VeriPalette.Light.bg)
val SurfaceLow: Color @Composable get() = pick(VeriPalette.Dark.surfaceLow, VeriPalette.Light.surfaceLow)
val SurfaceHigh: Color @Composable get() = pick(VeriPalette.Dark.surfaceHigh, VeriPalette.Light.surfaceHigh)
val SurfaceTop: Color @Composable get() = pick(VeriPalette.Dark.surfaceTop, VeriPalette.Light.surfaceTop)
val OutlineColor: Color @Composable get() = pick(VeriPalette.Dark.outline, VeriPalette.Light.outline)
val TextPrimary: Color @Composable get() = pick(VeriPalette.Dark.textPrimary, VeriPalette.Light.textPrimary)
val TextSecondary: Color @Composable get() = pick(VeriPalette.Dark.textSecondary, VeriPalette.Light.textSecondary)
val TextTertiary: Color @Composable get() = pick(VeriPalette.Dark.textTertiary, VeriPalette.Light.textTertiary)
val PrimaryBlue: Color @Composable get() = pick(VeriPalette.Dark.primary, VeriPalette.Light.primary)
val OnPrimaryBlue: Color @Composable get() = pick(VeriPalette.Dark.onPrimary, VeriPalette.Light.onPrimary)
val SuccessGreen: Color @Composable get() = pick(VeriPalette.Dark.success, VeriPalette.Light.success)
val WarningAmber: Color @Composable get() = pick(VeriPalette.Dark.warning, VeriPalette.Light.warning)
val DangerRed: Color @Composable get() = pick(VeriPalette.Dark.danger, VeriPalette.Light.danger)
val InfoPurple: Color @Composable get() = pick(VeriPalette.Dark.info, VeriPalette.Light.info)

// The terminal keeps its own palette in both themes.
val TerminalBg: Color = Color(0xFF0A0D12)
val TerminalText: Color = Color(0xFFA8FFC8)
