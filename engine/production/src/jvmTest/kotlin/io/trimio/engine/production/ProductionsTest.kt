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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
