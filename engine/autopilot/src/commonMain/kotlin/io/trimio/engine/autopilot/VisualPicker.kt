package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StructuredJson
import io.trimio.engine.assets.art.ArtCatalogue
import io.trimio.engine.motion.art.ArtFlavour
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Chooses the picture for each visual query the way an art director would: the vocabulary, the
 * animated pictures ([Knowledge.animations]: an emoji that moves, a line icon that draws itself,
 * in the style's [ArtFlavour]) and, when nothing good is at hand, the web propose candidates, and
 * the model picks the one that actually shows the idea — "car engine" is an engine, not a fire
 * engine — or none. An animated picture leads when it shows the thing as well as the best still.
 *
 * Every pick carries its relevance (0..1, [ArtCatalogue.relevance]): how well the picture shows
 * the query; the jury reads it. All web look-ups of one pick share [WEB_BUDGET_MS].
 */
class VisualPicker(
    private val library: VisualLibrary,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val model: LanguageModel? = null,
    /** The score's look: which kind of animated picture the style speaks. */
    private val look: String? = null,
    /** All web look-ups of one pick together. */
    private val webBudgetMs: Long = WEB_BUDGET_MS,
) {
    /**
     * [found]: the pictures fetched for this piece, stills and animations (already in [library]);
     * [relevance]: how well each query's pick shows it; [credits]: what the picked animations
     * require the video to credit (CC BY).
     */
    data class Result(
        val library: VisualLibrary,
        val picks: Map<String, String?>,
        val found: List<VectorIcon> = emptyList(),
        val relevance: Map<String, Float> = emptyMap(),
        val credits: List<String> = emptyList(),
    )

    /** [queries] with the line each comes from (context for the model). Returns ids ("set:name") per query. */
    suspend fun pick(queries: Map<String, String>): Result {
        val found = mutableListOf<VectorIcon>()
        val scores = HashMap<Pair<String, String>, Float>()
        val credits = HashMap<String, String>()
        val flavour = ArtFlavour.forLook(look)
        val budget = Budget(webBudgetMs.milliseconds)
        val candidates = queries.keys.associateWith { q ->
            val local = library.search(q, limit = LOCAL)
            val moving = budget.run(emptyList()) { knowledge.animations(q, queries[q].orEmpty(), flavour, ART) }
            val strong = moving.any { it.relevance >= ART_LEAD }
            val web = if (weak(q, local) && !strong) budget.run(emptyList()) { knowledge.icons(q, WEB) } else emptyList()
            found += web
            found += moving.map { it.icon }
            moving.forEach { m -> m.credit?.let { credits[m.icon.id] = it } }
            val stills = (local + web).distinctBy { it.id }.take(LOCAL + WEB)
            stills.forEach { scores[q to it.id] = relevanceOf(q, it) }
            moving.forEach { scores[q to it.icon.id] = it.relevance }
            // An animation leads when it shows the thing about as well as the best still does.
            val best = stills.maxOfOrNull { scores.getValue(q to it.id) } ?: 0f
            val (lead, rest) = moving.partition { it.relevance >= ART_LEAD && it.relevance + ANIMATION_EDGE >= best }
            (lead.map { it.icon } + stills + rest.map { it.icon }).distinctBy { it.id }
        }.filterValues { it.isNotEmpty() }
        val chosen = choose(candidates, queries) ?: candidates.mapValues { it.value.firstOrNull()?.id }
        val picks = queries.keys.associateWith { chosen[it] }
        val relevance = picks.mapNotNull { (q, id) -> id?.let { q to (scores[q to it] ?: 0f) } }.toMap()
        return Result(library.withIcons(found), picks, found.distinctBy { it.id }, relevance, picks.values.mapNotNull { credits[it] }.distinct())
    }

    /** How well a still icon shows [query] (its name and keywords), on the animated pictures' scale. */
    private fun relevanceOf(query: String, icon: VectorIcon): Float = ArtCatalogue.relevance(query, icon.name.replace('-', ' '), icon.keywords)

    /** Time left for the web in one pick: each call gets what remains, then none. */
    private class Budget(total: Duration) {
        private val end = TimeSource.Monotonic.markNow() + total

        suspend fun <T> run(empty: T, block: suspend () -> T): T {
            val left = -end.elapsedNow()
            if (!left.isPositive()) return empty
            return kotlinx.coroutines.withTimeoutOrNull(left) { block() } ?: empty
        }
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
                candidates.getValue(q).forEach { c ->
                    append("  - ").append(c.id).append(": ").append(c.keywords.filter { k -> k.none { it in '\u0600'..'\u06FF' } }.take(8).joinToString(", "))
                    if (c.animated) append(" (animated)")
                    append('\n')
                }
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
        const val ART = 2
        const val MAX_QUERIES = 12

        /** All web look-ups of one pick together (each request has its own 6 s, [WebKnowledge.FETCH_MS]). */
        const val WEB_BUDGET_MS = 20_000L

        /** An animation this relevant leads the candidates (and spares the web search for stills). */
        const val ART_LEAD = 0.75f

        /** How much less relevant than the best still an animation may be and still lead: motion is worth a little. */
        const val ANIMATION_EDGE = 0.05f
        const val SYSTEM = "You are an art director choosing icons for motion graphics. Pick what literally shows the thing; " +
            "prefer colourful, friendly pictures, and an animated one when it shows the thing as well. Answer in JSON."
    }
}
