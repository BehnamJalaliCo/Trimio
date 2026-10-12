package io.trimio.shared.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import io.trimio.core.model.tools.MediaTools
import io.trimio.feature.stream.PlaybackState
import io.trimio.feature.stream.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The desktop's in-app player for a finished MP4, on the ffmpeg the app already bundles (no
 * other media framework). Two ffmpeg processes per stretch of playback: one decodes the picture
 * to RGBA at the size it is shown, one decodes the sound to 48 kHz stereo PCM for the sound card
 * ([PcmSink]). The sound card is the clock: each picture is shown when the sound reaches its time
 * and dropped when it is late, so picture and sound stay together. Without a sound device (or
 * without a sound track) the wall clock keeps time. Pausing stops both; playing, seeking and a
 * new size start them again where the playhead is (ffmpeg seeks to the exact frame).
 */
class FfmpegVideoPlayer(
    private val file: File,
    /** The sound out; null plays silent on the wall clock. Opened at each play. */
    private val sink: () -> PcmSink? = { JavaSoundSink.open(RATE, CHANNELS) },
) : VideoPlayer {
    /** A picture shown: its time, its decode index since open, and the image (owned by the player). */
    class Frame(val time: Float, val index: Long, val image: Image)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<Frame?>(null)

    /** The picture at the playhead (null until the first is decoded). */
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    /** Pictures shown and pictures skipped because they were late, since open. */
    @Volatile var shown = 0L
        private set

    @Volatile var dropped = 0L
        private set

    /** Bytes of sound handed to the sound out since open. */
    @Volatile var soundBytes = 0L
        private set

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "trimio-player").apply { isDaemon = true } }
    private val ring = ArrayDeque<Image>()
    private var facts: Facts? = null
    @Volatile private var session: Session? = null
    @Volatile private var viewport = 0 to 0
    @Volatile private var target: Float? = null
    @Volatile private var closed = false
    private var decodeSize = 0 to 0
    private var counter = 0L

    init {
        worker.execute(::load)
    }

    private fun load() {
        val f = runCatching { Facts.of(file) }.getOrElse { e ->
            _state.update { it.copy(failed = e.message ?: "unreadable") }
            return
        }
        facts = f
        _state.update { it.copy(duration = f.duration, ready = true) }
        still(0f)
    }

    override fun play() = run {
        val f = facts ?: return@run
        val s = _state.value
        val from = if (s.ended || s.position >= f.duration - END_EPS) 0f else s.position
        start(f, from)
    }

    override fun pause() = run {
        stopSession()
        _state.update { it.copy(playing = false) }
    }

    override fun seek(seconds: Float) {
        target = seconds
        worker.execute {
            val t = target ?: return@execute
            target = null
            val f = facts ?: return@execute
            val at = t.coerceIn(0f, f.duration)
            val playing = session != null
            _state.update { it.copy(position = at, ended = false) }
            if (playing) start(f, at) else still(at)
        }
    }

    /** The size the picture is shown at, in pixels: pictures are decoded to it. */
    fun viewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width to height) == viewport) return
        viewport = width to height
        worker.execute {
            val f = facts ?: return@execute
            val want = sizeFor(f)
            if (!changed(want)) return@execute
            val s = _state.value
            if (session != null) start(f, s.position) else still(s.position)
        }
    }

    @Composable
    override fun Picture(modifier: Modifier) {
        val current by frame.collectAsState()
        Canvas(modifier.background(Color.Black).onSizeChanged { viewport(it.width, it.height) }) {
            val f = current ?: return@Canvas
            val img = f.image
            if (img.isClosed) return@Canvas
            val k = min(size.width / img.width, size.height / img.height)
            val w = img.width * k
            val h = img.height * k
            val x = (size.width - w) / 2
            val y = (size.height - h) / 2
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeXYWH(x, y, w, h), SamplingMode.LINEAR, null, true)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            stopSession()
            _frame.value = null
            synchronized(ring) {
                ring.forEach { it.close() }
                ring.clear()
            }
        }
        worker.shutdown()
    }

    private inline fun run(crossinline block: () -> Unit) {
        if (!closed) worker.execute { block() }
    }

    // ------------------------------------------------------------------ decoding

    private fun sizeFor(f: Facts): Pair<Int, Int> {
        val (vw, vh) = viewport
        val k = if (vw > 0 && vh > 0) min(1f, min(vw / f.width.toFloat(), vh / f.height.toFloat())) else min(1f, DEFAULT_WIDTH / f.width.toFloat())
        fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
        return even(f.width * k) to even(f.height * k)
    }

    /** A new size worth decoding again for: a fifth larger or smaller than the current one. */
    private fun changed(want: Pair<Int, Int>): Boolean =
        decodeSize.first == 0 || abs(want.first - decodeSize.first) > decodeSize.first / RESIZE_SHARE

    /** One picture at [at] while paused (the poster, a scrub). */
    private fun still(at: Float) {
        val f = facts ?: return
        val (w, h) = sizeFor(f).also { decodeSize = it }
        val p = video(at, w, h, f.fps, frames = 1)
        try {
            val bytes = ByteArray(w * h * 4)
            DataInputStream(p.inputStream).readFully(bytes)
            publish(bytes, w, h, at)
        } catch (_: EOFException) {
            // Past the last picture: the one shown stays.
        } finally {
            p.destroy()
        }
    }

    private fun start(f: Facts, from: Float) {
        stopSession()
        val size = sizeFor(f).also { decodeSize = it }
        val s = Session(f, from, size.first, size.second, if (f.audio) sink() else null)
        session = s
        _state.update { it.copy(playing = true, ended = false, position = from) }
        s.start()
    }

    private fun stopSession() {
        session?.stop()
        session = null
    }

    private fun publish(bytes: ByteArray, w: Int, h: Int, time: Float) {
        val image = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), bytes, w * 4)
        val index = synchronized(ring) {
            ring.addLast(image)
            // The screen draws the newest; the ones before it are let go a few pictures later.
            while (ring.size > RING) ring.removeFirst().close()
            shown++
            counter++
        }
        _frame.value = Frame(time, index, image)
    }

    private fun video(from: Float, w: Int, h: Int, fps: Float, frames: Int? = null): Process = ProcessBuilder(
        buildList {
            addAll(listOf(MediaTools.ffmpeg, "-nostdin", "-loglevel", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, from), "-i", file.absolutePath, "-an", "-sn"))
            addAll(listOf("-vf", "fps=${"%.3f".format(java.util.Locale.ROOT, fps)},scale=$w:$h:flags=bilinear"))
            if (frames != null) addAll(listOf("-frames:v", "$frames"))
            addAll(listOf("-f", "rawvideo", "-pix_fmt", "rgba", "-"))
        },
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    private fun sound(from: Float): Process = ProcessBuilder(
        MediaTools.ffmpeg, "-nostdin", "-loglevel", "error", "-ss", "%.3f".format(java.util.Locale.ROOT, from), "-i", file.absolutePath,
        "-vn", "-sn", "-ac", "$CHANNELS", "-ar", "$RATE", "-f", "s16le", "-",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

    /** One stretch of playback from [from]: a picture thread and a sound thread on one clock. */
    private inner class Session(private val f: Facts, private val from: Float, private val w: Int, private val h: Int, private val out: PcmSink?) {
        @Volatile private var running = true
        private val video = video(from, w, h, f.fps)
        private val audio = out?.let { sound(from) }
        private val started = System.nanoTime()
        @Volatile private var soundDone = audio == null

        /**
         * Seconds of the edit heard so far: the sound card's own count, else the wall clock (no
         * sound out, or no sound came within a moment: the picture never waits on silence).
         */
        fun clock(): Float {
            val wall = (System.nanoTime() - started) / NANOS
            val heard = out?.playedFrames ?: return from + wall
            val silent = heard <= 0 && (soundDone || wall > SOUND_GRACE_S)
            return from + if (silent) wall else heard / RATE.toFloat()
        }

        fun start() {
            if (audio != null && out != null) thread(name = "trimio-player-sound", isDaemon = true) { pump(audio.inputStream, out) }
            thread(name = "trimio-player-picture", isDaemon = true) { pictures() }
        }

        private fun pump(input: InputStream, sink: PcmSink) {
            val chunk = ByteArray(CHUNK)
            try {
                while (running) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    sink.write(chunk, n)
                    soundBytes += n
                }
                if (running) sink.drain()
            } catch (_: Exception) {
                // Stopped (the stream or the line was closed under us).
            } finally {
                soundDone = true
            }
        }

        private fun pictures() {
            val input = DataInputStream(video.inputStream.buffered(w * h * 4 * 2))
            val bytes = ByteArray(w * h * 4)
            val step = 1f / f.fps
            var i = 0
            try {
                while (running) {
                    input.readFully(bytes)
                    val t = from + i * step
                    i++
                    // A late picture is skipped; an early one waits for the sound to reach it.
                    if (clock() > t + step * LATE_FRAMES) {
                        dropped++
                    } else if (waitFor(t)) {
                        publish(bytes, w, h, t)
                        _state.update { it.copy(position = t.coerceAtMost(f.duration)) }
                    }
                }
            } catch (_: EOFException) {
                finish()
            } catch (_: Exception) {
                // Stopped while reading.
            }
        }

        private fun waitFor(t: Float): Boolean {
            while (running && clock() < t) Thread.sleep(WAIT_MS)
            return running
        }

        /** The last picture is up: wait for the sound to end, then rest at the end. */
        private fun finish() {
            while (running && !soundDone && clock() < f.duration) Thread.sleep(WAIT_MS * 4)
            if (!running) return
            running = false
            out?.close()
            worker.execute {
                if (session === this) {
                    session = null
                    _state.update { it.copy(playing = false, ended = true, position = f.duration) }
                }
            }
        }

        fun stop() {
            running = false
            runCatching { out?.close() }
            video.destroy()
            audio?.destroy()
        }
    }

    /** What the file is: its picture size (rotation applied), rate, length and whether it has sound. */
    data class Facts(val width: Int, val height: Int, val fps: Float, val duration: Float, val audio: Boolean) {
        companion object {
            fun of(file: File): Facts {
                require(file.isFile) { "no file at $file" }
                val p = ProcessBuilder(
                    MediaTools.ffprobe, "-v", "error", "-show_entries", "stream=codec_type,width,height,avg_frame_rate,r_frame_rate:stream_side_data=rotation:format=duration",
                    "-of", "default=nw=1", file.absolutePath,
                ).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                fun first(key: String) = Regex("^$key=(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
                val w = first("width")?.toIntOrNull() ?: error("no picture in $file")
                val h = first("height")?.toIntOrNull() ?: error("no picture in $file")
                val rotated = first("rotation")?.toIntOrNull()?.let { abs(it) % HALF_TURN == QUARTER_TURN } ?: false
                val fps = (rate(first("avg_frame_rate")) ?: rate(first("r_frame_rate")))?.takeIf { it in 1f..MAX_FPS } ?: DEFAULT_FPS
                val duration = first("duration")?.toFloatOrNull() ?: error("no length for $file")
                val audio = Regex("^codec_type=audio$", RegexOption.MULTILINE).containsMatchIn(text)
                return Facts(if (rotated) h else w, if (rotated) w else h, fps, duration, audio)
            }

            private fun rate(v: String?): Float? {
                val (n, d) = v?.split('/')?.takeIf { it.size == 2 } ?: return v?.toFloatOrNull()
                val den = d.toFloatOrNull()?.takeIf { it > 0f } ?: return null
                return n.toFloatOrNull()?.div(den)
            }
        }
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        private const val CHUNK = 4_096
        private const val RING = 6
        private const val DEFAULT_WIDTH = 540f
        private const val RESIZE_SHARE = 5
        private const val LATE_FRAMES = 1.5f
        private const val WAIT_MS = 2L
        private const val NANOS = 1e9f
        private const val END_EPS = 0.05f
        private const val SOUND_GRACE_S = 1.5f
        private const val DEFAULT_FPS = 30f
        private const val MAX_FPS = 120f
        private const val HALF_TURN = 180
        private const val QUARTER_TURN = 90
    }
}

/** The desktop's players: a finished edit is a file path (or a file: URI). */
fun ffmpegVideoPlayers(): io.trimio.feature.stream.VideoPlayers = io.trimio.feature.stream.VideoPlayers { uri ->
    FfmpegVideoPlayer(if (uri.startsWith("file:")) File(java.net.URI(uri)) else File(uri))
}
