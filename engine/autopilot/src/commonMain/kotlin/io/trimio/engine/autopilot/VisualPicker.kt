package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StructuredJson
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Chooses the picture for each visual query the way an art director would: the vocabulary (and,
 * when it has nothing good, the web) proposes candidates, and the model picks the one that
 * actually shows the idea — "car engine" is an engine, not a fire engine — or none.
 */
class VisualPicker(
    private val library: VisualLibrary,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val model: LanguageModel? = null,
) {
    data class Result(val library: VisualLibrary, val picks: Map<String, String?>)

    /** [queries] with the line each comes from (context for the model). Returns ids ("set:name") per query. */
    suspend fun pick(queries: Map<String, String>): Result {
        val found = mutableListOf<VectorIcon>()
        val candidates = queries.keys.associateWith { q ->
            val local = library.search(q, limit = LOCAL)
            val web = if (weak(q, local)) knowledge.icons(q, WEB).also { found += it } else emptyList()
            (local + web).distinctBy { it.id }.take(LOCAL + WEB)
        }.filterValues { it.isNotEmpty() }
        val chosen = choose(candidates, queries) ?: candidates.mapValues { it.value.firstOrNull()?.id }
        return Result(library.withIcons(found), queries.keys.associateWith { chosen[it] })
    }

    /** The local search found nothing whose name or keywords contain the query's head noun. */
    private fun weak(query: String, local: List<VectorIcon>): Boolean {
        val head = query.lowercase().split(' ').lastOrNull { it.length > 2 } ?: return local.isEmpty()
        return local.none { icon -> head in icon.name || icon.keywords.any { k -> k.lowercase() == head } }
    }

    private suspend fun choose(candidates: Map<String, List<VectorIcon>>, context: Map<String, String>): Map<String, String?>? {
        val m = model ?: return null
        val asked = candidates.filterValues { it.size > 1 }.keys.toList().take(MAX_QUERIES)
        if (asked.isEmpty()) return null
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                JsonObject(
                    asked.withIndex().associate { (i, q) ->
                        "q$i" to buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray((candidates.getValue(q).map { it.id } + "none").map(::JsonPrimitive)))
                        }
                    },
                ),
            )
        }
        val message = buildString {
            append("For each query pick the icon that best pictures it in a short video about this line, or none if none fits.\n\n")
            asked.forEachIndexed { i, q ->
                append("q$i: \"").append(q).append("\" (line: ").append(context[q].orEmpty().take(120)).append(")\n")
                candidates.getValue(q).forEach { c -> append("  - ").append(c.id).append(": ").append(c.keywords.take(8).joinToString(", ")).append('\n') }
            }
        }
        val reply = runCatching {
            m.generate(GenerationRequest(system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, message)), schema = schema, maxTokens = 40 * asked.size + 40, temperature = 0f))
        }.getOrNull() ?: return null
        val o = runCatching { StructuredJson.decode(JsonObject.serializer(), reply.text) }.getOrNull() ?: return null
        val picked = asked.withIndex().associate { (i, q) -> q to o["q$i"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "none" } }
        return candidates.mapValues { (q, list) -> if (q in picked) picked[q] else list.first().id }
    }

    private companion object {
        const val LOCAL = 6
        const val WEB = 3
        const val MAX_QUERIES = 12
        const val SYSTEM = "You are an art director choosing icons for motion graphics. Pick what literally shows the thing; prefer colourful, friendly pictures. Answer in JSON."
    }
}
