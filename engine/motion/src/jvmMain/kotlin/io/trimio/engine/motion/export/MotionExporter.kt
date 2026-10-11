package io.trimio.engine.motion.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.tools.MediaTools
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
import kotlin.math.roundToInt

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
    fun videoArgs(fps: Int, width: Int = HD_SHORT, height: Int = HD_LONG): List<String> = videoArgs(fps, Level.of(width, height, fps))

    private fun videoArgs(fps: Int, level: Level): List<String> = listOf(
        // 16-bit RGB in, converted once to 8-bit 4:2:0 with the BT.709 matrix and dithering.
        "-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=lanczos+accurate_rnd+full_chroma_int+full_chroma_inp,format=yuv420p",
    ) + MediaTools.h264(
        listOf(
            "-c:v", "libx264", "-preset", preset, "-crf", "$crf", "-maxrate", "${maxrateMbps * level.rate}M", "-bufsize", "${maxrateMbps * level.rate * 2}M",
            "-profile:v", "high", "-level:v", level.label, "-g", "${fps * 2}", "-keyint_min", "$fps", "-sc_threshold", "0",
            "-x264-params", "aq-mode=3:deblock=-1,-1",
        ),
        maxrateMbps * level.rate, gop = fps * 2,
    ) + listOf(
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
    )

    /**
     * The H.264 level a frame size and rate needs (1080p up to 60 fps fits 4.2; 1440p and 4K need
     * 5.x) and how many times the 1080p bitrate ceiling it gets, so larger frames keep their quality.
     */
    enum class Level(val label: String, val rate: Int) {
        L42("4.2", 1), L51("5.1", 2), L52("5.2", 4);

        companion object {
            fun of(width: Int, height: Int, fps: Int): Level {
                val pixels = width.toLong() * height
                return when {
                    pixels <= HD_PIXELS -> L42
                    pixels * fps <= UHD_30 -> L51
                    else -> L52
                }
            }

            private const val HD_PIXELS = 1920L * 1088
            private const val UHD_30 = 3840L * 2160 * 30
        }
    }

    companion object {
        /** Quick review renders: same colour pipeline, faster encode. */
        val Draft = Delivery(crf = 20, preset = "veryfast")

        private const val HD_SHORT = 1080
        private const val HD_LONG = 1920
    }
}

/**
 * Renders a [Composition] to MP4. Frames are composited in half-float precision (no banding
 * through stacked translucent layers), read out as 16-bit RGB and reduced to 8 bits only once,
 * inside the colour conversion.
 *
 * [scale] sets the output size against the composition's own (1080 on the short side): 2 renders
 * a 4K file, 2/3 a 720p one. Everything is drawn at the output size (text, shapes and footage
 * stay sharp), not rendered small and stretched.
 */
