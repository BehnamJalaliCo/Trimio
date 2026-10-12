package io.trimio.engine.llm

import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real llama.cpp generation through JNI with a 230M model. Run with
 * `./gradlew :engine:llm:jvmTest -Ptrimio.nativeTests --tests '*LlamaNativeTest*'`.
 */
class LlamaNativeTest {

    @Serializable
    data class Pick(val style: String, val emphasis: List<Int>, val headline: String)

    private val enabled = System.getProperty("trimio.nativeTests") == "true"

    @Test
    fun grammarForcesSchemaValidJsonEvenFromATinyModel(): Unit = runBlocking {
        if (!enabled) return@runBlocking
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{
                 "style":{"type":"string","enum":["liquid-glass","neobrutalism","kinetic-typography"]},
                 "emphasis":{"type":"array","maxItems":4,"items":{"type":"integer"}},
                 "headline":{"type":"string","maxLength":40}}}""",
        ).jsonObject
        LlamaLanguageModel("lfm-test", System.getProperty("trimio.llama.model"), ChatFormat.Lfm, contextSize = 2048).use { model ->
            val streamed = StringBuilder()
            val start = System.nanoTime()
            val pick = model.generateStructured(
                GenerationRequest(
                    system = "You are a video editor. Answer in JSON.",
                    messages = listOf(ChatMessage(ChatRole.User, "Words: 0:سلام 1:بیت\u200Cکوین 2:پنج 3:درصد 4:رشد. Pick a style, emphasised word indices and a short Persian headline.")),
                    schema = schema,
                    maxTokens = 400,
                    temperature = 0f,
                ),
                Pick.serializer(),
            ) { streamed.append(it) }
            val ms = (System.nanoTime() - start) / 1_000_000
            println("llama: $pick in ${ms}ms (${LlamaLanguageModel.systemInfo().take(80)})")
            assertTrue(pick.style in setOf("liquid-glass", "neobrutalism", "kinetic-typography"))
            assertTrue(pick.emphasis.size <= 4 && pick.headline.length <= 40)
            assertTrue(streamed.trim().startsWith("{"), "nothing before the JSON: $streamed")
        }
    }

    /**
     * The engine's speed features change nothing in the answers: the shared head read once (a
     * snapshot for this hybrid model) and the lazy grammar give the greedy output of a plain run.
     */
    @Test
    fun reusedHeadsAndTheLazyGrammarGiveTheSameAnswers(): Unit = runBlocking {
        if (!enabled) return@runBlocking
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"title":{"type":"string","maxLength":30},"energy":{"type":"number"}}}""").jsonObject
        val system = "You are a video editor. For each line, give a short title and an energy from 0 to 1. Answer in JSON. " + "Be concise. ".repeat(20)
        val lines = listOf("سلام دوستان، امروز درباره‌ی بیت‌کوین حرف می‌زنیم.", "قیمت امسال پنجاه درصد رشد کرد.", "برای لینک، کلمه‌ی کد رو کامنت کن.")
        fun requests() = lines.map { GenerationRequest(system, listOf(ChatMessage(ChatRole.User, "Line: $it")), schema, maxTokens = 60, temperature = 0f) }

        suspend fun run(prefixCache: Boolean, lazyGrammar: Boolean): List<Generation> {
            System.setProperty("trimio.llama.prefixCache", if (prefixCache) "on" else "off")
            System.setProperty("trimio.llama.lazyGrammar", if (lazyGrammar) "on" else "off")
            return try {
                LlamaLanguageModel("lfm-test", System.getProperty("trimio.llama.model"), ChatFormat.Lfm, contextSize = 2048).use { model ->
                    requests().map { model.generate(it) }.also { println("llama runtime: ${model.runtime?.summary()}") }
                }
            } finally {
                System.clearProperty("trimio.llama.prefixCache")
                System.clearProperty("trimio.llama.lazyGrammar")
            }
        }

        val plain = run(prefixCache = false, lazyGrammar = false)
        val fast = run(prefixCache = true, lazyGrammar = true)
        assertEquals(plain.map { it.text }, fast.map { it.text })
        assertEquals(0, plain.sumOf { it.usage!!.reusedTokens })
        // The second call finds the head the first shares with it and keeps it; the third starts there.
        assertTrue(fast.last().usage!!.reusedTokens > 50, "head reused: ${fast.map { it.usage }}")
    }

    @Test
    fun runtimeSaysWhereTheModelRuns(): Unit = runBlocking {
        if (!enabled) return@runBlocking
        LlamaLanguageModel("lfm-test", System.getProperty("trimio.llama.model"), ChatFormat.Lfm, contextSize = 2048).use { model ->
            assertEquals(null, model.runtime, "nothing to say before the model loads")
            model.generate(GenerationRequest("Answer briefly.", listOf(ChatMessage(ChatRole.User, "Say hi.")), maxTokens = 8, temperature = 0f))
            val runtime = model.runtime!!
            println("llama runtime: ${runtime.summary()}")
            assertTrue(runtime.totalLayers > 0 && runtime.threads > 0, runtime.toString())
            assertEquals(1, runtime.usage.calls)
            assertTrue(runtime.usage.promptTokens > 0 && runtime.usage.generatedTokens > 0, runtime.usage.toString())
            assertTrue(runtime.facts.any { it.first == "device" }, "the devices ggml saw are listed")
        }
    }
}
