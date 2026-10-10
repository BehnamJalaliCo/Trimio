package io.trimio.engine.autopilot.workshop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.FrameGrabber
import io.trimio.engine.autopilot.Knowledge
import io.trimio.engine.autopilot.Media
import io.trimio.engine.autopilot.SubjectFinder
import io.trimio.engine.autopilot.Taste
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * The workshop: about twenty synthetic talking videos the director has never seen (Persian and
 * English, fifteen seconds to a minute and a half, a dozen domains, simple and expert briefs,
 * realistic recogniser errors, a voice the analysis can hear, a room and a person to draw over)
 * and the one call that edits them. It proves the rules generalise beyond the two real videos
 * they were tuned on, without touching production code.
 *
 * The contract for other agents (the jury of judges, the golden archive) is small and stable:
 *
 * ```
 * val all: List<Scenario> = Workshop.scenarios()          // fixed order, fixed ids
 * val p: Autopilot.Production = Workshop.run(all[0], 1L)  // the full autopilot, rules or model
 * Workshop.save(all[0], 1L, p, stills = true)             // report, score, stills, contact sheet
 * Workshop.footage(all[0])                                // the MediaSource to render frames with
 * ```
 *
 * [run] uses the rules (no model) unless `-Pauto.llm=<gguf>` is set (as in AutopilotProofTest,
 * with `-Pauto.vision`, `-Pauto.native`); `-Pvisuals.dir` adds the picture packs. Knowledge is
 * offline: no web lookups, so runs are repeatable. Everything else is deterministic per
 * (scenario, seed).
 */
object Workshop {
    /** Where [save] writes: `-Pworkshop.out`, default build/workshop (under the module). */
    val out: File get() = File(System.getProperty("workshop.out") ?: "build/workshop")

    /** Every scenario, in a fixed order. Building them is cheap; voices are synthesised on first use. */
    fun scenarios(): List<Scenario> = Scenarios.all

    /** The scenario with this [id]. */
    fun scenario(id: String): Scenario = scenarios().first { it.id == id }

    /** The synthetic footage of [scenario] (one per call: it caches its last frame). */
    fun footage(scenario: Scenario): io.trimio.engine.motion.MediaSource = SyntheticFootage(variant(scenario))

    /**
     * Edits [scenario] with [seed], the way the app does: the recogniser's words, the voice and
     * the brief go through the whole [Autopilot] (listen, spell, understand, plan, pictures,
     * compile, critic, mastering). The speaker's face is found on the synthetic footage like
     * Studio finds it on a real video.
     */
    fun run(scenario: Scenario, seed: Long, understanding: Understanding? = null): Autopilot.Production = runBlocking {
        val subject = if (scenario.footage) subject(scenario) else null
        val grabber = eyes?.let { FrameGrabber { output, times -> frames(output.composition, scenario).let { f -> times.map { Media.small(f.draw(it), LOOK_W, LOOK_H) } } } }
        Autopilot(text, brands, visuals, model, Knowledge.Offline, Taste(), eyes, grabber).produce(
            Autopilot.Request(scenario.transcript, scenario.voice, scenario.prompt, seed, footage = if (scenario.footage) "main" else null, subject = subject, understanding = understanding),
        )
    }

    /** Where the face is on [scenario]'s footage, as Studio finds it (five small frames). */
    fun subject(scenario: Scenario): Subject? {
        val f = SyntheticFootage(variant(scenario))
        val h = 160
        return SubjectFinder.find((1..5).map { k -> f.rgba(SMALL_W, h, scenario.seconds * k / 6f) }, SMALL_W, h)
    }

    /**
     * Writes one run to `out/<id>/seed-N`: report.txt (scenario, injected errors, what was heard,
     * the autopilot's report, every placed graphic), score.json, understanding.json and, with
     * [stills], a PNG at each key beat plus contact-sheet.png. Returns the folder.
     */
    fun save(scenario: Scenario, seed: Long, production: Autopilot.Production, stills: Boolean, seconds: Double = 0.0): File {
        val dir = File(out, "${scenario.id}/seed-$seed").apply { deleteRecursively(); mkdirs() }
        val c = production.compiled.composition
        val subject = if (scenario.footage) subject(scenario) else null
        File(dir, "report.txt").writeText(
            buildString {
                appendLine("scenario ${scenario.id} | ${scenario.language} | ${scenario.domain} | ${"%.1f".format(scenario.seconds)}s source → ${"%.1f".format(c.duration)}s edit | seed $seed")
                appendLine("footage ${scenario.footage} | subject $subject | ask ${scenario.ask} | captions off ${scenario.captionsOff} | ran in ${"%.1f".format(seconds)}s")
                appendLine("\nprompt:\n${scenario.prompt}")
                appendLine("\nheard:\n${scenario.words.joinToString(" ") { it.text }}")
                appendLine("\ntruth:\n${scenario.script}")
                appendLine("\ninjected errors (${scenario.errors.size}):")
                scenario.errors.forEach { appendLine("  $it") }
                appendLine("\nautopilot report:")
                production.report.forEach { appendLine(it) }
                appendLine("\nplaced (recipe zone at–out [left,top,right,bottom] text):")
                production.compiled.beats.forEach { b ->
                    appendLine("  ${b.recipe.padEnd(13)} ${b.zone.padEnd(7)} ${"%5.1f".format(b.at)}–${"%5.1f".format(b.out)} [${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}] ${if (b.overFootage) "over" else "stage"} ${b.text}")
                }
                appendLine("\ncompiler notes:")
                production.compiled.notes.forEach { appendLine("  $it") }
            },
        )
        File(dir, "score.json").writeText(Score.encode(production.score))
        File(dir, "understanding.json").writeText(JSON.encodeToString(Understanding.serializer(), production.understanding))
        if (stills) stills(scenario, production, dir)
        return dir
    }

