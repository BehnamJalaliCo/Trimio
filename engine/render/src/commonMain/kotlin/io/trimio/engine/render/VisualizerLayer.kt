package io.trimio.engine.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.trimio.core.model.audio.AudioFeatures
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Audio-reactive visual for audio-only edits, drawn between the background and the type.
 *  - ring: radial bars around the centre, a halo the captions sit inside
 *  - bars: a row of level bars along the bottom third
 * Bars sample the energy track backwards in time, so the shape trails the voice like a wave.
 */
internal class VisualizerLayer(private val kind: String, private val palette: ResolvedPalette) {

    fun DrawScope.draw(timeMs: Long, audio: AudioFeatures?) {
        when (kind) {
            "ring" -> ring(timeMs, audio)
            "bars" -> bars(timeMs, audio)
        }
    }

    private fun level(audio: AudioFeatures?, timeMs: Long): Float =
        audio?.energy01(timeMs.coerceAtLeast(0)) ?: (0.35f + 0.25f * sin(timeMs / 210f) * sin(timeMs / 97f))

    private fun DrawScope.ring(timeMs: Long, audio: AudioFeatures?) {
        val unit = FrameRenderer.unit(size)
        val radius = unit * 0.36f
        val count = 72
        val brush = Brush.sweepGradient(listOf(palette.accent, palette.accent2, palette.accent), center)
        for (i in 0 until count) {
            // Mirror the history around the circle so the ring is symmetric.
            val k = if (i < count / 2) i else count - i
            val l = level(audio, timeMs - k * 25L).coerceIn(0.05f, 1f)
            val a = (i.toFloat() / count) * 2f * PI.toFloat() - PI.toFloat() / 2
            val inner = radius
            val outer = radius + unit * 0.12f * l
            val dir = Offset(cos(a), sin(a))
            drawLine(brush, center + dir * inner, center + dir * outer, unit * 0.009f, cap = StrokeCap.Round, alpha = 0.55f + 0.45f * l)
        }
    }

    private fun DrawScope.bars(timeMs: Long, audio: AudioFeatures?) {
        val unit = FrameRenderer.unit(size)
        val count = 32
        val slot = size.width * 0.8f / count
        val left = size.width * 0.1f
        val base = size.height * 0.84f
        for (i in 0 until count) {
            val l = level(audio, timeMs - (count - i) * 30L).coerceIn(0.05f, 1f)
            val h = unit * 0.22f * l
            val x = left + slot * (i + 0.5f)
            val color = if (i % 2 == 0) palette.accent else palette.accent2
            drawLine(color, Offset(x, base), Offset(x, base - h), slot * 0.6f, cap = StrokeCap.Butt)
        }
    }
}
