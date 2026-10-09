package io.trimio.engine.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.trimio.core.designsystem.shader.ShaderSources
import io.trimio.core.designsystem.shader.TrimioShader
import io.trimio.core.designsystem.shader.uniform
import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.style.StyleSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generated backdrops. Required for audio-only input, optional behind footage.
 * Presets: aurora (shader), gradient, mesh, grid, waves (audio visualiser), solid.
 */
internal class BackgroundLayer(
    private val style: StyleSpec,
    private val palette: ResolvedPalette,
    private val options: RenderOptions,
) {
    private val aurora: TrimioShader? by lazy { runCatching { TrimioShader(ShaderSources.aurora) }.getOrNull() }
    private var lowRes: ImageBitmap? = null
    private val lowResScope = CanvasDrawScope()

    fun DrawScope.draw(preset: String, timeMs: Long, energy: Float, audio: AudioFeatures?) {
        val e = if (style.background.audioReactive) energy else 0f
        when (preset) {
            "aurora" -> aurora(timeMs, e)
            "gradient" -> gradient(timeMs)
            "mesh" -> mesh(timeMs, e)
            "grid" -> grid(timeMs, e)
            "waves" -> waves(timeMs, audio)
            else -> drawRect(palette.background(0))
        }
    }

    private fun DrawScope.aurora(timeMs: Long, energy: Float) {
        val shader = aurora ?: return gradient(timeMs)
        if (options.shaderScale < 1f) return lowResolution { auroraFull(shader, timeMs, energy) }
        auroraFull(shader, timeMs, energy)
    }

    /** Renders [block] into a reduced-size bitmap and draws it stretched with smooth filtering. */
    private fun DrawScope.lowResolution(block: DrawScope.() -> Unit) {
        val w = (size.width * options.shaderScale).toInt().coerceAtLeast(1)
        val h = (size.height * options.shaderScale).toInt().coerceAtLeast(1)
        val bitmap = lowRes?.takeIf { it.width == w && it.height == h } ?: ImageBitmap(w, h).also { lowRes = it }
        lowResScope.draw(this, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) { block() }
        drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Medium)
    }

    private fun DrawScope.auroraFull(shader: TrimioShader, timeMs: Long, energy: Float) {
        shader.uniform("iResolution", size.width, size.height)
        shader.uniform("iTime", timeMs / 1000f)
        shader.uniform("iEnergy", energy)
        shader.uniform("cBase", palette.background(0))
        shader.uniform("cA", palette.background(1))
        shader.uniform("cB", palette.background(2))
        shader.uniform("cC", palette.background(3))
        drawRect(shader.brush())
    }

    /** Slowly rotating linear gradient through the palette. */
    private fun DrawScope.gradient(timeMs: Long) {
        val angle = timeMs / 9000f * 2 * PI.toFloat()
        val r = maxOf(size.width, size.height)
        val c = center
        val d = Offset(cos(angle) * r / 2, sin(angle) * r / 2)
        drawRect(Brush.linearGradient(palette.background, start = c - d, end = c + d))
    }

    /** Soft colour blobs drifting on Lissajous paths: organic, calm. */
    private fun DrawScope.mesh(timeMs: Long, energy: Float) {
        drawRect(palette.background(0))
        val t = timeMs / 1000f
        val colors = palette.background.drop(1).ifEmpty { listOf(palette.accent) }
        colors.forEachIndexed { i, color ->
            val phase = i * 1.7f
            val x = size.width * (0.5f + 0.35f * sin(t * 0.21f + phase))
            val y = size.height * (0.5f + 0.35f * cos(t * 0.17f + phase * 1.3f))
            val radius = FrameRenderer.unit(size) * (0.55f + 0.1f * sin(t * 0.3f + phase) + 0.15f * energy)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0f)), Offset(x, y), radius), radius, Offset(x, y))
        }
    }

    /** Flat field with a hard grid that pulses with the voice (brutalism, bento). */
    private fun DrawScope.grid(timeMs: Long, energy: Float) {
        drawRect(palette.background(0))
        val step = FrameRenderer.unit(size) / 9f
        val drift = (timeMs / 40f) % step
        val line = palette.background(1).copy(alpha = 0.35f + 0.4f * energy)
        val stroke = FrameRenderer.unit(size) * 0.0025f
        var x = -drift
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
            x += step
        }
        var y = -drift
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
            y += step
        }
    }

    /** Mirrored bars of recent loudness, scrolling: the audio-only visualiser. */
    private fun DrawScope.waves(timeMs: Long, audio: AudioFeatures?) {
        gradient(timeMs)
        val bars = 48
        val gap = size.width / bars
        val mid = size.height * 0.5f
        val maxH = size.height * 0.22f
        for (i in 0 until bars) {
            val sampleAt = timeMs - (bars - i) * 40L
            val level = audio?.energy01(sampleAt.coerceAtLeast(0)) ?: (0.3f + 0.2f * sin(sampleAt / 180f))
            val h = maxH * level.coerceIn(0.04f, 1f)
            val x = i * gap + gap / 2
            val color = if (i % 2 == 0) palette.accent else palette.accent2
            drawLine(color.copy(alpha = 0.85f), Offset(x, mid - h), Offset(x, mid + h), gap * 0.55f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        drawCircle(Color.White.copy(alpha = 0.05f), FrameRenderer.unit(size) * 0.4f, center, style = Stroke(FrameRenderer.unit(size) * 0.004f))
    }
}
