package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Calibration on the real benchmark (the owner's anchors: the seed-1 edit is 10/10, the version
 * before it 9/10 for an imprecise cold-open cut). Runs only with a saved real run, never in CI and
 * never copied into the repository:
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*JuryBenchmarkTest*' -Pjury.benchmark=<run dir>
 *
 * <run dir> holds transcript.json and seed-1-understanding.json (a proof run's output, no model is
 * run here), with ../prompt.txt and ../input/source.mp4 next to it. The edit is re-planned from the
 * saved reading with the current code, judged as is (the raw plan) and through the jury's loop.
 */
class JuryBenchmarkTest {
    @kotlinx.serialization.Serializable
    data class W(val w: String, val s: Float, val e: Float)

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun benchmarkPlanPassesTheJury() {
        val dir = System.getProperty("jury.benchmark")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val video = File(dir, "../input/source.mp4").takeIf { it.isFile } ?: return
        val words = json.decodeFromString(ListSerializer(W.serializer()), File(dir, "transcript.json").readText())
            .map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) }
        val understanding = json.decodeFromString(Understanding.serializer(), File(dir, "seed-1-understanding.json").readText())
        val prompt = File(dir, "../prompt.txt").readText()
        val voice = Media.audio(video, 48_000)
        val subject = Media.subject(video)
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        val request = Autopilot.Request(Transcript(Language.Persian, words), voice, prompt, 1L, subject = subject, understanding = understanding)

        // The raw plan, judged without revisions (rounds 0, variations 0).
        val asIs = Jury(budget = Jury.Budget(rounds = 0, variations = 0))
        val raw = runBlocking { Autopilot(text, brands, VisualLibrary.Empty, jury = asIs).produce(request) }
        val saved = File(dir, "seed-1-score.json").takeIf { it.isFile }?.readText()?.let(Score::parse)
        println("benchmark plan ${if (saved == raw.score) "is" else "differs from"} the saved seed-1 score")
        val panel = raw.panel!!
        println("RAW  ${panel.summary()}")
        panel.findings().forEach(::println)
        raw.report.filter { it.startsWith("cold open") || it.startsWith("sound") }.forEach(::println)

        // Through the loop: what the app would deliver.
        val looped = runBlocking { Autopilot(text, brands, VisualLibrary.Empty).produce(request) }
        println("LOOP ${looped.panel!!.summary()}")
        looped.panel!!.findings().forEach(::println)
        looped.report.filter { it.startsWith("jury") }.forEach(::println)
        assertTrue(panel.min >= 9f, "the owner's 10/10 edit passes or nearly passes: ${panel.summary()}")
        assertTrue(looped.panel!!.min >= panel.min, "the loop never makes it worse")
    }
}
