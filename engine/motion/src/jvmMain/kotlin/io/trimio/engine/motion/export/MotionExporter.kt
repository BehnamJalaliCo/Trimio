package io.trimio.engine.motion.export

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Delivery settings (research report, P0): 1080p H.264 High 4.2 at near-transparent quality,
 * BT.709 limited range tagged and converted with the matching matrix, closed GOPs, AAC 320k.
 */
data class Delivery(
    val crf: Int = 16,
    val preset: String = "slow",
    val maxrateMbps: Int = 20,
    val audioKbps: Int = 320,
    /** Parallel renderers; each encodes its own run of frames, joined losslessly at the end. */
    // Each worker holds a half-float frame, a footage stream and its own caches (~1 GB native):
    // two keep a phone-class or 16 GB machine safe next to a loaded director model.
    val workers: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 2),
) {
    fun videoArgs(fps: Int): List<String> = listOf(
        // 16-bit RGB in, converted once to 8-bit 4:2:0 with the BT.709 matrix and dithering.
        "-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=lanczos+accurate_rnd+full_chroma_int+full_chroma_inp,format=yuv420p",
        "-c:v", "libx264", "-preset", preset, "-crf", "$crf", "-maxrate", "${maxrateMbps}M", "-bufsize", "${maxrateMbps * 2}M",
        "-profile:v", "high", "-level:v", "4.2", "-g", "${fps * 2}", "-keyint_min", "$fps", "-sc_threshold", "0",
        "-x264-params", "aq-mode=3:deblock=-1,-1",
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
    )

    companion object {
        /** Quick review renders: same colour pipeline, faster encode. */
        val Draft = Delivery(crf = 20, preset = "veryfast")
    }
}

/**
 * Renders a [Composition] to MP4. Frames are composited in half-float precision (no banding
 * through stacked translucent layers), read out as 16-bit RGB and reduced to 8 bits only once,
 * inside the colour conversion.
 */
class MotionExporter(
    private val composition: Composition,
    /** A fresh media source per worker: footage streams are sequential. */
    private val media: () -> MediaSource = { MediaSource.None },
    private val delivery: Delivery = Delivery(),
) {
    /** One worker's renderer: its own surface, text engine and caches. */
    class Frames(val composition: Composition, media: MediaSource) {
        private val info = ImageInfo(composition.width, composition.height, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        private val surface = Surface.makeRaster(info)
        private val scope = CanvasDrawScope()
        private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val renderer = MotionRenderer(composition, measurer, runBlocking { MotionFonts.load() }, media)
        private val half = Bitmap().apply { allocPixels(info) }

        fun draw(t: Float): Image {
            surface.canvas.clear(0xFF000000.toInt())
            scope.draw(Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), Size(composition.width.toFloat(), composition.height.toFloat())) {
                renderer.render(this, t)
            }
            return surface.makeImageSnapshot()
        }

        /** The frame at [t] as little-endian 16-bit RGBA (opaque, so premultiplied equals straight). */
        fun rgba64(t: Float): ByteArray {
            draw(t).use { check(it.readPixels(half)) { "frame read failed" } }
            val bytes = half.readPixels()!!
            for (i in bytes.indices step 2) {
                val v = HALF_TO_U16[(bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)]
                bytes[i] = v.toByte()
                bytes[i + 1] = (v ushr 8).toByte()
            }
            return bytes
        }

        private companion object {
            /** Every half-float bit pattern → 16-bit unsigned, clamped to [0, 1]. */
            val HALF_TO_U16 = IntArray(1 shl 16) { h ->
                val sign = h ushr 15
                val exp = (h ushr 10) and 0x1F
                val mant = h and 0x3FF
                val v = when {
                    sign == 1 -> 0.0
                    exp == 0 -> mant / 1024.0 / 16384.0
                    exp == 31 -> 1.0
                    else -> (1 + mant / 1024.0) * Math.pow(2.0, exp - 15.0)
                }
                (v.coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt()
            }
        }
    }

    fun export(file: File, audio: File? = null, from: Float = 0f, to: Float = composition.duration, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        file.absoluteFile.parentFile.mkdirs()
        val fps = composition.fps
        val first = (from * fps).toInt()
        val last = (to * fps).toInt()
        val total = last - first
        val workers = delivery.workers.coerceAtMost(maxOf(1, total / MIN_FRAMES_PER_WORKER))
        val parts = (0 until workers).map { File(file.absoluteFile.parentFile, ".${file.name}.part$it.mp4") }
        val done = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val threads = parts.mapIndexed { k, part ->
            val a = first + total * k / workers
            val b = first + total * (k + 1) / workers
            thread(name = "motion-export-$k") {
                runCatching { encode(part, a, b) { onProgress(done.incrementAndGet(), total) } }.onFailure { synchronized(errors) { errors += it } }
            }
        }
        threads.forEach { it.join() }
        errors.firstOrNull()?.let { parts.forEach(File::delete); throw it }
        join(parts, audio, file)
        parts.forEach(File::delete)
    }

    private fun encode(part: File, a: Int, b: Int, onFrame: () -> Unit) {
        val fps = composition.fps
        val frames = Frames(composition, media())
        val process = ProcessBuilder(
            listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba64le", "-s", "${composition.width}x${composition.height}", "-r", "$fps", "-i", "-") +
                delivery.videoArgs(fps) + listOf("-threads", "2", "-an", part.absolutePath),
        ).redirectErrorStream(true).start()
        val stdin = process.outputStream.buffered(1 shl 22)
        try {
            for (f in a until b) {
                stdin.write(frames.rgba64(f.toFloat() / fps))
                onFrame()
            }
        } finally {
            stdin.close()
        }
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg failed: $log" }
    }

    /** Joins the parts without re-encoding (every part starts on a key frame) and adds the sound. */
    private fun join(parts: List<File>, audio: File?, file: File) {
        val list = File(file.absoluteFile.parentFile, ".${file.name}.parts.txt")
        list.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
        val cmd = buildList {
            addAll(listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.absolutePath))
            if (audio != null) addAll(listOf("-i", audio.absolutePath))
            addAll(listOf("-map", "0:v"))
            if (audio != null) addAll(listOf("-map", "1:a", "-c:a", "aac", "-b:a", "${delivery.audioKbps}k", "-ar", "48000", "-shortest"))
            addAll(listOf("-c:v", "copy", "-movflags", "+faststart", file.absolutePath))
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        list.delete()
        check(p.waitFor() == 0) { "ffmpeg join failed: $log" }
    }

    private companion object {
        const val MIN_FRAMES_PER_WORKER = 45
    }
}
