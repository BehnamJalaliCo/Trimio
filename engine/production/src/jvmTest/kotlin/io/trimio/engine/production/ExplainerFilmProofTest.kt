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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A voice alone through the whole production service: the recording is read, the words heard (a
 * stand-in recogniser), the screenwriter writes an explainer film, the stage draws it with the
 * illustration layer's pictures, the narration is mastered with music and transition cues under it,
 * and the MP4 is exported with picture and sound. The editor says it does not change explainer films.
 *
 * With -Pexplainer.wav=<voice> -Pexplainer.transcript=<[{"w","s","e"}]> [-Pexplainer.out=<dir>] the
 * same path runs on a real narration (the proof; skipped otherwise).
 */
class ExplainerFilmProofTest {
    private val dir = Files.createTempDirectory("explainer-film").toFile()

    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    private class Heard(private val words: List<Word>) : SpeechRecognizer {
        override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
            onProgress(1f)
            return Transcript(Language.Persian, words)
        }
    }

    private fun editor(words: List<Word>, out: File, jury: Jury?) = Productions(
        JvmProductionPlatform(File(out, "renders"), recognizer = { Heard(words) }, delivery = Delivery.Draft.copy(workers = 2)),
        jury = jury,
    )

    /** A voice of [seconds] (a soft tone: the words come from the stand-in recogniser). */
    private fun voice(seconds: Float): File {
        val file = File(dir, "voice.wav")
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=170:sample_rate=48000:duration=$seconds", "-af", "volume=0.3", file.path,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && file.length() > 0) { "ffmpeg: $log" }
        return file
    }

    private fun run(media: File, words: List<Word>, prompt: String, out: File, jury: Jury?, name: String): Pair<ProductionState, String> = runBlocking {
        val request = ProductionRequest(media.path, kind = SourceKind.Audio, prompt = prompt, seed = 3, output = File(out, "$name.mp4").path)
        val productions = editor(words, out, jury)
        val states = productions.produce(request).toList()
        val last = states.last()
        assertEquals(ProductionStatus.Done, last.status, "failed: ${last.error}")
        val edit = assertNotNull(last.edit)
        val film = assertNotNull(edit.production.film, "an explainer film was written")
        val report = edit.production.report
        File(out, "$name.report.txt").writeText((report + listOfNotNull(last.technical?.let { "steps: " + it.steps.joinToString { s -> s.name } })).joinToString("\n"))
        assertTrue(report.any { it.startsWith("sound: ") }, "music under the narration")
        assertTrue(film.chapters.isNotEmpty())
        // The editor does not change explainer films (yet); it says so instead of failing.
        assertEquals(Opened.Explainer, productions.reopen(request, EditOverrides()))
        last to assertNotNull(last.output)
    }

    private fun streams(path: String) = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", path).start()
        .inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }.toSet()

    @Test
    fun aVoiceAloneBecomesAnIllustratedNewsFilm() {
        val out = System.getProperty("explainer.out")?.let(::File)?.apply { mkdirs() } ?: dir
        var t = 0.3f
        val words = NEWS.split(' ').map { w ->
            val len = 0.22f + w.length * 0.045f + (if (w.endsWith('.') || w.endsWith('،')) 0.35f else 0f)
            Word(w, TimeRange((t * 1000).toLong(), ((t + len - 0.06f) * 1000).toLong()), language = Language.Persian).also { t += len }
        }
        val (state, mp4) = run(voice(t + 0.6f), words, "یک خبر کوتاه به سبک اخبار مصور", out, Jury(budget = Jury.Budget(rounds = 0, variations = 0)), "news")
        assertEquals(setOf("video", "audio"), streams(mp4))
        val steps = assertNotNull(state.technical).steps.map { it.name }
        listOf("listen", "write", "draw", "sound", "render").forEach { assertTrue(it in steps, "step $it in $steps") }
    }

    @Test
    fun aRealNarration() {
        val wav = System.getProperty("explainer.wav")?.let(::File)?.takeIf { it.isFile } ?: return
        val json = System.getProperty("explainer.transcript")?.let(::File)?.takeIf { it.isFile } ?: return
        val out = File(System.getProperty("explainer.out") ?: "build/explainer-film").apply { mkdirs() }
        val words = Json.decodeFromString(ListSerializer(W.serializer()), json.readText())
            .map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) }
        val (_, mp4) = run(wav, words, System.getProperty("explainer.prompt").orEmpty(), out, null, "reference")
        assertEquals(setOf("video", "audio"), streams(mp4))
    }

    private companion object {
        const val NEWS = "صرافی بزرگ دیروز برداشت همه‌ی کاربران را متوقف کرد. هزاران نفر هنوز پولشان را پس نگرفته‌اند، " +
            "و جلوی دفتر صرافی صف کشیده‌اند. مدیر صرافی گفت تا سه روز دیگر همه چیز درست می‌شود. " +
            "اما کارشناسان می‌گویند طلا و دلار امن‌ترند."
    }
}
