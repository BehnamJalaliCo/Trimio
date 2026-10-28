package io.trimio.engine.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.designsystem.shader.ShaderSources
import io.trimio.core.designsystem.shader.TrimioShader
import io.trimio.core.model.style.OverlaySpec

/** Finishing layer: vignette, film grain, letterbox bars. */
internal class OverlayLayer(private val spec: OverlaySpec, @Suppress("UnusedPrivateProperty") private val options: RenderOptions) {
    private val grain: TrimioShader? by lazy { runCatching { TrimioShader(ShaderSources.filmGrain) }.getOrNull() }

    /**
     * Grain rasterised once into a tile and shifted at 12 Hz: a fresh noise field every frame is
     * the worst thing an encoder can be fed. Luma-only grey noise, 1–2 code values on a midtone.
     */
    private val grainTile: ImageBitmap? by lazy {
        val g = grain ?: return@lazy null
        val tile = ImageBitmap(TILE, TILE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(tile), Size(TILE.toFloat(), TILE.toFloat())) {
            g.uniform("iResolution", size.width, size.height)
            g.uniform("iTime", 0f)
            g.uniform("iAmount", 1f)
            drawRect(g.brush())
        }
        tile
    }

    fun DrawScope.draw(timeMs: Long) {
        if (spec.vignette > 0f) {
            drawRect(
                Brush.radialGradient(
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = spec.vignette),
                    center = center,
                    radius = maxOf(size.width, size.height) * 0.75f,
                ),
            )
        }
        if (spec.grain > 0f) {
            val tile = grainTile
            val g = grain
            if (tile != null) {
                val frame = (timeMs * GRAIN_HZ / 1000).toInt()
                val shift = Offset(((frame * 97) % TILE).toFloat(), ((frame * 53) % TILE).toFloat())
                translate(-shift.x, -shift.y) {
                    drawRect(
                        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)),
                        topLeft = shift,
                        size = size,
                        alpha = grainAlpha(spec.grain),
                    )
                }
            } else if (g != null) {
                g.uniform("iResolution", size.width, size.height)
                g.uniform("iTime", grainTime(timeMs))
                g.uniform("iAmount", spec.grain)
                drawRect(g.brush())
            }
        }
        if (spec.letterbox > 0f) {
            val bar = size.height * spec.letterbox
            drawRect(Color.Black, Offset.Zero, Size(size.width, bar))
            drawRect(Color.Black, Offset(0f, size.height - bar), Size(size.width, bar))
        }
    }

    private companion object {
        const val TILE = 256
        const val GRAIN_HZ = 12

        /** 1–2 code values on a midtone: texture, never snow. */
        fun grainAlpha(amount: Float): Float = (amount * GRAIN_ALPHA_GAIN).coerceIn(0f, 1f)

        /** Grain's time quantised to 12 Hz so consecutive frames share one noise aim. */
        fun grainTime(timeMs: Long): Float = (timeMs * GRAIN_HZ / 1000).toInt() / GRAIN_HZ.toFloat()

        private const val GRAIN_ALPHA_GAIN = 0.02f
    }
}
