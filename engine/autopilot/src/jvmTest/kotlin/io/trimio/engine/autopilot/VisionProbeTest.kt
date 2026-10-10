package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

/** One frame to the director's eyes (-Pauto.llm, -Pauto.vision, -Pauto.image). */
class VisionProbeTest {
    private companion object {
        const val QUESTION = "Describe this frame: what text is on screen, where the person's face is, and whether any graphic covers the face."
    }

    @Test
    fun look() {
        val llm = System.getProperty("auto.llm") ?: return
        val eyes = System.getProperty("auto.vision") ?: return
        val img = ImageIO.read(File(System.getProperty("auto.image") ?: return))
        val rgb = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val i = (y * img.width + x) * 3
            rgb[i] = (p shr 16).toByte(); rgb[i + 1] = (p shr 8).toByte(); rgb[i + 2] = p.toByte()
        }
        val budget = (System.getProperty("auto.imagetokens") ?: "96").toInt()
        LlamaLanguageModel("probe", llm, ChatFormat.ChatMl, contextSize = 8192, threads = 4, visionPath = eyes, maxImageTokens = budget).use { m ->
            val t0 = System.nanoTime()
            val out = runBlocking {
                m.generate(
                    GenerationRequest(
                        system = "You review frames of short videos.",
                        messages = listOf(ChatMessage(ChatRole.User, QUESTION, listOf(RgbImage(img.width, img.height, rgb)))),
                        maxTokens = 160, temperature = 0f,
                        schema = kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"type":"object","properties":{"text":{"type":"string","maxLength":60},
                              "face":{"type":"string","enum":["top","middle","bottom","none"]},"readable":{"type":"boolean"}}}""",
                        ).jsonObject,
                    ),
                )
            }
            println("vision ${(System.nanoTime() - t0) / 1e9}s: ${out.text}")
        }
    }
}
