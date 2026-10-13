package io.trimio.engine.llm

import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Self-speculation with a model's own MTP head gives exactly the text plain decoding gives (greedy,
 * same grammar), and reports how many guesses were kept. Run with a Qwen3.5 MTP GGUF:
 * `./gradlew :engine:llm:jvmTest -Ptrimio.nativeTests -Ptrimio.encoders=<dir with Qwen3.5-0.8B-Q4_K_M.gguf from
 * unsloth/Qwen3.5-0.8B-MTP-GGUF> --tests '*LlamaMtpTest*'`. Skipped without the file.
 */
class LlamaMtpTest {
    private val model = System.getProperty("trimio.encoders")?.let { File(it, "Qwen3.5-0.8B-Q4_K_M.gguf") }?.takeIf { it.isFile }

    private fun run(draft: Int, request: GenerationRequest): Pair<String, Long> {
        System.setProperty("trimio.llama.draft", draft.toString())
        try {
            return LlamaLanguageModel("mtp-test", model!!.absolutePath, ChatFormat.ChatMl, contextSize = 2048).use { m ->
                val t0 = System.nanoTime()
                val out = runBlocking { m.generate(request) }
                println("mtp draft=$draft: ${out.usage?.summary()} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                out.text to (System.nanoTime() - t0) / 1_000_000
            }
        } finally {
            System.clearProperty("trimio.llama.draft")
        }
    }

    @Test
    fun speculationKeepsTheExactGreedyText() {
        if (model == null) return
        val plain = GenerationRequest(
            system = "You are a helpful assistant.",
            messages = listOf(ChatMessage(ChatRole.User, "List the planets of the solar system, one per line, then say done.")),
            maxTokens = 120, temperature = 0f,
        )
        val (a, _) = run(0, plain)
        val (b, _) = run(3, plain)
        assertEquals(a, b)
        // With a grammar too (the director's structured answers).
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"object","properties":{"title":{"type":"string","maxLength":40},"items":{"type":"array","maxItems":5,"items":{"type":"string","maxLength":20}}}}""",
        ) as kotlinx.serialization.json.JsonObject
        val structured = plain.copy(messages = listOf(ChatMessage(ChatRole.User, "Give a title and five fruits as JSON.")), schema = schema)
        assertEquals(run(0, structured).first, run(3, structured).first)
    }
}
