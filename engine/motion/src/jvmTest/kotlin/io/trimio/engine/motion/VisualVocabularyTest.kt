package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.visual.VisualLibrary
import java.io.File
import kotlin.test.Test

/** The visual vocabulary across domains: one query per profession, drawn and assembling. */
class VisualVocabularyTest {
    private val queries = listOf(
        "stethoscope", "tooth", "pill", "brain", "heart", "car", "car engine", "wrench", "fuel", "pizza", "cooking", "chef",
        "house", "key", "dumbbell", "running", "graduation cap", "book", "money", "rocket", "airplane", "coffee", "baby", "dog",
    )

    @Test
    fun drawsEveryDomain() {
        val dir = System.getProperty("visuals.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val lib = VisualLibrary.parse(dir.listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to it.readText() })
        val report = StringBuilder("library: ${lib.size} icons\n")
        val cols = 6
        val cell = 300f
        val nodes = queries.mapIndexedNotNull { i, q ->
            val icon = lib.find(q) ?: return@mapIndexedNotNull null.also { report.appendLine("$q → none") }
            report.appendLine("$q → ${icon.set}:${icon.name}  (${lib.search(q).joinToString { "${it.set}:${it.name}" }})")
            val x = (i % cols) * cell + cell / 2
            val y = (i / cols) * cell + cell / 2
            val t0 = i * 0.03f
            VectorNode(
                icon, cell * 0.62f, tint = Color(0xFFF2EDE4).anim, assemble = Anim.tween(0f, 1f, t0, t0 + 0.8f, Easing.Linear),
                transform = Transform(x = x.anim, y = y.anim), name = q,
            )
        }
        val comp = Composition((cols * cell).toInt(), (4 * cell).toInt(), 30, 2f, Color(0xFF0B0A09), Group(nodes), shutterAngle = 0f)
        val kit = MotionTestKit(comp)
        val out = File("build/visuals")
        kit.png(1.9f, out.resolve("vocabulary.png"))
        kit.png(0.45f, out.resolve("vocabulary-assembling.png"))
        out.resolve("vocabulary.txt").writeText(report.toString())
    }
}
