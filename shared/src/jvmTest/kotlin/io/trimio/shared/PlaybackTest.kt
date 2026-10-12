package io.trimio.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.production.Edit
import io.trimio.engine.production.JvmProductionPlatform
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionStatus
import io.trimio.engine.production.Productions
import io.trimio.feature.editor.EditorViewModel
import io.trimio.feature.stream.BuildResult
import io.trimio.feature.stream.JudgeScore
import io.trimio.feature.stream.ResultActions
import io.trimio.feature.stream.ResultScreen
import io.trimio.shared.player.FfmpegVideoPlayer
import io.trimio.shared.player.PcmSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.trimio.engine.motion.export.Delivery as Quality

/**
 * In-app playback on the real engine: an edit is produced from a test clip (the rules direct),
 * then its MP4 plays in the desktop player (picture and sound advance together, pause holds,
 * seeking lands, the end is reached) and in the editor's preview (frames follow the playhead).
 * The result screen with the player is rendered to build/screenshots for review.
 */
class PlaybackTest {
    /** A sound card that plays in real time: writes block once a fifth of a second is queued. */
    private class RealtimeSink(private val rate: Int, private val channels: Int) : PcmSink {
        private var started = 0L
        private var written = 0L
        var energy = 0.0
        var samples = 0L

        override fun write(bytes: ByteArray, n: Int) {
            if (started == 0L) started = System.nanoTime()
            for (i in 0 until n / 2) {
                val v = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768.0
                energy += v * v
                samples++
            }
            written += n / (2L * channels)
            while (written - playedFrames > rate / 5) Thread.sleep(2)
        }

        override val playedFrames: Long
            get() = if (started == 0L) 0L else minOf(written, (System.nanoTime() - started) * rate / 1_000_000_000L)

        override fun drain() {
            while (playedFrames < written) Thread.sleep(2)
        }

        override fun close() = Unit

        val rms: Double get() = if (samples == 0L) 0.0 else sqrt(energy / samples)
    }

    @Test
    fun theProducedEditPlaysWithPictureAndSoundTogether() = runBlocking {
        val file = mp4
        assertTrue(file.isFile)
        var sink: RealtimeSink? = null
        val player = FfmpegVideoPlayer(file) { RealtimeSink(FfmpegVideoPlayer.RATE, FfmpegVideoPlayer.CHANNELS).also { sink = it } }
        withTimeout(TIMEOUT) { player.frame.first { it != null } }
        val duration = player.state.value.duration
        assertTrue(abs(duration - FfmpegVideoPlayer.Facts.of(file).duration) < 0.01f && duration > 4f, "length $duration")
        player.viewport(360, 640)

        val before = player.shown
        val wall = System.nanoTime()
        player.play()
        withTimeout(TIMEOUT) { player.state.first { it.position >= 2.5f } }
        val seconds = (System.nanoTime() - wall) / 1e9f
        val heard = sink!!
        val playedSeconds = heard.playedFrames / FfmpegVideoPlayer.RATE.toFloat()
        val shownFrames = player.shown - before
        val frame = assertNotNull(player.frame.value)
        println(
            "played ${"%.2f".format(seconds)} s: $shownFrames pictures shown, ${player.dropped} dropped, " +
                "sound ${"%.2f".format(playedSeconds)} s (rms ${"%.3f".format(heard.rms)}), picture at ${frame.time} s",
        )
        assertTrue(shownFrames >= 2.5f * 30 * 0.8f, "pictures advance at the edit's rate: $shownFrames")
        assertTrue(player.soundBytes > 2L * FfmpegVideoPlayer.RATE * 4, "sound was decoded: ${player.soundBytes} bytes")
        assertTrue(heard.rms > 0.001, "the sound is not silence: ${heard.rms}")
        assertTrue(abs(frame.time - playedSeconds) < 0.15f, "picture ${frame.time} s and sound $playedSeconds s stay together")
        assertTrue(seconds < 4.5f, "plays in real time: $seconds s for 2.5 s")
        assertEquals(360, frame.image.width, "decoded at the size it is shown")

        player.pause()
        withTimeout(TIMEOUT) { player.state.first { !it.playing } }
        val held = player.state.value.position
        Thread.sleep(300)
        assertEquals(held, player.state.value.position, "pause holds the playhead")

        player.seek(1f)
        withTimeout(TIMEOUT) { player.frame.first { it != null && abs(it.time - 1f) < 0.05f } }
        assertEquals(1f, player.state.value.position)

        player.seek(duration - 0.6f)
        player.play()
        val end = withTimeout(TIMEOUT) { player.state.first { it.ended } }
        assertFalse(end.playing)
        assertEquals(duration, end.position)
        player.close()
    }

