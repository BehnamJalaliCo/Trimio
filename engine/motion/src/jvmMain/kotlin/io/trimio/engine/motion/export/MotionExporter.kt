package io.trimio.engine.motion.export

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
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
import org.jetbrains.skia.impl.BufferUtil
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
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
    val workers: Int = defaultWorkers(),
    /** Draw frames on the graphics card where it can ([GpuCanvas]); false keeps the CPU raster. */
    val gpu: Boolean = true,
) {
    fun videoArgs(fps: Int, width: Int = HD_SHORT, height: Int = HD_LONG): List<String> = videoArgs(fps, Level.of(width, height, fps))

    private fun videoArgs(fps: Int, level: Level): List<String> = listOf(
        // 16-bit RGB in, converted once to 8-bit 4:2:0 with the BT.709 matrix and dithering.
        "-vf", "scale=out_color_matrix=bt709:out_range=tv:flags=lanczos+accurate_rnd+full_chroma_int+full_chroma_inp,format=yuv420p",
    ) + Encoders.args(
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

        /**
         * Workers: about one per two hardware threads, at most three. Each holds two 16-bit frames,
         * a footage stream and its text caches (~150 MB with the footage decoder), so three stay
         * well inside a 16 GB machine; past that the encoder and the decoders share the same cores.
         */
        fun defaultWorkers(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, MAX_WORKERS)

        private const val MAX_WORKERS = 3

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
 *
 * Each worker draws its run of frames (on the GPU where [Delivery.gpu] and [GpuCanvas] allow)
 * while its own thread hands the previous frame to the encoder; [profile] says where the time went.
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

    /** The last export's timings (frames, stages, encoder, CPU or GPU drawing). */
    val profile = RenderProfile()

    /**
     * One worker's renderer: its own surface, text engine and caches. [transparent] draws over
     * nothing (the graphics layer: clear canvas, straight alpha out). [gpu] draws on the graphics
     * card when it can ([onGpu] tells); otherwise on a CPU raster.
     */
    class Frames(
        val composition: Composition,
        media: MediaSource,
        scale: Float = 1f,
        private val transparent: Boolean = false,
        gpu: Boolean = false,
    ) : AutoCloseable {
        val width: Int = even(composition.width * scale)
        val height: Int = even(composition.height * scale)
        private val info = ImageInfo(width, height, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        private val timed = TimedMedia(media)
        private val gpuCanvas: GpuCanvas? = if (gpu) GpuCanvas.open(info) else null
        private val surface = gpuCanvas?.surface ?: Surface.makeRaster(info)
        private val scope = CanvasDrawScope()
        private val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
        val renderer = MotionRenderer(composition, measurer, runBlocking { MotionFonts.load() }, timed)

        /**
         * The frame read out in half floats (the graphics layer un-premultiplied by Skia), then turned
         * into 16-bit integers through a table. (Skia's own 16-bit integer read-out writes white in
         * this Skiko build, so it is not used.)
         */
        private val half = Bitmap().also {
            // (Not inside `apply`: a Bitmap has its own width and height.)
            check(it.allocPixels(ImageInfo(width, height, ColorType.RGBA_F16, if (transparent) ColorAlphaType.UNPREMUL else ColorAlphaType.PREMUL, ColorSpace.sRGB)))
        }
        private val halves: ShortBuffer = BufferUtil.getByteBufferFromPointer(half.peekPixels()!!.addr, width * height * PIXEL)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        private val scratch = ShortArray(width * height * CHANNELS)

        val frameBytes: Int get() = width * height * PIXEL
        val onGpu: Boolean get() = gpuCanvas != null

        /** Nanoseconds spent waiting for footage frames so far. */
        val footageNs: Long get() = timed.ns

        /** Draws the frame at [t] into the surface. */
        fun paint(t: Float) {
            surface.canvas.clear(if (transparent) 0 else 0xFF000000.toInt())
            val sx = width / composition.width.toFloat()
            val sy = height / composition.height.toFloat()
            scope.draw(Density(1f), LayoutDirection.Ltr, surface.canvas.asComposeCanvas(), Size(width.toFloat(), height.toFloat())) {
                scale(sx, sy, pivot = Offset.Zero) { renderer.render(this, t) }
            }
        }

        fun draw(t: Float): Image {
            paint(t)
            return surface.makeImageSnapshot()
        }

        /** The painted frame into [into] ([frameBytes]) as little-endian 16-bit RGBA, straight alpha. */
        fun read(into: ByteArray) {
            check(surface.readPixels(half, 0, 0)) { "frame read failed" }
            halves.rewind()
            halves.get(scratch)
            val table = HALF_TO_U16
            for (i in scratch.indices) scratch[i] = table[scratch[i].toInt() and 0xFFFF]
            ByteBuffer.wrap(into, 0, frameBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(scratch)
        }

        /** The frame at [t] as little-endian 16-bit RGBA, straight (not premultiplied) alpha. */
        fun rgba64(t: Float): ByteArray = ByteArray(frameBytes).also {
            paint(t)
            read(it)
        }

        override fun close() {
            gpuCanvas?.close()
            if (gpuCanvas == null) surface.close()
            half.close()
        }

        private companion object {
            const val CHANNELS = 4

            /** Every half-float bit pattern → 16-bit unsigned, clamped to [0, 1]. */
            val HALF_TO_U16 = ShortArray(1 shl 16) { h ->
                val sign = h ushr 15
                val exp = (h ushr 10) and 0x1F
                val mant = h and 0x3FF
                val v = when {
                    sign == 1 -> 0.0
                    exp == 0 -> mant / 1024.0 / 16384.0
                    exp == 31 -> 1.0
                    else -> (1 + mant / 1024.0) * Math.pow(2.0, exp - 15.0)
                }
                (v.coerceIn(0.0, 1.0) * 65535.0 + 0.5).toInt().toShort()
            }
        }
    }

    /** Counts the time the renderer waits for footage frames (decoding and scaling in ffmpeg). */
    private class TimedMedia(private val inner: MediaSource) : MediaSource {
        var ns = 0L

        override fun frame(source: String, time: Float): ImageBitmap? {
            val a = System.nanoTime()
            return inner.frame(source, time).also { ns += System.nanoTime() - a }
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
        val started = System.nanoTime()
        val fps = composition.fps
        val first = (from * fps).toInt()
        val last = (to * fps).toInt()
        val total = last - first
        val workers = delivery.workers.coerceAtMost(maxOf(1, total / MIN_FRAMES_PER_WORKER))
        val gpu = delivery.gpu && GpuCanvas.wanted
        profile.workers = workers
        profile.encoder = if (graphics) "prores_ks" else Encoders.name
        profile.width = width
        profile.height = height
        val ext = if (graphics) "mov" else "mp4"
        // Work files have plain names: ffmpeg's arguments may not survive a non-UTF-8 locale; only Java renames to the real name.
        val stem = ".trimio-" + Integer.toHexString(file.absolutePath.hashCode()) + "-" + System.nanoTime()
        val parts = (0 until workers).map { File(file.absoluteFile.parentFile, "$stem.part$it.$ext") }
        val done = AtomicInteger()
        val errors = mutableListOf<Throwable>()
        val run = {
            val threads = parts.mapIndexed { k, part ->
                val a = first + total * k / workers
                val b = first + total * (k + 1) / workers
                thread(name = "motion-export-$k") {
                    runCatching { encode(part, a, b, graphics, gpu) { onProgress(done.incrementAndGet(), total) } }.onFailure { synchronized(errors) { errors += it } }
                }
            }
            threads.forEach { it.join() }
        }
        if (gpu) GpuCanvas.guarded(run) else run()
        profile.wallNs = System.nanoTime() - started
        errors.firstOrNull()?.let { parts.forEach(File::delete); throw it }
        val joined = File(file.absoluteFile.parentFile, "$stem.$ext")
        try {
            join(parts, audio, joined)
            Files.move(joined.toPath(), file.absoluteFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            parts.forEach(File::delete)
            joined.delete()
        }
        profile.wallNs = System.nanoTime() - started
        System.err.println("trimio " + profile.summary())
    }

    /**
     * One worker: frames [a, b) drawn and read on this thread, written to ffmpeg by a second one
     * (two frame buffers between them), so drawing never waits for the pipe unless the encoder
     * is the slower side.
     */
    private fun encode(part: File, a: Int, b: Int, graphics: Boolean, gpu: Boolean, onFrame: () -> Unit) {
        val fps = composition.fps
        val scene = if (graphics) composition.transparent() else composition
        val source = media()
        val frames = trusted(Frames(scene, source, scale, transparent = graphics, gpu = gpu), scene, a, graphics)
            ?: Frames(scene, source, scale, transparent = graphics)
        profile.canvas = if (frames.onGpu) "gpu" else "raster"
        val codec = if (graphics) prores() else delivery.videoArgs(fps, width, height)
        val process = ProcessBuilder(
            listOf(MediaTools.ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba64le", "-s", "${width}x$height", "-r", "$fps", "-i", "-") +
                codec + listOf("-threads", "2", "-an", part.absolutePath),
        ).redirectErrorStream(true).start()
        val log = StringBuilder()
        val logger = thread(isDaemon = true, name = "motion-export-log") { runCatching { log.append(process.inputStream.bufferedReader().readText()) } }
        val writer = Writer(process.outputStream, frames.frameBytes)
        try {
            for (f in a until b) {
                val waited = System.nanoTime()
                val buffer = writer.free()
                val drawn = System.nanoTime()
                val cpu = cpuClock.currentThreadCpuTime
                frames.paint(f.toFloat() / fps)
                profile.drawCpuNs.addAndGet(cpuClock.currentThreadCpuTime - cpu)
                val read = System.nanoTime()
                frames.read(buffer)
                profile.encodeWaitNs.addAndGet(drawn - waited)
                profile.drawNs.addAndGet(read - drawn)
                profile.readNs.addAndGet(System.nanoTime() - read)
                profile.frames.incrementAndGet()
                writer.send(buffer)
                onFrame()
            }
        } finally {
            writer.finish()
            profile.footageNs.addAndGet(frames.footageNs)
            frames.close()
            (source as? AutoCloseable)?.close()
        }
        val code = process.waitFor()
        logger.join(LOG_WAIT_MS)
        writer.failure?.let { throw IllegalStateException("ffmpeg stopped taking frames: $log", it) }
        check(code == 0) { "ffmpeg failed: $log" }
    }

    /**
     * [frames] when it draws on the CPU, or when its GPU picture of frame [at] matches the CPU's
     * (a driver that draws wrong, or black, is caught here); null after a mismatch, which also
     * keeps later exports in this process on the CPU.
     */
    private fun trusted(frames: Frames, scene: Composition, at: Int, graphics: Boolean): Frames? {
        if (!frames.onGpu) return frames
        val t = at.toFloat() / composition.fps
        val gpu = runCatching { frames.rgba64(t) }.getOrNull()
        val cpu = media().let { m ->
            try {
                Frames(scene, m, scale, transparent = graphics).use { it.rgba64(t) }
            } finally {
                (m as? AutoCloseable)?.close()
            }
        }
        val difference = gpu?.let { meanDifference(it, cpu) } ?: 1f
        if (difference <= GPU_TOLERANCE) return frames
        System.err.println("trimio render: the GPU frame differs from the CPU one by ${(difference * PERCENT).toInt()}%; drawing on the CPU")
        GpuCanvas.distrust()
        frames.close()
        return null
    }

    /** Hands painted frames to ffmpeg on its own thread; two buffers go back and forth. */
    private class Writer(private val out: OutputStream, size: Int) {
        private val free = ArrayBlockingQueue<ByteArray>(BUFFERS).apply { repeat(BUFFERS) { add(ByteArray(size)) } }
        private val full = ArrayBlockingQueue<ByteArray>(BUFFERS + 1)

        @Volatile var failure: Throwable? = null
            private set

        private val thread = thread(name = "motion-export-write") {
            try {
                while (true) {
                    val buffer = full.take()
                    if (buffer === END) break
                    // After a failure the frames are dropped, not written, so the drawing side never blocks.
                    if (failure == null) runCatching { out.write(buffer) }.onFailure { failure = it }
                    free.put(buffer)
                }
            } finally {
                runCatching { out.close() }
            }
        }

        fun free(): ByteArray {
            failure?.let { throw IllegalStateException("ffmpeg stopped taking frames", it) }
            return free.take()
        }

        fun send(buffer: ByteArray) = full.put(buffer)

        fun finish() {
            full.put(END)
            thread.join()
        }

        private companion object {
            const val BUFFERS = 2
            val END = ByteArray(0)
        }
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
        /** Mean difference (0..1) of two 16-bit frames, over a sample of their pixels. */
        fun meanDifference(a: ByteArray, b: ByteArray): Float {
            var sum = 0L
            var n = 0
            var i = 0
            while (i + 1 < minOf(a.size, b.size)) {
                val x = (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8)
                val y = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                sum += kotlin.math.abs(x - y)
                n++
                i += SAMPLE_STRIDE
            }
            return if (n == 0) 1f else sum / (n * U16)
        }

        /** Antialiasing and filtering differ a little between GPU and CPU; a wrong picture differs a lot. */
        const val GPU_TOLERANCE = 0.03f
        const val SAMPLE_STRIDE = 8 * 7 + 2
        const val U16 = 65535f
        const val PERCENT = 100
        val cpuClock: java.lang.management.ThreadMXBean = java.lang.management.ManagementFactory.getThreadMXBean()
        const val MIN_FRAMES_PER_WORKER = 45
        const val PIXEL = 8
        const val LOG_WAIT_MS = 5_000L
    }
}

/** [v] rounded to the nearest even number of pixels (at least 2). */
private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
