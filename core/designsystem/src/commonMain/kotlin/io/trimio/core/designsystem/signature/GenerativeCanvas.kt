package io.trimio.core.designsystem.signature

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import io.trimio.core.designsystem.theme.Trimio
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stand-in wherever a frame is not there yet (a build forming, a project without a poster,
 * the create screen before a video): a slow generative scene instead of a figure. Three glows
 * drift on the theme's colours (ember, violet, lime on noir; softer on paper), light motes rise,
 * and outlined frames settle into place like an edit being laid out. [generating] adds the build's
 * own motion: a light sweep and layout bars that grow, so the card reads as work in progress.
 * Reduced motion holds one still frame.
 */
@Composable
fun GenerativeCanvas(modifier: Modifier = Modifier, generating: Boolean = false) {
    val c = Sig.colors
    val dark = c.canvas.luminance() < HALF
    val palette = if (dark) Palette.Noir(c.action) else Palette.Paper(c.action, c.ink)
    val still = Trimio.motion.isReduced
    val loop by rememberInfiniteTransition(label = "generative").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (generating) FAST_MS else SLOW_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "loop",
    )
    val t = if (still) STILL_AT else loop
    Box(
        modifier.drawBehind {
            drawRect(palette.ground)
            glows(t, palette)
            motes(t, palette)
            frames(t, palette)
            if (generating) {
                bars(t, palette)
                sweep(t, palette)
            }
        },
    )
}

/** The scene's colours for the theme: a deep ground with strong glows, or warm paper with soft ones. */
private sealed class Palette(
    val ground: Color, val glowA: Color, val glowB: Color, val glowC: Color, val ink: Color, val accent: Color, val mote: Color,
) {
    class Noir(action: Color) :
        Palette(Color(0xFF0D0C0B), action.copy(alpha = 0.55f), VIOLET.copy(alpha = 0.5f), LIME.copy(alpha = 0.28f), Color.White, action, Color.White)

    class Paper(action: Color, ink: Color) :
        Palette(Color(0xFFF1ECE4), action.copy(alpha = 0.3f), VIOLET.copy(alpha = 0.28f), Color(0xFF7FC8B8).copy(alpha = 0.3f), ink, action, VIOLET)
}

/** Three soft glows on slow Lissajous paths. */
private fun DrawScope.glows(t: Float, p: Palette) {
    val w = size.width
    val h = size.height
    val a = 2f * PI.toFloat() * t
    glow(Offset(w * (0.7f + 0.12f * sin(a)), h * (0.28f + 0.08f * cos(a * 2f))), w * 0.62f, p.glowA)
    glow(Offset(w * (0.2f + 0.1f * cos(a)), h * (0.74f + 0.07f * sin(a))), w * 0.6f, p.glowB)
    glow(Offset(w * (0.42f + 0.16f * sin(a * 2f + 1f)), h * (0.5f + 0.12f * cos(a + 2f))), w * 0.42f, p.glowC)
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius), radius = radius, center = center)

/** Light motes rising and twinkling; their places are fixed per index so the scene never jumps. */
private fun DrawScope.motes(t: Float, p: Palette) {
    val w = size.width
    val h = size.height
    for (i in 0 until MOTES) {
        val seed = i * GOLDEN
        val x = w * (seed % 1f)
        val rise = (t + (seed * 3f) % 1f) % 1f
        val y = h * (1.05f - rise * 1.1f)
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (t * 2f + seed)))
        val r = (1.2f + (i % 3)) * density
        drawCircle(p.mote.copy(alpha = 0.32f * twinkle), radius = r, center = Offset(x, y))
    }
}

/** Outlined frames that drift into a stack, like cards of an edit being laid out. */
private fun DrawScope.frames(t: Float, p: Palette) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = 1.2f * density)
    for (i in 0 until FRAMES) {
        val phase = (t + i / FRAMES.toFloat()) % 1f
        val settle = ease(phase.coerceAtMost(0.6f) / 0.6f)
        val fade = if (phase < 0.8f) 1f else 1f - (phase - 0.8f) / 0.2f
        val fw = w * (0.58f - 0.08f * i)
        val fh = fw * 1.25f
        val x = (w - fw) / 2f + w * 0.06f * (i - 1) * (1f - settle)
        val y = h * 0.2f + h * 0.05f * i + h * 0.08f * (1f - settle)
        drawRoundRect(
            (if (i == 0) p.accent else p.ink).copy(alpha = 0.22f * fade * settle),
            topLeft = Offset(x, y), size = Size(fw, fh), cornerRadius = CornerRadius(10f * density), style = stroke,
        )
    }
}

/** Layout bars growing one after another: titles and captions being placed. */
private fun DrawScope.bars(t: Float, p: Palette) {
    val w = size.width
    val h = size.height
    val widths = floatArrayOf(0.56f, 0.38f, 0.46f, 0.3f)
    widths.forEachIndexed { i, target ->
        val start = i * 0.14f
        val grow = ease(((t - start) / 0.3f).coerceIn(0f, 1f))
        val out = if (t > 0.86f) 1f - (t - 0.86f) / 0.14f else 1f
        val bw = w * target * grow
        val y = h * (0.62f + 0.075f * i)
        drawRoundRect(
            (if (i == 0) p.accent else p.ink).copy(alpha = (if (i == 0) 0.9f else 0.35f) * out),
            topLeft = Offset(w * 0.12f, y), size = Size(bw, h * 0.028f), cornerRadius = CornerRadius(h * 0.014f),
        )
    }
}

/** A diagonal band of light crossing the card: the renderer passing over it. */
private fun DrawScope.sweep(t: Float, p: Palette) {
    val w = size.width
    val h = size.height
    val x = -w + t * 3f * w
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, p.ink.copy(alpha = 0.12f), Color.Transparent),
            start = Offset(x, 0f), end = Offset(x + w * 0.6f, h),
        ),
    )
}

private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

private val VIOLET = Color(0xFF8B7BFF)
private val LIME = Color(0xFFD7FF3A)
private const val HALF = 0.5f
private const val SLOW_MS = 14_000
private const val FAST_MS = 6_000
private const val STILL_AT = 0.35f
private const val MOTES = 18
private const val FRAMES = 3
private const val GOLDEN = 0.61803398f