    /** The moments worth a look: the hook, each graphic once it has landed, captions alone, the last screen. */
    fun keyTimes(production: Autopilot.Production): List<Pair<Float, String>> {
        val c = production.compiled
        val d = c.composition.duration
        val beats = c.beats.filter { it.recipe != "pop-captions" }.map { b -> minOf(b.at + 1.1f, (b.at + b.out) / 2f) to b.recipe }
        val captionsOnly = c.beats.filter { it.recipe == "pop-captions" }
            .firstOrNull { cap -> c.beats.none { it.recipe != "pop-captions" && it.at < cap.out && cap.at < it.out } }?.let { listOf((it.at + it.out) / 2f to "captions") }.orEmpty()
        val all = (listOf(0.6f to "hook") + beats + captionsOnly + listOf(d - 0.3f to "end")).sortedBy { it.first }
        // Close moments once; at most sixteen.
        val picked = mutableListOf<Pair<Float, String>>()
        for (m in all) if (picked.none { kotlin.math.abs(it.first - m.first) < 0.35f }) picked += m
        return if (picked.size <= MAX_STILLS) picked else picked.filterIndexed { i, _ -> i % ((picked.size + MAX_STILLS - 1) / MAX_STILLS) == 0 } + picked.last()
    }

    private fun stills(scenario: Scenario, production: Autopilot.Production, dir: File) {
        val c = production.compiled.composition
        val frames = frames(c, scenario)
        val times = keyTimes(production)
        val scale = 0.25f
        val w = (c.width * scale).toInt()
        val h = (c.height * scale).toInt()
        val columns = 6
        val sheet = org.jetbrains.skia.Surface.makeRasterN32Premul(w * columns, h * ((times.size + columns - 1) / columns))
        val label = org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle("DejaVu Sans", org.jetbrains.skia.FontStyle.BOLD) ?: org.jetbrains.skia.FontMgr.default.legacyMakeTypeface("", org.jetbrains.skia.FontStyle.BOLD), 22f)
        for ((i, m) in times.withIndex()) {
            val (t, what) = m
            val img = frames.draw(t)
            File(dir, "t${"%05.1f".format(t)}-$what.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            val x = ((i % columns) * w).toFloat()
            val y = ((i / columns) * h).toFloat()
            sheet.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeXYWH(x, y, w.toFloat(), h.toFloat()))
            sheet.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(x, y, w.toFloat(), 30f), org.jetbrains.skia.Paint().apply { color = 0xAA000000.toInt() })
            sheet.canvas.drawString("${"%.1f".format(t)}s $what", x + 6f, y + 23f, label, org.jetbrains.skia.Paint().apply { color = 0xFFFFFFFF.toInt() })
        }
        File(dir, "contact-sheet.png").writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun frames(c: io.trimio.engine.motion.Composition, scenario: Scenario) =
        MotionExporter.Frames(c, if (scenario.footage) footage(scenario) else io.trimio.engine.motion.MediaSource.None)

    private fun variant(s: Scenario) = s.id.fold(7) { h, ch -> h * 31 + ch.code }

    private val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    private val brands by lazy { runBlocking { BrandLibrary.load() } }
    private val visuals by lazy {
        System.getProperty("visuals.dir")?.let(::File)?.takeIf { it.isDirectory }?.let { dir ->
            VisualLibrary.parse(dir.listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to it.readText() })
        } ?: VisualLibrary.Empty
    }

    /** The director model, only when the supervisor passes one (-Pauto.llm, -Pauto.llm.id, -Pauto.vision; as AutopilotProofTest). */
    private val model: LanguageModel? by lazy {
        System.getProperty("auto.llm")?.let {
            LlamaLanguageModel(System.getProperty("auto.llm.id") ?: "qwen3.5-4b-q4km", it, ChatFormat.ChatMl, contextSize = 8192, threads = 4, visionPath = System.getProperty("auto.vision"), maxImageTokens = 96)
        }
    }
    private val eyes: LanguageModel? get() = model?.takeIf { it.canSee }

    private val JSON = Json { prettyPrint = true; encodeDefaults = false }
    private const val MAX_STILLS = 16
    private const val SMALL_W = 90
    private const val LOOK_W = 288
    private const val LOOK_H = 512
}
