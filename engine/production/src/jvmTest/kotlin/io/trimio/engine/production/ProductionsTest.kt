package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.production.diagnostics.Death
import io.trimio.engine.production.diagnostics.RunOutcome
import io.trimio.engine.production.diagnostics.RunReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production service end to end on a synthetic 3-second clip made by ffmpeg (a test pattern
 * and a tone; no one's footage): prepare → transcribe → direct → render, states in order, an MP4
 * with picture and sound. No model runs: the rules direct and the recogniser is a stand-in that
 * hears a fixed Persian sentence.
 */
class ProductionsTest {
    private val dir = Files.createTempDirectory("production-test").toFile()

    private val words = listOf("سلام", "دوستان،", "امروز", "یک", "نکته‌ی", "مهم", "می‌گم.")
    private val recognizer = object : SpeechRecognizer {
        override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
            onProgress(1f)
            return Transcript(Language.Persian, words.mapIndexed { i, w -> Word(w, TimeRange(200L + i * 380L, 200L + i * 380L + 320L), language = Language.Persian) })
        }
    }

    private fun clip(): File {
        val file = File(dir, "clip.mp4")
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=3",
            "-f", "lavfi", "-i", "sine=frequency=180:sample_rate=48000:duration=3",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.path,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && file.length() > 0) { "ffmpeg: $log" }
        return file
    }

    private fun editor(checkpoints: Checkpoints = Checkpoints.None) = Productions(
        JvmProductionPlatform(File(dir, "renders"), recognizer = { recognizer }, delivery = Delivery.Draft.copy(workers = 1)),
        checkpoints,
        // The smallest panel: one deliberation, no revisions or variations (the jury has its own tests).
        jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)),
    )

    @Test
    fun producesAnMp4WithStatesInOrder() = runBlocking {
        val checkpoints = Checkpoints(Path(File(dir, "checkpoints").path))
        val request = ProductionRequest(clip().path, prompt = "یک ویدیوی کوتاه و پرانرژی", glossary = "RSI یعنی شاخص قدرت نسبی", seed = 7)
        val states = editor(checkpoints).produce(request).toList()

        val last = states.last()
        assertEquals(ProductionStatus.Done, last.status, "failed: ${last.error}")
        // Stages never go back and the bar never moves backwards.
        states.zipWithNext().forEach { (a, b) ->
            assertTrue(b.stage.ordinal >= a.stage.ordinal, "stage ${a.stage} → ${b.stage}")
            assertTrue(b.progress >= a.progress, "progress ${a.progress} → ${b.progress}")
        }
        val seen = states.map { it.stage }.distinct()
        assertEquals(listOf(Stage.Prepare, Stage.Transcribe, Stage.Direct, Stage.Render, Stage.Done), seen)
        assertTrue(states.any { it.previews.isNotEmpty() }, "previews of the forming edit")
        assertNotNull(last.panel, "the jury's panel")
        assertNotNull(last.edit)

        val out = File(assertNotNull(last.output))
        assertTrue(out.length() > 10_000, "an MP4 was written")
        val streams = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", out.path).start()
            .inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("video", "audio"), streams)

        // The words and the reading were checkpointed for a restart.
        val saved = assertNotNull(checkpoints.load(request.key))
        assertNotNull(saved.understanding)
        assertEquals(words.size, saved.transcript.words.size)

        // The technical report rode along in the states and was kept beside the checkpoint.
        val report = assertNotNull(checkpoints.reports.load(request.key))
        assertEquals(report.outcome, assertNotNull(last.technical).outcome)
        assertEquals(RunOutcome.Done, report.outcome)
        val steps = report.steps.map { it.name }
        listOf("prepare", "transcribe", "direct", "listen", "plan", "jury", "render").forEach { assertTrue(it in steps, "step $it in $steps") }
        assertTrue(report.steps.all { it.endMs != null }, "every step closed")
        assertEquals(words.size, report.speech?.words)
        assertTrue((report.render?.fps ?: 0f) > 0f && (report.render?.frames ?: 0) > 0, "render: ${report.render}")
        assertTrue(report.jury.isNotEmpty() && report.jury.first().marks.size == 10, "the jury's sittings: ${report.jury.size}")
        assertTrue(report.device.any { it.name == "os" })
        assertNull(checkpoints.reports.marker(request.key), "a clean end leaves no death marker")
    }

    @Test
    fun aRunThatDiedTwiceInOneStepSaysSo() = runBlocking {
        val checkpoints = Checkpoints(Path(File(dir, "died").path))
        val request = ProductionRequest(File(dir, "never-read.mp4").path, prompt = "test", seed = 5)
        // An earlier process died in whisper once; the one before this died there again (its marker is left).
        checkpoints.reports.save(RunReport(request.key, attempt = 2, deaths = listOf(Death(1, "transcribe", 0))))
        checkpoints.reports.mark(request.key, "entering transcribe@another-process#1")
        val last = editor(checkpoints).produce(request).toList().last()
        assertEquals(ProductionStatus.Failed, last.status)
        assertEquals("transcribe", ProductionState.diedIn(last.error))
        assertEquals(2, checkpoints.reports.load(request.key)?.deaths?.size)
    }

    /**
     * The owner's 1.0.21 run: the system killed the app for memory at «pictures» (52 %). The next attempt runs lean (no
     * eyes, no web animations, no live previews), finishes, and says so in its report.
     */
    @Test
    fun aRunKilledForMemoryInsideTheDirectorFinishesLean() = runBlocking {
        val checkpoints = Checkpoints(Path(File(dir, "killed").path))
        val request = ProductionRequest(clip().path, prompt = "یک ویدیوی کوتاه", seed = 9)
        checkpoints.reports.save(RunReport(request.key, attempt = 1))
        checkpoints.reports.mark(request.key, "in pictures@another-process#1")
        val states = editor(checkpoints).produce(request).toList()
        val last = states.last()
        assertEquals(ProductionStatus.Done, last.status, "failed: ${last.error}")
        val report = assertNotNull(checkpoints.reports.load(request.key))
        assertEquals(listOf("pictures"), report.deaths.map { it.step })
        assertTrue(report.models.any { it.name == "lean run" }, "the report says the run was lean: ${report.models}")
        assertTrue(states.none { it.previews.isNotEmpty() }, "no live previews in a lean run")
    }

    /**
     * The owner's phone ended a run for memory as its render began (71 %) and the next attempt directed it all again: a
     * finished edit is kept before its render, so the attempt after a kill renders it at once, without the director.
     */
    @Test
    fun aRunKilledInItsRenderRendersAgainWithoutDirecting() = runBlocking {
        val checkpoints = Checkpoints(Path(File(dir, "render-killed").path))
        val request = ProductionRequest(clip().path, prompt = "یک ویدیوی کوتاه", seed = 11)
        val first = editor(checkpoints).produce(request).toList().last()
        assertEquals(ProductionStatus.Done, first.status, "failed: ${first.error}")
        // As the checkpoint stands when the system ends the process in the render.
        val kept = assertNotNull(checkpoints.load(request.key))
        assertTrue(!kept.rendering, "a finished render is kept as finished")
        checkpoints.save(request.key, kept.copy(rendering = true))
        val states = editor(checkpoints).produce(request).toList()
        assertEquals(ProductionStatus.Done, states.last().status, "failed: ${states.last().error}")
        assertTrue(states.none { it.stage == Stage.Direct }, "no directing: ${states.map { it.step }.distinct()}")
        assertTrue(assertNotNull(checkpoints.reports.load(request.key)).models.any { it.name == "resumed" }, "the report says it was resumed")
        assertEquals(first.edit?.production?.compiled?.composition?.duration, states.last().edit?.production?.compiled?.composition?.duration)
    }

    @Test
    fun cancellingStopsTheRun() = runBlocking {
        val request = ProductionRequest(clip().path, prompt = "test", seed = 3)
        // Collecting stops at the first directing state: the flow is cancelled and nothing is rendered.
        val state = editor().produce(request).first { it.stage == Stage.Direct }
        assertEquals(ProductionStatus.Running, state.status)
        assertTrue(!File(dir, "renders/${request.key}.mp4").exists())
    }
}
