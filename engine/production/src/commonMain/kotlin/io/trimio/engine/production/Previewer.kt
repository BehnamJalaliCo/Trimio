package io.trimio.engine.production

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import io.trimio.engine.motion.score.Compiler
import kotlin.math.roundToInt

/**
 * Small frames of a compiled edit, drawn by the same [MotionRenderer] the export uses, scaled down
 * on the canvas (no full-size frame is made): the live preview while the edit forms, and the
 * director's eyes ([io.trimio.engine.autopilot.FrameGrabber]).
 */
class Previewer(private val measurer: TextMeasurer, private val fonts: MotionFonts, private val shaders: Boolean = true) {

    fun frame(output: Compiler.Output, media: MediaSource, t: Float, width: Int): ImageBitmap {
        val c = output.composition
        val k = width / c.width.toFloat()
        val height = (c.height * k).roundToInt().coerceAtLeast(1)
        val image = ImageBitmap(width, height)
        val renderer = MotionRenderer(c, measurer, fonts, media).also { it.runtimeShaders = shaders }
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            scale(k, k, pivot = Offset.Zero) { renderer.render(this, t.coerceIn(0f, c.duration)) }
        }
        return image
    }

    /** [frame] as packed RGB for a vision model, exactly [width]×[height]. */
    fun rgb(output: Compiler.Output, media: MediaSource, t: Float, width: Int, height: Int): RgbImage {
        val image = frame(output, media, t, width)
        val h = minOf(height, image.height)
        val argb = IntArray(width * h)
        image.readPixels(argb, 0, 0, width, h)
        val out = ByteArray(width * height * 3)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 3] = (c shr 16).toByte()
            out[i * 3 + 1] = (c shr 8).toByte()
            out[i * 3 + 2] = c.toByte()
        }
        return RgbImage(width, height, out)
    }

    companion object {
        /** Preview width: sharp on a phone card, cheap to draw. */
        const val WIDTH = 270

        /**
         * The time the [k]-th preview shows: a golden-ratio walk over the edit, so successive
         * previews cover it evenly instead of repeating one moment.
         */
        fun time(k: Int, duration: Float): Float = ((0.3f + k * 0.618034f) % 1f) * duration
    }
}