    @Test
    fun theResultScreenShowsThePlayer() = runBlocking {
        val player = FfmpegVideoPlayer(mp4) { null }
        withTimeout(TIMEOUT) { player.state.first { it.ready } }
        player.viewport(370, 660)
        player.seek(2.2f)
        withTimeout(TIMEOUT) { player.frame.first { it != null && abs(it.time - 2.2f) < 0.05f } }
        val result = BuildResult(
            title = "ریلز بیت‌کوین", styleName = "نوآر", durationMs = (player.state.value.duration * 1000).toLong(), buildSeconds = 72, onDevice = true,
            scenes = 7, words = 42, effects = 9, lufs = -14f,
            judges = JudgeScore.Panel, findings = emptyList(), poster = null, version = 1,
        )
        shoot("result-player-fa-noir", paper = false) { ResultScreen(result, ResultActions(onExport = {}, onEdit = {}), video = player) }
        shoot("result-player-fa-desktop", paper = true, width = 1280, height = 800, density = 1.5f) { ResultScreen(result, ResultActions(onExport = {}, onEdit = {}), video = player) }
        shoot("result-player-fullscreen-fa", paper = false, width = 1280, height = 800, density = 1.5f) {
            ResultScreen(result, ResultActions(), video = player, startFullscreen = true)
        }
        player.close()
    }

    @Test
    fun theEditorsPreviewPlays() = runBlocking {
        val held = object : ProducedEdits {
            override fun find(id: String): Edit? = edit.takeIf { id == "p1" }
        }
        val vm = withContext(Dispatchers.Main) { EditorViewModel("p1", held, engine) }
        withTimeout(TIMEOUT) { vm.state.first { it is EditorViewModel.State.Open } }
        val frames = mutableListOf<ImageBitmap>()
        val watching = launch(Dispatchers.Main) { vm.frame.collect { it?.let(frames::add) } }
        withTimeout(TIMEOUT) { vm.frame.first { it != null } }
        val first = frames.size
        val wall = System.nanoTime()
        withContext(Dispatchers.Main) { vm.frameWidth(360); vm.play() }
        withTimeout(TIMEOUT) { vm.state.first { (it as? EditorViewModel.State.Open)?.ui?.position?.let { p -> p >= 2f } == true } }
        val seconds = (System.nanoTime() - wall) / 1e9f
        val drawn = frames.size - first
        withContext(Dispatchers.Main) { vm.play() }
        val ui = (vm.state.value as EditorViewModel.State.Open).ui
        watching.cancel()
        println("editor preview: $drawn frames in ${"%.2f".format(seconds)} s (${"%.1f".format(drawn / seconds)} fps), playhead ${ui.position} s")
        assertFalse(ui.playing, "the second press pauses")
        assertTrue(drawn >= 10, "frames follow the playhead: $drawn")
        val a = IntArray(frames.first().width * frames.first().height).also { frames.first().readPixels(it) }
        val b = IntArray(frames.last().width * frames.last().height).also { frames.last().readPixels(it) }
        assertFalse(a.contentEquals(b), "the picture moves")
    }

    private fun shoot(name: String, paper: Boolean, width: Int = 390, height: Int = 844, density: Float = 2f, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }

    private companion object {
        const val TIMEOUT = 300_000L

        /** The edit's MP4, or another file Trimio produced (TRIMIO_PLAYBACK_MP4) to play instead. */
        val mp4: File get() = System.getenv("TRIMIO_PLAYBACK_MP4")?.let(::File)?.takeIf { it.isFile } ?: File(edit.output)
        val dir: File = Files.createTempDirectory("playback-test").toFile()
        val said = listOf("سلام", "دوستان،", "امروز", "بیت‌کوین", "پنج", "درصد", "رشد", "کرد.", "سیگنال", "خرید", "رسید.")

        val heard = object : SpeechRecognizer {
            override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
                onProgress(1f)
                return Transcript(Language.Persian, said.mapIndexed { i, w -> Word(w, TimeRange(250L + i * 480L, 250L + i * 480L + 400L), language = Language.Persian) })
            }
        }

        val engine = Productions(
            JvmProductionPlatform(File(dir, "renders"), recognizer = { heard }, delivery = Quality.Draft.copy(workers = 1)),
            jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)),
        )

        /** A six-second test pattern with a voice-like tone, made into an edit by the real engine. */
        val edit: Edit by lazy {
            val clip = File(dir, "clip.mp4")
            val p = ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=6",
                "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*180*t)*lt(mod(t-0.25\\,0.48)\\,0.4):s=48000:d=6",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip.path,
            ).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0 && clip.length() > 0) { "ffmpeg: $log" }
            runBlocking {
                val last = engine.produce(ProductionRequest(clip.path, prompt = "سیگنال امروز بیت‌کوین، پرانرژی", seed = 7)).toList().last()
                check(last.status == ProductionStatus.Done) { "production failed: ${last.error}" }
                last.edit!!
            }
        }
    }
}
