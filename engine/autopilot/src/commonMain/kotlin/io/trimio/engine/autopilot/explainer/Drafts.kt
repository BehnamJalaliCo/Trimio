package io.trimio.engine.autopilot.explainer

import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.FilmGrammar
import io.trimio.engine.motion.explainer.Item
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a model writes: a flat, grammar-friendly form of the film. Beats name their clause and the
 * word they land on (never seconds, so they cannot fall outside the narration); every field is
 * always present (a small model's grammar emits them all), "none"/-1 meaning unset. [toFilm] turns
 * it into the contract.
 */
@Serializable
data class DraftBeat(
    val clause: Int = 0,
    /** Why this picture fits the sentence (English, for the jury). */
    val metaphor: String = "",
    val primitive: String = "none",
    val op: String = "add",
    val element: String = "",
    /** The word of the clause the beat lands on, as written there; "" = the clause's start. */
    val cue: String = "",
    val text: String = "",
    val caption: String = "",
    val items: List<String> = emptyList(),
    /** English icon keywords, one per item (icon-tiles), or empty. */
    val visuals: List<String> = emptyList(),
    /** One number per item (bars), or empty. */
    val values: List<Double> = emptyList(),
    /** Meter/ring 0..1, a stat's number; -1 = none. */
    val value: Double = -1.0,
    val unit: String = "",
    val picks: List<Int> = emptyList(),
    val visual: String = "",
    /** Illustrated news: the small word above the headline, the punchline for the dark box. */
    val kicker: String = "",
    val punch: String = "",
    /** Illustrated news: the hero illustration in English ("worried young man looking at his phone"), "" when none. */
    val hero: String = "",
    /** A name written on an object in the illustration, only when the narration says it; else "". */
    val sign: String = "",
)

@Serializable
data class DraftChapter(
    val title: String = "",
    /** First and last clause numbers (whole film), for a film written in one go. */
    val first: Int = 0,
    val last: Int = 0,
    val beats: List<DraftBeat> = emptyList(),
    /** How the scene arrives: circle, push, peel or cut ("" lets the grammar choose). */
    val transition: String = "",
)

/** One chapter written alone (a small model, chapter by chapter): its heading and its beats. */
@Serializable
data class DraftScene(val title: String = "", val beats: List<DraftBeat> = emptyList(), val transition: String = "")

/** A model's choice of film grammar, and why. */
@Serializable
data class DraftGrammar(val grammar: String = "", val why: String = "")

/** What a director wrote for one chapter or scene. */
data class Written(val plan: Chapters.Plan, val beats: List<DraftBeat>, val transition: String = "")

@Serializable
data class DraftFilm(val chapters: List<DraftChapter> = emptyList())

/** A small model's outline: where each chapter starts and its heading. */
@Serializable
data class DraftOutline(val chapters: List<OutlineEntry> = emptyList()) {
    @Serializable
    data class OutlineEntry(val first: Int = 0, val title: String = "")
}

object Drafts {
    val TRANSITIONS = listOf("circle", "push", "peel", "cut")

    /** The serial names models write ("task-tiles", "add"). */
    val PRIMITIVES: Map<String, Primitive> = Primitive.entries.associateBy { serial(it) }
    val OPS: Map<String, StageOp> = StageOp.entries.associateBy { serial(it) }

    fun serial(p: Primitive): String = Primitive.serializer().descriptor.getElementName(p.ordinal)
    fun serial(op: StageOp): String = StageOp.serializer().descriptor.getElementName(op.ordinal)

    /**
     * The film of [chapters] (each with its clause range and beats) on [n]'s clock: chapter times
     * from their first clauses, each beat on its cue word (or spread over its clause), element ids
     * kept apart per chapter. Untidy references and lengths are left to [FilmCheck.repair].
     */
    fun toFilm(n: Narration, chapters: List<Written>, seed: Long, grammar: FilmGrammar = FilmGrammar.DiagramChapters): ExplainerFilm {
        val art = if (grammar == FilmGrammar.IllustratedNews) ArtDirector.news(n, seed) else ArtDirector.choose(n, seed)
        val plans = chapters.map { it.plan }
        val bounds = RulesDirector.bounds(n, plans)
        val accents = ArtDirector.accents(plans.size, seed)
        val out = chapters.mapIndexed { i, w ->
            val (start, end) = bounds[i]
            val perClause = mutableMapOf<Int, Int>()
            val beats = w.beats.mapNotNull { d -> beat(n, w.plan, i, d, perClause, w.beats) }.sortedBy { it.at }.map { it.copy(at = it.at.coerceIn(start, end)) }
            Chapter(w.plan.title, start, end, accents[i], beats, w.transition.trim().lowercase().takeIf { it in TRANSITIONS }.orEmpty())
        }
        val captions = if (art.captionStyle == ArtDirector.NO_CAPTIONS) emptyList() else Captions.of(n.words, n.persian)
        return ExplainerFilm(art, out, captions, n.language)
    }

    private fun beat(n: Narration, plan: Chapters.Plan, chapter: Int, d: DraftBeat, perClause: MutableMap<Int, Int>, all: List<DraftBeat>): Beat? {
        val k = d.clause.coerceIn(plan.clauses.first, plan.clauses.last)
        val c = n.clauses.getOrNull(k) ?: return null
        val primitive = PRIMITIVES[d.primitive.trim().lowercase()]
        val op = OPS[d.op.trim().lowercase()] ?: if (primitive != null) StageOp.Add else StageOp.Highlight
        val nth = perClause.getOrDefault(k, 0).also { perClause[k] = it + 1 }
        val count = all.count { it.clause.coerceIn(plan.clauses.first, plan.clauses.last) == k }.coerceAtLeast(1)
        val at = cue(n, c, d.cue) ?: (c.start + c.length * nth / count)
        val items = d.items.mapIndexed { j, label -> Item(label, d.values.getOrNull(j), d.visuals.getOrNull(j).orEmpty()) }
        val hero = d.hero.takeIf { it.isNotBlank() }?.let { IllustrationRequest(it, sign = d.sign.trim()).encode() }.orEmpty()
        val content = BeatContent(
            text = d.text, caption = d.caption, items = items, value = d.value.takeIf { it >= 0.0 }, unit = d.unit, picks = d.picks, visual = d.visual,
            illustration = hero, kicker = d.kicker, punch = d.punch,
        )
        val id = "c${chapter + 1}-" + d.element.lowercase().filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { primitive?.let(::serial) ?: "el" }
        return Beat(at, at, c.text, op, id, primitive.takeIf { op in FilmCheck.CREATES }, content, d.metaphor)
    }

    /** When the cue word is said in [c] (a breath before), or null. */
    private fun cue(n: Narration, c: Clause, cue: String): Double? {
        val key = cue.trim().split(' ').firstOrNull()?.let(Metaphors::bare)?.takeIf { it.length >= 2 } ?: return null
        val i = c.words.firstOrNull { Metaphors.bare(n.texts[it]) == key } ?: c.words.firstOrNull { Metaphors.bare(n.texts[it]).startsWith(key) } ?: return null
        return maxOf(c.start, n.startOf(i) - LEAD)
    }

    // ---- schemas (the subset the on-device grammar compiles: every property emitted, in order) ----

    private fun str(max: Int) = buildJsonObject { put("type", "string"); put("maxLength", max) }
    private fun enum(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private val int = buildJsonObject { put("type", "integer") }
    private val num = buildJsonObject { put("type", "number") }
    private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
    }
    private fun array(item: JsonObject, max: Int, min: Int = 0) = buildJsonObject {
        put("type", "array"); put("items", item); put("maxItems", max)
        if (min > 0) put("minItems", min)
    }

    val beatSchema: JsonObject = obj(
        "clause" to int,
        "metaphor" to str(80),
        "primitive" to enum(listOf("none") + PRIMITIVES.keys),
        "op" to enum(OPS.keys.toList()),
        "element" to str(16),
        "cue" to str(24),
        "text" to str(40),
        "caption" to str(30),
        "items" to array(str(22), 10),
        "visuals" to array(str(24), 10),
        "values" to array(num, 10),
        "value" to num,
        "unit" to str(6),
        "picks" to array(int, 10),
        "visual" to str(30),
        "kicker" to str(20),
        "punch" to str(40),
        "hero" to str(60),
        "sign" to str(24),
    )

    /** One chapter's heading and beats, [clauses] clauses long. */
    fun sceneSchema(clauses: Int): JsonObject = obj("title" to str(28), "beats" to array(beatSchema, maxOf(2, clauses * BEATS_PER_CLAUSE), min = 1), "transition" to enum(TRANSITIONS))

    val filmSchema: JsonObject = obj(
        "chapters" to array(obj("title" to str(28), "first" to int, "last" to int, "beats" to array(beatSchema, 40, min = 1), "transition" to enum(TRANSITIONS)), 24, min = 1),
    )

    val outlineSchema: JsonObject = obj("chapters" to array(obj("first" to int, "title" to str(28)), 10, min = 1))

    val grammarSchema: JsonObject = obj("grammar" to enum(FilmGrammar.entries.map { it.id }), "why" to str(80))


    private const val BEATS_PER_CLAUSE = 3
    private const val LEAD = 0.12
}
