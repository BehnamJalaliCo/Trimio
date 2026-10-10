package io.trimio.engine.motion

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * The same engine across professions (docs/benchmark/02-domains): a cardiologist, a mechanic and a
 * cook. Speech timing is synthetic; the pictures come from the visual vocabulary
 * (-Pvisuals.dir=…, built by tools/visuals/build_visuals.py).
 */
class DomainsTest {
    private val dir = File("../../docs/benchmark/02-domains")

    private fun transcript(text: String): Transcript {
        var t = 300L
        val words = text.split(' ').filter { it.isNotEmpty() }.map { w ->
            val d = 160L + w.length * 40L
            Word(w, TimeRange(t, t + d), language = Language.Persian, emphasis = if (w.any { it.isDigit() } || w.contains('«')) 0.8f else 0.3f).also {
                t += d + if (w.last() in ".!؟?:") 420L else if (w.last() in "،,") 160L else 60L
            }
        }
        return Transcript(Language.Persian, words)
    }

    @Test
    fun professions() {
        val vis = System.getProperty("visuals.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val visuals = VisualLibrary.parse(vis.listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to it.readText() })
        val brands = runBlocking { BrandLibrary.load() }
        val out = File("build/domains")
        val only = System.getProperty("bench.only")
        for (file in dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val name = file.nameWithoutExtension
            if (only != null && only != name) continue
            val doc = Json.parseToJsonElement(file.readText()).jsonObject
            val score = Score.parse(doc.getValue("score").toString())
            val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
            val compiled = Compiler(probe.renderer.text, brands, visuals).compile(Compiler.Input(score, transcript(doc.getValue("text").jsonPrimitive.content)))
            out.resolve("$name.txt").apply { parentFile.mkdirs() }.writeText(
                compiled.beats.joinToString("\n") { "%-12s %-6s %5.2f–%5.2f  %s".format(it.recipe, it.zone, it.at, it.out, it.text) } + "\n" + compiled.notes.joinToString("\n"),
            )
            val kit = MotionTestKit(compiled.composition)
            val d = compiled.composition.duration
            kit.contactSheet((0 until 16).map { 0.4f + it * (d - 0.6f) / 15f }, out.resolve("$name-sheet.png"), columns = 8, scale = 0.2f)
            if (System.getProperty("bench.video.out") != "false" && MotionTestKit.ffmpeg()) {
                val wav = out.resolve("$name.wav")
                MotionTestKit.soundtrack(compiled.sfx, d, wav)
                kit.mp4(out.resolve("$name.mp4"), audio = wav)
            }
        }
    }
}
