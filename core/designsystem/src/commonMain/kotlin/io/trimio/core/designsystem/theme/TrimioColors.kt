package io.trimio.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens. Dark is the primary, "cinematic" scheme: deep blue-black canvas so video
 * and motion previews pop, glass surfaces as translucent whites, neon accents for state.
 */
@Immutable
data class TrimioColors(
    val canvas: Color,
    val canvasRaised: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val primary: Color,
    val onPrimary: Color,
    val accentCyan: Color,
    val accentMagenta: Color,
    val accentAmber: Color,
    val success: Color,
    val danger: Color,
    /** Aurora shader palette: base + three light bands. */
    val auroraBase: Color,
    val auroraA: Color,
    val auroraB: Color,
    val auroraC: Color,
    val isDark: Boolean,
)

val DarkTrimioColors = TrimioColors(
    canvas = Color(0xFF06060B),
    canvasRaised = Color(0xFF0E0F18),
    glassFill = Color(0x14FFFFFF),
    glassStroke = Color(0x29FFFFFF),
    glassHighlight = Color(0x66FFFFFF),
    textPrimary = Color(0xFFF4F5FF),
    textSecondary = Color(0xFFA9ADC8),
    textTertiary = Color(0xFF6B6F8C),
    primary = Color(0xFF7C5CFF),
    onPrimary = Color(0xFFFFFFFF),
    accentCyan = Color(0xFF3DE8FF),
    accentMagenta = Color(0xFFFF4FD8),
    accentAmber = Color(0xFFFFC44D),
    success = Color(0xFF3DFFA0),
    danger = Color(0xFFFF5470),
    auroraBase = Color(0xFF05050A),
    auroraA = Color(0xFF2B1A7A),
    auroraB = Color(0xFF0A5C78),
    auroraC = Color(0xFFB0249C),
    isDark = true,
)

val LightTrimioColors = TrimioColors(
    canvas = Color(0xFFF5F6FB),
    canvasRaised = Color(0xFFFFFFFF),
    glassFill = Color(0x99FFFFFF),
    glassStroke = Color(0x1F0B0D1A),
    glassHighlight = Color(0xCCFFFFFF),
    textPrimary = Color(0xFF0B0D1A),
    textSecondary = Color(0xFF4A4E68),
    textTertiary = Color(0xFF8A8EA8),
    primary = Color(0xFF5B3DF5),
    onPrimary = Color(0xFFFFFFFF),
    accentCyan = Color(0xFF00A7C2),
    accentMagenta = Color(0xFFD42CB0),
    accentAmber = Color(0xFFE0A100),
    success = Color(0xFF00B86B),
    danger = Color(0xFFE5304F),
    auroraBase = Color(0xFFF2F0FF),
    auroraA = Color(0xFFC9BEFF),
    auroraB = Color(0xFFA6EEFF),
    auroraC = Color(0xFFFFC2EF),
    isDark = false,
)
