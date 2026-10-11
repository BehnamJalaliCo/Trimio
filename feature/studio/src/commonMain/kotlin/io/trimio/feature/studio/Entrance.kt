package io.trimio.feature.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.theme.Trimio
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

/**
 * The home entrance (docs/design/motion/1-home-entrance.mp4, source m1-home.html): one clock in
 * milliseconds, and every element keyed to it by its own delay and duration. Plays once per visit
 * of the home screen (coming back to it does not replay it) and not at all with reduced motion.
 */
internal class Entrance(private val clock: State<Float>) {
    /** Linear 0..1 progress of an element that starts at [delay] and runs for [duration]. */
    fun progress(delay: Int, duration: Int): Float = ((clock.value - delay) / duration).coerceIn(0f, 1f)

    companion object {
        const val END = 2_300f

        /** Expo-out: the Signature entrance curve. */
        fun ease(x: Float): Float = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x)

        /** A soft overshoot for small controls. */
        fun spring(x: Float): Float = if (x >= 1f) 1f else 1f - exp(-6f * x) * cos(10f * x)
    }
}

@Composable
internal fun rememberEntrance(): Entrance {
    val played = rememberSaveable { mutableStateOf(false) }
    val reduced = Trimio.motion.isReduced
    val clock = remember { Animatable(if (played.value || reduced) Entrance.END else 0f) }
    LaunchedEffect(Unit) {
        played.value = true
        if (clock.value < Entrance.END) clock.animateTo(Entrance.END, tween(Entrance.END.toInt(), easing = LinearEasing))
    }
    return remember(clock) { Entrance(clock.asState()) }
}

/** Rises 26dp into place while fading in. */
internal fun Modifier.rise(e: Entrance, delay: Int, duration: Int): Modifier = graphicsLayer {
    val p = Entrance.ease(e.progress(delay, duration))
    translationY = (1f - p) * 26.dp.toPx()
    alpha = p
}

internal fun Modifier.fadeIn(e: Entrance, delay: Int, duration: Int): Modifier = graphicsLayer {
    alpha = Entrance.ease(e.progress(delay, duration))
}

/** Grows from 90% with a soft overshoot. */
internal fun Modifier.pop(e: Entrance, delay: Int, duration: Int): Modifier = graphicsLayer {
    val p = e.progress(delay, duration)
    val s = 0.9f + 0.1f * Entrance.spring(p)
    scaleX = s
    scaleY = s
    alpha = (p * 3f).coerceAtMost(1f)
}

/** A display line rising out of its own line box, revealed from the top (the hero's mask reveal). */
internal fun Modifier.maskReveal(e: Entrance, delay: Int, duration: Int): Modifier =
    graphicsLayer {
        clip = e.progress(delay, duration) < 1f
        shape = RectangleShape
    }.graphicsLayer {
        val p = Entrance.ease(e.progress(delay, duration))
        translationY = (1f - p) * 40.dp.toPx()
        clip = p < 1f
        shape = GenericShape { size, _ -> addRect(Rect(0f, 0f, size.width, size.height * p)) }
    }

/** A rule drawn from the reading start. */
internal fun Modifier.drawRule(e: Entrance, delay: Int, duration: Int, rtl: Boolean): Modifier = graphicsLayer {
    scaleX = Entrance.ease(e.progress(delay, duration))
    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
}

/** The composer arriving from below. */
internal fun Modifier.slideUp(e: Entrance, delay: Int, duration: Int): Modifier = graphicsLayer {
    val p = e.progress(delay, duration)
    translationY = (1f - Entrance.ease(p)) * 120.dp.toPx()
    alpha = (p * 2f).coerceAtMost(1f)
}

/**
 * The display style at its board size, or smaller so the longest of [lines] fits in [width]
 * (English words run longer than Persian ones; large font scales too). Never wraps a hero line.
 */
@Composable
internal fun fittedHero(lines: List<String>, style: TextStyle, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(lines, style, width, density) {
        val widest = lines.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
        val room = with(density) { width.toPx() }
        if (widest <= room || widest == 0) style else {
            val scale = room / widest * FIT_MARGIN
            style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
        }
    }
}

private const val FIT_MARGIN = 0.98f
