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
                    maxTokens = 200,
                    temperature = 0f,
                ),
                Pick.serializer(),
            ) { streamed.append(it) }
            val ms = (System.nanoTime() - start) / 1_000_000
            println("llama: $pick in ${ms}ms (${LlamaLanguageModel.systemInfo().take(80)})")
            assertTrue(pick.style in setOf("liquid-glass", "neobrutalism", "kinetic-typography"))
            assertTrue(pick.emphasis.size <= 4 && pick.headline.length <= 40)
            assertEquals(streamed.toString().trim(), streamed.toString().trim().let { it.substring(it.indexOf('{')) }, "nothing before the JSON")
        }
    }
}
