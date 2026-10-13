package io.trimio.engine.autopilot.workshop

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs

/**
 * Training data for the learned look score (engine/perception `AestheticHead`): frames of
 * workshop edits with the jury's own verdict on that moment. Each scenario is edited twice — the
 * raw plan (no revisions) and the delivered edit — so the data holds both flawed and fixed frames.
 * A frame's label is 10 minus the cost of the visual judges' findings (placement, type and
 * legibility, choreography) within a second of it. Opt-in, writes outside the repo:
 * `./gradlew :engine:autopilot:jvmTest --tests '*AestheticHarvest*' -Paesthetic.out=<dir>`.
 */
class AestheticHarvestTest {
    private val visual = setOf("layout", "typography", "motion")

    @kotlin.test.Test
    fun harvestRatedFrames() {
        val out = System.getProperty("aesthetic.out")?.let(::File) ?: return
        out.mkdirs()
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        val raw = Autopilot(text, brands, VisualLibrary.Empty, jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)))
        // Every scenario in its own style; the 28-style axis adds `-Pgolden.styles=true` worth of variety when wanted.
        val axis = if (System.getProperty("golden.styles") == "true") StyleAxis.runs().map { (s, style) -> s to style.id } else emptyList()
        val runs = Workshop.scenarios().map { it to null } + axis
        val labels = File(out, "labels.jsonl").bufferedWriter()
        var frames = 0
        for ((scenario, style) in runs) {
            val request = Autopilot.Request(
                scenario.transcript, scenario.voice, StyleAxis.prompt(scenario, style), 1L,
                footage = if (scenario.footage) "main" else null, subject = if (scenario.footage) Workshop.subject(scenario) else null, assets = scenario.assets,
            )
            val editions = listOf("raw" to runBlocking { raw.produce(request) }, "final" to Workshop.run(scenario, 1L, style = style))
            for ((kind, production) in editions) {
                val panel = production.panel ?: continue
                val findings = panel.verdicts.filter { it.judge in visual }.flatMap { v -> v.findings.map { v.judge to it } }
                val c = production.compiled.composition
                val media = if (scenario.footage) Workshop.footage(scenario) else io.trimio.engine.motion.MediaSources(scenario.attached.map { it.source })
                val render = MotionExporter.Frames(c, media)
                for ((t, what) in Workshop.keyTimes(production)) {
                    val near = findings.filter { abs(it.second.at - t) <= WINDOW }
                    val score = (10f - near.sumOf { it.second.severity.cost.toDouble() }.toFloat()).coerceIn(0f, 10f)
                    val name = "${scenario.id}${style?.let { "~$it" } ?: ""}-$kind-${"%05.1f".format(t)}.jpg".replace('/', '_')
                    val img = render.draw(t)
                    val w = WIDTH
                    val h = (c.height * WIDTH / c.width.toFloat()).toInt()
                    val small = Surface.makeRasterN32Premul(w, h)
                    small.canvas.drawImageRect(img, Rect.makeXYWH(0f, 0f, w.toFloat(), h.toFloat()))
                    File(out, name).writeBytes(small.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)!!.bytes)
                    val judges = near.groupBy { it.first }.mapValues { (_, f) -> f.sumOf { it.second.severity.cost.toDouble() } }
                    labels.appendLine("""{"file":"$name","score":$score,"kind":"$kind","what":"$what","t":$t,"costs":{${judges.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}""")
                    frames++
                }
            }
            labels.flush()
            println("aesthetic: ${scenario.id}${style?.let { "~$it" } ?: ""} done, $frames frames")
        }
        labels.close()
    }

    private companion object {
        const val WINDOW = 1.0f
        const val WIDTH = 180
        const val JPEG_QUALITY = 90
    }
}
