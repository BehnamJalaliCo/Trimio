package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.llm.StructuredJson
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Scores a vision model as the edit's critic on labelled frames (-Pauto.bench=dir with
 * labels.json): where the face is, whether a graphic overlaps it, whether the text reads.
 */
class VisionBenchTest {
    @Test
    fun bench() {
        val dir = System.getProperty("auto.bench")?.let(::File)?.takeIf { it.isDirectory } ?: return
        if (System.getProperty("auto.mode") == "ground") return
        val llm = System.getProperty("auto.llm") ?: return
        val eyes = System.getProperty("auto.vision") ?: return
        val format = if (System.getProperty("auto.format") == "gemma") ChatFormat.Gemma else ChatFormat.ChatMl
        val budget = (System.getProperty("auto.imagetokens") ?: "96").toInt()
        val labels = Json.parseToJsonElement(File(dir, "labels.json").readText()).jsonObject
        val schema = Json.parseToJsonElement(SCHEMA).jsonObject
        var face = 0
        var overlap = 0
        var readable = 0
        var seconds = 0.0
        LlamaLanguageModel(File(llm).name, llm, format, contextSize = 4096, threads = 4, visionPath = eyes, maxImageTokens = budget).use { m ->
            for ((name, label) in labels) {
                val t0 = System.nanoTime()
                val reply = runBlocking {
                    m.generate(
                        GenerationRequest(
                            system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, QUESTION, listOf(load(File(dir, "$name.png"))))),
                            schema = schema, maxTokens = 120, temperature = 0f,
                        ),
                    )
                }
                val dt = (System.nanoTime() - t0) / 1e9
                seconds += dt
                val o = runCatching { StructuredJson.decode(JsonObject.serializer(), reply.text) }.getOrNull()
                fun got(k: String) = o?.get(k)?.jsonPrimitive?.contentOrNull
                fun want(k: String) = label.jsonObject[k]?.jsonPrimitive?.contentOrNull
                if (got("face") == want("face")) face++
                if (got("overlap") == want("overlap")) overlap++
                if (got("readable") == want("readable")) readable++
                println("bench %-20s face %s/%s overlap %s/%s readable %s/%s  %.1fs  text=%s".format(
                    name, got("face"), want("face"), got("overlap"), want("overlap"), got("readable"), want("readable"), dt, got("text"),
                ))
            }
        }
        val n = labels.size
        println("bench RESULT ${File(llm).name}: face $face/$n, overlap $overlap/$n, readable $readable/$n, %.1fs per frame".format(seconds / n))
    }

    /**
     * Grounding: the model draws the face box (0–1000 coordinates), overlap is computed from it
     * and the graphics' known boxes — the way the critic works in the app, where the compiler
     * knows exactly where every graphic is.
     */
    @Test
    fun ground() {
        val dir = System.getProperty("auto.bench")?.let(::File)?.takeIf { it.isDirectory } ?: return
        if (System.getProperty("auto.mode") != "ground") return
        val llm = System.getProperty("auto.llm") ?: return
        val eyes = System.getProperty("auto.vision") ?: return
        val format = if (System.getProperty("auto.format") == "gemma") ChatFormat.Gemma else ChatFormat.ChatMl
        val budget = (System.getProperty("auto.imagetokens") ?: "96").toInt()
        val labels = Json.parseToJsonElement(File(dir, "labels.json").readText()).jsonObject
        val schema = Json.parseToJsonElement(GROUND_SCHEMA).jsonObject
        var faceOk = 0
        var overlapOk = 0
        var seconds = 0.0
        LlamaLanguageModel(File(llm).name, llm, format, contextSize = 4096, threads = 4, visionPath = eyes, maxImageTokens = budget).use { m ->
            for ((name, label) in labels) {
                val t0 = System.nanoTime()
                val reply = runBlocking {
                    m.generate(
                        GenerationRequest(
                            system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, GROUND, listOf(load(File(dir, "$name.png"))))),
                            schema = schema, maxTokens = 80, temperature = 0f,
                        ),
                    )
                }
                val dt = (System.nanoTime() - t0) / 1e9
                seconds += dt
                val o = runCatching { StructuredJson.decode(JsonObject.serializer(), reply.text) }.getOrNull()
                val person = o?.get("person")?.jsonPrimitive?.contentOrNull == "yes"
                val box = o?.get("face")?.let { e -> runCatching { (e as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content.toFloat() / 1000f } }.getOrNull() }
                val wantFace = label.jsonObject["face"]?.jsonPrimitive?.contentOrNull != "none"
                // Truth: the speaker's face box on these frames (288×512): x 100–196, y 208–365.
                val truth = listOf(100f / 288f, 208f / 512f, 196f / 288f, 365f / 512f)
                val iou = if (person && box != null && box.size == 4) iou(box, truth) else 0f
                val faceRight = if (wantFace) iou >= 0.3f else !person
                if (faceRight) faceOk++
                val graphics = label.jsonObject["boxes"]?.jsonArray?.map { r -> r.jsonArray.map { it.jsonPrimitive.content.toFloat() } }.orEmpty()
                val overlaps = person && box != null && box.size == 4 && graphics.any { g -> cover(box, listOf(g[0] / 288f, g[1] / 512f, g[2] / 288f, g[3] / 512f)) > 0.15f }
                val wantOverlap = label.jsonObject["overlap"]?.jsonPrimitive?.contentOrNull == "yes"
                if (overlaps == wantOverlap) overlapOk++
                println("ground %-20s person=%s box=%s iou=%.2f overlap %s/%s  %.1fs".format(name, person, box?.map { (it * 1000).toInt() }, iou, overlaps, wantOverlap, dt))
            }
        }
        val n = labels.size
        println("ground RESULT ${File(llm).name}: face $faceOk/$n, overlap $overlapOk/$n, %.1fs per frame".format(seconds / n))
    }

    private fun iou(a: List<Float>, b: List<Float>): Float {
        val w = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f)
        val h = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Share of the face box [face] covered by [g]. */
    private fun cover(face: List<Float>, g: List<Float>): Float {
        val w = (minOf(face[2], g[2]) - maxOf(face[0], g[0])).coerceAtLeast(0f)
        val h = (minOf(face[3], g[3]) - maxOf(face[1], g[1])).coerceAtLeast(0f)
        val area = (face[2] - face[0]) * (face[3] - face[1])
        return if (area <= 0f) 0f else w * h / area
    }

    private fun load(file: File): RgbImage {
        val img = ImageIO.read(file)
        val rgb = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val i = (y * img.width + x) * 3
            rgb[i] = (p shr 16).toByte(); rgb[i + 1] = (p shr 8).toByte(); rgb[i + 2] = p.toByte()
        }
        return RgbImage(img.width, img.height, rgb)
    }

    private companion object {
        const val SYSTEM = "You check frames of short vertical videos for an editor. Look carefully and answer in JSON."
        const val QUESTION = "face: which third of the frame (top, middle, bottom) holds the person's face, or none if no person is visible. " +
            "overlap: is any text, card or graphic drawn over the person's face (yes or no)? readable: can all on-screen text be read easily on a phone (yes or no)? " +
            "text: the largest on-screen text."
        const val GROUND = "Is there a person in this frame? If so, give the bounding box of the person's face (from the top of the head to the chin) as [x1, y1, x2, y2] " +
            "in coordinates from 0 to 1000 relative to the image width and height."
        const val GROUND_SCHEMA = """{"type":"object","properties":{
            "person":{"type":"string","enum":["yes","no"]},
            "face":{"type":"array","items":{"type":"integer"},"minItems":4,"maxItems":4}}}"""
        const val SCHEMA = """{"type":"object","properties":{
            "face":{"type":"string","enum":["top","middle","bottom","none"]},
            "overlap":{"type":"string","enum":["yes","no"]},
            "readable":{"type":"string","enum":["yes","no"]},
            "text":{"type":"string","maxLength":40}}}"""
    }
}
