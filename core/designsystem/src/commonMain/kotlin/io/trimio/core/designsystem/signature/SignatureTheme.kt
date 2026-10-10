package io.trimio.core.designsystem.signature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language

/**
 * "Signature" — the approved visual language: a cinematic body (warm black, bone ink, film grain),
 * immersive depth (full-bleed footage, warm floating glass) and Swiss order (hairlines, indexes,
 * big numerals). Three accents, each with one meaning that never changes:
 *  - [SigColors.action] (ember): what the user can do — buttons, the brand dot.
 *  - [SigColors.emphasis] (acid lime): what matters inside content — emphasised words, live state.
 *  - [SigColors.director] (iridescent): only where the director (the AI) is present.
 */
@Immutable
data class SigColors(
    val canvas: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val action: Color,
    val onAction: Color,
    val emphasis: Color,
    val onEmphasis: Color,
    /** Emphasis as a soft chip (status, live): container and content readable on this canvas. */
    val emphasisSoft: Color,
    val emphasisSoftContent: Color,
    /** Text and marks over footage and artwork: always light, whatever the theme. */
    val onMedia: Color,
    val director: List<Color>,
    /** The director's text ink: the iridescent ramp, deepened on paper so it stays readable. */
    val directorInk: List<Color>,
    /** Live/progress marks (bars, rings): lime on dark; on paper lime is too faint, so ink. */
    val live: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val glassRim: Color,
    val isDark: Boolean,
) {
    val directorBrush: Brush get() = Brush.linearGradient(director)
    val directorInkBrush: Brush get() = Brush.linearGradient(directorInk)
}

private val Iridescent = listOf(Color(0xFF9BE7FF), Color(0xFFB7A6FF), Color(0xFFFFC6E0), Color(0xFFFFE3B0))

val SigNoir = SigColors(
    canvas = Color(0xFF0B0A09),
    surface = Color(0xFF141210),
    surfaceHigh = Color(0xFF1C1916),
    line = Color(0x17F2EDE4),
    lineStrong = Color(0x29F2EDE4),
    ink = Color(0xFFF2EDE4),
    muted = Color(0xFF8F887D),
    faint = Color(0xFF5B564F),
    action = Color(0xFFFF5B2E),
    onAction = Color(0xFF170904),
    emphasis = Color(0xFFD7FF3A),
    onEmphasis = Color(0xFF0B0A09),
    emphasisSoft = Color(0x1AD7FF3A),
    emphasisSoftContent = Color(0xFFD7FF3A),
    onMedia = Color(0xFFF2EDE4),
    director = Iridescent,
    directorInk = Iridescent,
    live = Color(0xFFD7FF3A),
    glassTop = Color(0x1FFFF6EB),
    glassBottom = Color(0x09FFF6EB),
    glassRim = Color(0x38FFFFFF),
    isDark = true,
)

val SigPaper = SigColors(
    canvas = Color(0xFFEFEDE6),
    surface = Color(0xFFE5E2D9),
    surfaceHigh = Color(0xFFFFFFFF),
    line = Color(0x1F111111),
    lineStrong = Color(0xFF111111),
    ink = Color(0xFF111111),
    muted = Color(0xFF6E6B64),
    faint = Color(0xFFA9A59C),
    action = Color(0xFFFF5B2E),
    onAction = Color(0xFF170904),
    emphasis = Color(0xFFD7FF3A),
    onEmphasis = Color(0xFF111111),
    emphasisSoft = Color(0xFFD7FF3A),
    emphasisSoftContent = Color(0xFF111111),
    onMedia = Color(0xFFF2EDE4),
    director = Iridescent,
    directorInk = listOf(Color(0xFF1580B8), Color(0xFF5B3DE8), Color(0xFFC23577)),
    live = Color(0xFF111111),
    glassTop = Color(0xB3FFFFFF),
    glassBottom = Color(0x80FFFFFF),
    glassRim = Color(0x24111111),
    isDark = false,
)

/** The type scale. Display and expressive families carry the voice; ui carries reading. */
@Immutable
data class SigType(
    /** Screen hero ("امروز چه بسازیم؟"): display family at its heaviest. */
    val hero: TextStyle,
    /** Giant numerals (build percentage). */
    val giant: TextStyle,
    /** Section and card titles with personality. */
    val title: TextStyle,
    /** Long input text (the brief). */
    val lead: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    /** Secondary lines under titles. */
    val meta: TextStyle,
    /** Tracked Latin micro labels (INDEX · 03). */
    val micro: TextStyle,
    /** Index numerals in lists. */
    val index: TextStyle,
    /** Data figures (+5.2%, 80). */
    val figure: TextStyle,
    val button: TextStyle,
)

@Composable
private fun sigType(): SigType {
    val f = Trimio.type.families
    fun s(family: androidx.compose.ui.text.font.FontFamily, size: Int, weight: Int, line: Float = 1.35f, tracking: Float = 0f) = TextStyle(
        fontFamily = family, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = (size * line).sp, letterSpacing = tracking.em,
        // Line boxes as in the design files (CSS model): the glyphs are centred in each line box.
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    return SigType(
        hero = s(f.display, 50, 1000, line = 1.0f, tracking = -0.01f),
        giant = s(f.display, 140, 1000, line = 0.86f, tracking = -0.03f),
        title = s(f.expressive, 22, 900, line = 1.25f),
        lead = s(f.ui, 20, 500, line = 1.7f),
        body = s(f.ui, 14, 500, line = 1.6f),
        bodyStrong = s(f.ui, 14, 800, line = 1.5f),
        meta = s(f.ui, 12, 500, line = 1.5f),
        micro = s(f.accent, 10, 600, line = 1.2f, tracking = 0.16f),
        index = s(f.accent, 26, 300, line = 1f),
        figure = s(f.accent, 28, 900, line = 1.1f),
        button = s(f.ui, 16, 800, line = 1.2f),
    )
}

/** Spacing and shape scale (dp): an 8-pt rhythm with a 22dp screen gutter. */
object SigSpace {
    val gutter: Dp = 22.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 22.dp
    val xxl: Dp = 32.dp
    val radiusSm: Dp = 10.dp
    val radiusMd: Dp = 16.dp
    val radiusLg: Dp = 20.dp
    val radiusXl: Dp = 26.dp
    val controlHeight: Dp = 58.dp
}

private val LocalSigColors = staticCompositionLocalOf { SigNoir }
private val LocalSigType = staticCompositionLocalOf<SigType> { error("SignatureTheme not applied") }

/** Applies the Signature language on top of [TrimioTheme] (language, motion, haptics, fonts). */
@Composable
fun SignatureTheme(
    language: Language = Language.Persian,
    paper: Boolean = false,
    preferences: TrimioPreferences = TrimioPreferences(),
    content: @Composable () -> Unit,
) {
    TrimioTheme(language = language, dark = !paper, preferences = preferences) {
        CompositionLocalProvider(
            LocalSigColors provides if (paper) SigPaper else SigNoir,
            LocalSigType provides sigType(),
            content = content,
        )
    }
}

object Sig {
    val colors: SigColors
        @Composable @ReadOnlyComposable get() = LocalSigColors.current
    val type: SigType
        @Composable @ReadOnlyComposable get() = LocalSigType.current
}