class MotionExporter(
    private val composition: Composition,
    /** A fresh media source per worker: footage streams are sequential. */
    private val media: () -> MediaSource = { MediaSource.None },
    private val delivery: Delivery = Delivery(),
    private val scale: Float = 1f,
) {
    /** The file's frame size: the composition's scaled, rounded to even numbers (4:2:0 chroma). */
    val width: Int = even(composition.width * scale)
    val height: Int = even(composition.height * scale)

    /**
     * One worker's renderer: its own surface, text engine and caches. [transparent] draws over
     * nothing (the graphics layer: clear canvas, straight alpha out).
     */
    class Frames(val composition: Composition, media: MediaSource, scale: Float = 1f, private val transparent: Boolean = false) {
        val width: Int = even(composition.width * scale)
        val height: Int = even(composition.height * scale)
        private val info = ImageInfo(width, height, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        private val surface = Surface.makeRaster(info)
        private val scope = CanvasDrawScope()
        private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val renderer = MotionRenderer(composition, measurer, runBlocking { MotionFonts.load() }, media)
        private val half = Bitmap().apply { allocPixels(info) }

        fun draw(t: Float): Image {
            surface.canvas.clear(if (transparent) 0 else 0xFF000000.toInt())
            val sx = width / composition.width.toFloat()
            val sy = height / composition.height.toFloat()
            scope.draw(Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), Size(width.toFloat(), height.toFloat())) {
                scale(sx, sy, pivot = Offset.Zero) { renderer.render(this, t) }
            }
            return surface.makeImageSnapshot()
        }

        /** The frame at [t] as little-endian 16-bit RGBA, straight (not premultiplied) alpha. */
        fun rgba64(t: Float): ByteArray {
            draw(t).use { check(it.readPixels(half)) { "frame read failed" } }
            val bytes = half.readPixels()!!
            val px = IntArray(CHANNELS)
            for (p in bytes.indices step PIXEL) {
                for (c in 0 until CHANNELS) px[c] = HALF_TO_U16[(bytes[p + c * 2].toInt() and 0xFF) or ((bytes[p + c * 2 + 1].toInt() and 0xFF) shl 8)]
                // Opaque frames: premultiplied equals straight. Over nothing, the colour is divided back out.
                if (transparent) unpremultiply(px)
                for (c in 0 until CHANNELS) {
                    bytes[p + c * 2] = px[c].toByte()
                    bytes[p + c * 2 + 1] = (px[c] ushr 8).toByte()
                }
            }
            return bytes
        }

        private fun unpremultiply(px: IntArray) {
            val a = px[ALPHA]
            if (a == 0) {
                px[0] = 0; px[1] = 0; px[2] = 0
                return
            }
            if (a == U16) return
            for (c in 0 until ALPHA) px[c] = ((px[c].toLong() * U16 + a / 2) / a).toInt().coerceAtMost(U16)
        }

        private companion object {
            const val CHANNELS = 4
            const val ALPHA = 3
            const val PIXEL = 8
            const val U16 = 65535

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

    fun export(file: File, audio: File? = null, from: Float = 0f, to: Float = composition.duration, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) =
        render(file, audio, from, to, graphics = false, onProgress)

    /**
     * The motion graphics alone, for another editor's timeline: drawn over a transparent canvas
     * (the footage left out; the creator's own pictures stay, they are graphics), encoded as
     * ProRes 4444 with alpha in a QuickTime file. Premiere, Final Cut and DaVinci stack it over
     * the cut footage as is. [media] should answer only the creator's attachments.
     */
    fun exportGraphics(file: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) =
        render(file, audio = null, from = 0f, to = composition.duration, graphics = true, onProgress)

    private fun render(file: File, audio: File?, from: Float, to: Float, graphics: Boolean, onProgress: (Int, Int) -> Unit) {
        file.absoluteFile.parentFile.mkdirs()
        val fps = composition.fps
        val first = (from * fps).toInt()
        val last = (to * fps).toInt()
        val total = last - first
        val workers = delivery.workers.coerceAtMost(maxOf(1, total / MIN_FRAMES_PER_WORKER))
        val ext = if (graphics) "mov" else "mp4"
        val parts = (0 until workers).map { File(file.absoluteFile.parentFile, ".${file.name}.part$it.$ext") }
        val done = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val threads = parts.mapIndexed { k, part ->
            val a = first + total * k / workers
            val b = first + total * (k + 1) / workers
            thread(name = "motion-export-$k") {
                runCatching { encode(part, a, b, graphics) { onProgress(done.incrementAndGet(), total) } }.onFailure { synchronized(errors) { errors += it } }
            }
        }
        threads.forEach { it.join() }
        errors.firstOrNull()?.let { parts.forEach(File::delete); throw it }
        join(parts, audio, file)
        parts.forEach(File::delete)
    }

    private fun encode(part: File, a: Int, b: Int, graphics: Boolean, onFrame: () -> Unit) {
        val fps = composition.fps
        val frames = Frames(if (graphics) composition.transparent() else composition, media(), scale, transparent = graphics)
        val codec = if (graphics) prores() else delivery.videoArgs(fps, width, height)
        val process = ProcessBuilder(
            listOf(MediaTools.ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba64le", "-s", "${width}x$height", "-r", "$fps", "-i", "-") +
                codec + listOf("-threads", "2", "-an", part.absolutePath),
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

    /** ProRes 4444 with a 16-bit alpha, BT.709 tagged: every intra frame, so parts join losslessly. */
    private fun prores(): List<String> = listOf(
        "-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuva444p10le",
        "-c:v", "prores_ks", "-profile:v", "4444", "-alpha_bits", "16", "-vendor", "apl0",
        "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
    )

    /** Joins the parts without re-encoding (every part starts on a key frame) and adds the sound. */
    private fun join(parts: List<File>, audio: File?, file: File) {
        val list = File(file.absoluteFile.parentFile, ".${file.name}.parts.txt")
        list.writeText(parts.joinToString("\n") { "file '${it.absolutePath}'" })
        val cmd = buildList {
            addAll(listOf(MediaTools.ffmpeg, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.absolutePath))
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

/** [v] rounded to the nearest even number of pixels (at least 2). */
private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
