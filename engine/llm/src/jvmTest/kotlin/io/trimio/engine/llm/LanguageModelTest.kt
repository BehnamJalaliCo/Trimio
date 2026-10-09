package io.trimio.engine.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageModelTest {

    @Serializable
    data class Cue(val word: Int, val kind: String)

    @Serializable
    data class Plan(val style: String, val energy: Double, val cues: List<Cue>)

    private val schema = Json.parseToJsonElement(
        """
        {"type":"object","properties":{
          "style":{"type":"string","enum":["liquid-glass","neobrutalism"]},
          "energy":{"type":"number"},
          "cues":{"type":"array","maxItems":3,"items":{"type":"object","properties":{
             "word":{"type":"integer"},"kind":{"type":"string","enum":["counter","icon"]}}}}
        }}
        """,
    ).jsonObject

    @Test
    fun grammarCoversEveryPropertyAndBound() {
        val g = JsonSchemaGrammar.compile(schema)
        assertTrue(g.startsWith("root ::= "))
        assertTrue("\"\\\"liquid-glass\\\"\"" in g, g)
        assertTrue("{0,2}" in g, "maxItems 3 = first item + up to 2 more\n$g")
        assertTrue("integer ::=" in g && "number ::=" in g)
        // Every rule referenced is defined.
        val defined = g.lines().filter { "::=" in it }.map { it.substringBefore(" ::=") }.toSet()
        val referenced = Regex("(?<![\"\\\\\\w-])(root[\\w-]*)").findAll(g).map { it.value }.toSet()
        assertTrue(defined.containsAll(referenced), "undefined: ${referenced - defined}")
    }

    @Test
    fun cloudSchemaDropsBoundsAndLocksObjects() {
        val cloud = JsonSchemas.forCloud(schema)
        val cues = cloud["properties"]!!.jsonObject["cues"]!!.jsonObject
        assertFalse("maxItems" in cues)
        assertEquals("false", cloud["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals("false", cues["items"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content)
        assertEquals(3, (cloud["required"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun structuredJsonToleratesChatterAndBracesInStrings() {
        val plan = StructuredJson.decode(Plan.serializer(), "Sure!\n```json\n{\"style\":\"neo}brutalism\",\"energy\":0.7,\"cues\":[]}\n```")
        assertEquals("neo}brutalism", plan.style)
    }

    @Test
    fun structuredRetriesOnceThenSucceeds(): Unit = runBlocking {
        val replies = ArrayDeque(listOf("{\"style\":\"liquid-glass\",\"energy\":0.5,\"cues\":[{\"wo", "{\"style\":\"liquid-glass\",\"energy\":0.5,\"cues\":[]}"))
        val seen = mutableListOf<GenerationRequest>()
        val model = object : LanguageModel {
            override val id = "fake"
            override val isLocal = true
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
                seen += request
                return Generation(replies.removeFirst(), StopReason.MaxTokens, id)
            }
        }
        val plan = model.generateStructured(GenerationRequest("sys", listOf(ChatMessage(ChatRole.User, "go")), schema), Plan.serializer())
        assertEquals("liquid-glass", plan.style)
        assertEquals(3, seen[1].messages.size, "retry shows the broken reply and asks again")
    }

    @Test
    fun refusalIsNeverParsed(): Unit = runBlocking {
        val model = object : LanguageModel {
            override val id = "fake"
            override val isLocal = false
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit) =
                Generation("{\"style\":\"liquid-glass\",\"energy\":1,\"cues\":[]}", StopReason.Refusal, id)
        }
        val e = assertFailsWith<LanguageModelException> {
            model.generateStructured(GenerationRequest("s", listOf(ChatMessage(ChatRole.User, "x")), schema), Plan.serializer())
        }
        assertEquals(LanguageModelException.Kind.Refused, e.kind)
    }

    @Test
    fun openAiSendsStrictSchemaAndMapsErrors(): Unit = runBlocking {
        var sent: JsonObject? = null
        val ok = HttpClient(
            MockEngine { req ->
                sent = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                assertEquals("Bearer sk-test", req.headers[HttpHeaders.Authorization])
                respond(
                    """{"model":"gpt-5","choices":[{"finish_reason":"stop","message":{"content":"{\"style\":\"neobrutalism\",\"energy\":0.9,\"cues\":[{\"word\":2,\"kind\":\"icon\"}]}"}}]}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val plan = OpenAiLanguageModel(ok, "sk-test", "gpt-5")
            .generateStructured(GenerationRequest("sys", listOf(ChatMessage(ChatRole.User, "hi")), schema), Plan.serializer())
        assertEquals(Cue(2, "icon"), plan.cues.single())
        val format = sent!!["response_format"]!!.jsonObject["json_schema"]!!.jsonObject
        assertEquals("true", format["strict"]!!.jsonPrimitive.content)

        val denied = HttpClient(MockEngine { respond("""{"error":{"message":"bad key"}}""", HttpStatusCode.Unauthorized) })
        val e = assertFailsWith<LanguageModelException> {
            OpenAiLanguageModel(denied, "sk-x", "gpt-5").generate(GenerationRequest("s", listOf(ChatMessage(ChatRole.User, "x"))))
        }
        assertEquals(LanguageModelException.Kind.Auth, e.kind)
    }

    @Test
    fun cloudModelsNeedAKey(): Unit = runBlocking {
        val secrets = MemorySecretStore()
        val models = CloudModels(secrets, HttpClient(MockEngine { respond("") }))
        assertEquals(null, models.firstAvailable())
        models.saveKey(CloudProvider.OpenAI, "  sk-abc  ")
        assertEquals("sk-abc", secrets.read(CloudProvider.OpenAI.secretName))
        assertEquals("gpt-5", models.firstAvailable()?.id)
    }

    @Test
    fun fallbackTemplatesEndWithTheAssistantTurn() {
        val msgs = listOf(ChatMessage(ChatRole.User, "سلام"))
        assertTrue(ChatTemplates.format(ChatFormat.ChatMl, "sys", msgs).endsWith("<|im_start|>assistant\n"))
        val gemma = ChatTemplates.format(ChatFormat.Gemma, "sys", msgs)
        assertTrue(gemma.startsWith("<start_of_turn>user\nsys\n\nسلام") && gemma.endsWith("<start_of_turn>model\n"))
    }
}
