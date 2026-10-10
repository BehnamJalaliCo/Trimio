package io.trimio.engine.motion.export

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.MediaSource
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import kotlin.math.roundToInt

/**
 * Streams a video's frames through ffmpeg for the renderer, scaled once with Lanczos so that it
 * covers [width]×[height] (the renderer then draws it near 1:1 instead of stretching a small
 * frame). Colour is converted with the file's own matrix. Seeks only when the edit jumps.
 */
class FfmpegFootage(
    private val file: File,
    private val fps: Int,
    width: Int,
    height: Int,
    /** The source id this footage answers to (others get null). */
    private val id: String = "main",
) : MediaSource, AutoCloseable {
    private val size: Pair<Int, Int> = coverSize(probe(file), width, height)
    private val w = size.first
    private val h = size.second
    private var process: Process? = null
    private var input: DataInputStream? = null
    private var index = -1
    private var last: ImageBitmap? = null
    private val buffer = ByteArray(w * h * 4)

    override fun frame(source: String, time: Float): ImageBitmap? {
        if (source != id) return null
        val target = (time * fps).roundToInt().coerceAtLeast(0)
        if (target == index && last != null) return last
        if (process == null || target < index || target > index + MAX_SKIP) restart(target)
        val stream = input ?: return last
        while (index < target) {
            try { stream.readFully(buffer) } catch (_: EOFException) { return last }
            index++
        }
        last = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), buffer, w * 4).toComposeImageBitmap()
        return last
    }

    private fun restart(target: Int) {
        close()
        val p = ProcessBuilder(
            "ffmpeg", "-loglevel", "error", "-ss", "%.3f".format(target / fps.toFloat()), "-i", file.absolutePath, "-an",
            "-vf", "fps=$fps,scale=$w:$h:flags=lanczos+accurate_rnd+full_chroma_int",
            "-f", "rawvideo", "-pix_fmt", "rgba", "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        input = DataInputStream(p.inputStream.buffered(1 shl 22))
        index = target - 1
    }

    override fun close() {
        input?.close()
        process?.destroy()
        process = null
        input = null
    }

    companion object {
        private const val MAX_SKIP = 45

        /** Display width and height of the first video stream (rotation applied). */
        fun probe(file: File): Pair<Int, Int> {
            val p = ProcessBuilder(
                "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:stream_side_data=rotation",
                "-of", "default=nw=1", file.absolutePath,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            fun value(key: String) = Regex("^$key=(-?\\d+)", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.toInt()
            val w = value("width") ?: error("no video stream in $file")
            val h = value("height") ?: error("no video stream in $file")
            val rotated = (value("rotation") ?: 0).let { kotlin.math.abs(it) % 180 == 90 }
            return if (rotated) h to w else w to h
        }

        /** Even dimensions with the source aspect that just cover the frame. */
        fun coverSize(source: Pair<Int, Int>, width: Int, height: Int): Pair<Int, Int> {
            val k = maxOf(width / source.first.toFloat(), height / source.second.toFloat())
            fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
            return even(source.first * k) to even(source.second * k)
        }
    }
}
