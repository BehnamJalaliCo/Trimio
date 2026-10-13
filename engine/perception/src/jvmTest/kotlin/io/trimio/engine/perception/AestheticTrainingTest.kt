package io.trimio.engine.perception

import io.trimio.engine.perception.judge.AestheticFeatures
import io.trimio.engine.perception.judge.AestheticTrainer
import io.trimio.engine.perception.judge.RatedFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Trains the look score on the CPU from frames the jury rated (engine/autopilot
 * `AestheticHarvestTest` writes them): `-Ptrimio.aestheticData=<dir>`. Prints the held-out rank
 * correlation and writes `aesthetic-head.json` next to the data.
 */
class AestheticTrainingTest {
    private val dir = System.getProperty("trimio.aesthetic.data")?.let(::File)?.takeIf { File(it, "labels.jsonl").isFile }

    @Test
    fun trainTheHeadOnRatedFrames() {
        val data = dir ?: return
        val rows = File(data, "labels.jsonl").readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        val t = System.nanoTime()
        val rated = rows.mapNotNull { o ->
            val img = ImageIO.read(File(data, o.getValue("file").jsonPrimitive.content)) ?: return@mapNotNull null
            val px = ByteArray(img.width * img.height * 3)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                val i = (y * img.width + x) * 3
                px[i] = (p shr 16).toByte()
                px[i + 1] = (p shr 8).toByte()
                px[i + 2] = p.toByte()
            }
            RatedFrame(AestheticFeatures.of(Frame(img.width, img.height, px)), o.getValue("score").jsonPrimitive.float)
        }
        val scores = rated.map { it.score }
        println("aesthetic: ${rated.size} frames, ${scores.count { it < 10f }} below 10, mean ${scores.average()}")
        println("aesthetic: features in ${(System.nanoTime() - t) / 1_000_000} ms")
        val t2 = System.nanoTime()
        val head = AestheticTrainer().train(rated)
        val form = if (head.hidden.isEmpty()) "linear" else "tanh ${head.hidden.size}"
        println("aesthetic: trained in ${(System.nanoTime() - t2) / 1_000_000} ms on the CPU ($form); held-out Spearman ${head.heldOutSpearman}")
        File(data, "aesthetic-head.json").writeText(head.toJson())
    }
}
