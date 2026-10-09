package io.trimio.engine.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Off-screen Skia surface for desktop tools and tests: renders frames with the same
 * [FrameRenderer] as the app, then encodes PNGs or streams raw frames to ffmpeg.
 */
class DesktopRenderer(val width: Int, val height: Int) {
    private val surface = Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
    private val drawScope = CanvasDrawScope()
    val textMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    fun draw(block: DrawScope.() -> Unit): Image {
        surface.canvas.clear(0xFF000000.toInt())
        drawScope.draw(Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), Size(width.toFloat(), height.toFloat())) { block() }
        return surface.makeImageSnapshot()
    }

    fun png(image: Image): ByteArray = image.encodeToData(EncodedImageFormat.PNG)!!.bytes

    /** BGRA bytes of the last frame, for piping to an encoder. */
    fun bgra(image: Image): ByteArray {
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        image.readPixels(bitmap)
        return bitmap.readPixels()!!
    }
}

/**
 * Encodes raw BGRA frames (plus an optional WAV soundtrack) to H.264/AAC MP4 with the system
 * ffmpeg. Development and verification only; the Android app encodes with MediaCodec.
 */
class FfmpegVideoWriter(output: File, width: Int, height: Int, fps: Int, audio: File? = null) : AutoCloseable {
    private val process: Process = ProcessBuilder(
        buildList {
            addAll(listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${width}x$height", "-r", "$fps", "-i", "-"))
            if (audio != null) addAll(listOf("-i", audio.absolutePath, "-c:a", "aac", "-b:a", "192k", "-shortest"))
            addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", output.absolutePath))
        },
    ).redirectErrorStream(true).start()
    private val stdin = process.outputStream.buffered(1 shl 20)

    fun write(frame: ByteArray) = stdin.write(frame)

    override fun close() {
        stdin.close()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg failed: $log" }
    }

    companion object {
        fun isAvailable(): Boolean = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)
    }
}
