package io.trimio.engine.assets.art

import io.trimio.core.model.text.TextKey
import io.trimio.engine.assets.SemanticIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An open collection of animated pictures: who made it, its licence, and where each document is fetched. */
data class ArtSource(
    val id: String,
    val title: String,
    val author: String,
    val licence: ArtLicence,
    /** The credit a video using it must carry (CC BY), or null. */
    val credit: String?,
    val home: String,
    /** Document URL with `{id}` for the entry's key. */
    val url: String,
    /** "lottie" or "svg" (the engine's `MotionArt` formats). */
    val format: String,
    val width: Float,
    val height: Float,
    /** Its animations loop (emoji) rather than play once and rest (draw-on icons). */
    val loops: Boolean,
) {
    fun urlOf(key: String): String = url.replace("{id}", key)
}

/** One animated picture of the catalogue, with what it shows in English and Persian. */
data class ArtEntry(
    val source: ArtSource,
    val key: String,
    val name: String,
    val tags: List<String>,
    val tagsFa: List<String> = emptyList(),
    val popularity: Int = 0,
    val category: String = "",
) {
    /** "source:key", e.g. "noto:1f680", unique in the catalogue (and the picture's id in a score). */
    val id: String get() = "${source.id}:$key"
}

/** A search result: the entry and how well it shows what was asked, 0..1 (the jury reads it). */
data class ArtHit(val entry: ArtEntry, val relevance: Float)

/**
 * The searchable index of animated pictures the director may use — every entry licence-clean
 * ([ArtLicence.allowed]) — built from the bundled index (tools/art/build_art_index.py). Search
 * takes the director's English `visual` query and, as a second voice, the Persian line it
 * illustrates: words are matched exactly (stemmed), head noun first, and the rest by the
 * character n-gram [SemanticIndex] the rest of the asset matching uses.
 */
class ArtCatalogue(entries: List<ArtEntry>) {
    val entries: List<ArtEntry> = entries.filter { it.source.licence.allowed }

    private val byId = this.entries.associateBy { it.id }
    private val maxPopularity = (this.entries.maxOfOrNull { it.popularity } ?: 0).coerceAtLeast(1)

    /** Entries by every word (stemmed) and every 4-letter prefix of their names and tags. */
    private val byWord: Map<String, List<ArtEntry>> = buildMap<String, MutableList<ArtEntry>> {
        for (e in this@ArtCatalogue.entries) {
            for (w in (listOf(e.name) + e.tags + e.tagsFa).flatMap(::tokens).toSet()) {
                getOrPut(w) { mutableListOf() } += e
                if (w.length > PREFIX) getOrPut(PREFIX_MARK + w.take(PREFIX)) { mutableListOf() } += e
            }
        }
    }

    val size: Int get() = entries.size

    fun entry(id: String): ArtEntry? = byId[id.trim()]

    /**
     * The best entries for an English [query] ("rocket launch", "money"), and the Persian [line]
     * it illustrates when given; [sources] narrows to some collections (a style's flavour).
     * Only hits of at least [minRelevance] are returned.
     */
    fun search(query: String, line: String = "", limit: Int = 5, sources: Set<String>? = null, minRelevance: Float = MIN_RELEVANCE): List<ArtHit> {
        byId[query.trim()]?.let { return listOf(ArtHit(it, 1f)) }
        val q = tokens(query)
        val fa = tokens(line).filter { w -> w.any { it in '؀'..'ۿ' } && w.length >= MIN_FA && w !in STOP_FA }
        if (q.isEmpty() && fa.isEmpty()) return emptyList()
        val candidates = (q + fa).flatMap { w -> byWord[w].orEmpty() + byWord[PREFIX_MARK + w.take(PREFIX)].orEmpty() }
            .distinct().filter { sources == null || it.source.id in sources }
        if (candidates.isEmpty()) return emptyList()
        val grams = if (q.isEmpty()) emptyMap() else SemanticIndex(candidates.map { it to (listOf(it.name) + it.tags) }, dims = GRAM_DIMS).ranked(q.joinToString(" ")).toMap()
        return candidates.map { e ->
            val english = if (q.isEmpty()) 0f else maxOf(words(q, e), (grams[e] ?: 0f) * GRAM_WEIGHT)
            val persian = if (fa.isEmpty()) 0f else persian(fa, e)
            // Popularity only breaks ties between equally good matches; the relevance reported stays the match.
            ArtHit(e, maxOf(english, persian).coerceAtMost(1f)) to POPULARITY * e.popularity / maxPopularity
        }.filter { it.first.relevance >= minRelevance }
            .sortedWith(compareByDescending<Pair<ArtHit, Float>> { it.first.relevance + it.second }.thenBy { it.first.entry.tags.size })
            .take(limit).map { it.first }
    }

    private fun words(q: List<String>, e: ArtEntry): Float = match(q, e.name, e.tags)

    /** Persian line words found among the entry's Persian keywords. */
    private fun persian(fa: List<String>, e: ArtEntry): Float {
        if (e.tagsFa.isEmpty()) return 0f
        val have = e.tagsFa.flatMap(::tokens).toSet()
        val tts = e.tagsFa.firstOrNull()?.let(::tokens).orEmpty()
        val hits = fa.count { it in have }
        if (hits == 0) return 0f
        return if (tts.isNotEmpty() && tts.all { it in fa }) PERSIAN_NAME else PERSIAN_WORD
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * How well a picture called [name] with keywords [tags] shows an English [query], 0..1 — the
         * same measure for animated and still pictures, so the picker and the jury compare them.
         */
        fun relevance(query: String, name: String, tags: List<String>): Float {
            val q = tokens(query)
            if (q.isEmpty()) return 0f
            val words = match(q, name, tags)
            if (words >= COVER) return words
            val grams = SemanticIndex(listOf(Unit to (listOf(name) + tags).filter { it.isNotBlank() }), dims = GRAM_DIMS).ranked(q.joinToString(" ")).firstOrNull()?.second ?: 0f
            return maxOf(words, grams * GRAM_WEIGHT)
        }

        /** Word match of [q] (stemmed tokens, the head noun last) against a picture's name and keywords. */
        private fun match(q: List<String>, name: String, tags: List<String>): Float {
            val texts = listOf(name) + tags
            val phrase = q.joinToString(" ")
            if (texts.any { tokens(it).joinToString(" ") == phrase }) return EXACT
            // One word or two ("lightbulb", "light bulb") name the same thing.
            val joined = q.joinToString("")
            if (q.size > 1 && texts.any { tokens(it).joinToString("") == joined }) return COMPOUND
            val have = texts.flatMap(::tokens).toSet()
            val weights = q.mapIndexed { i, _ -> if (i == q.lastIndex) HEAD else 1f }
            val covered = q.indices.sumOf { i -> if (q[i] in have) weights[i].toDouble() else 0.0 }.toFloat() / weights.sum()
            if (covered <= 0f) return 0f
            // A picture of exactly the thing (its name is all in the query) beats one that merely mentions it.
            val nameWords = tokens(name)
            val exactName = nameWords.isNotEmpty() && nameWords.all { it in q }
            return COVER * covered + if (exactName) NAME_BONUS else 0f
        }

        /** Parses the bundled index (see tools/art/build_art_index.py); sources whose licence is not allowed are dropped. */
        fun parse(text: String): ArtCatalogue {
            val o = json.parseToJsonElement(text).jsonObject
            val sources = o.getValue("sources").jsonObject.mapValues { (id, v) -> source(id, v.jsonObject) }
            val entries = o.getValue("art").jsonArray.mapNotNull { row ->
                val a = row.jsonArray
                val source = sources[a[0].jsonPrimitive.content] ?: return@mapNotNull null
                ArtEntry(
                    source = source, key = a[1].jsonPrimitive.content, name = a[2].jsonPrimitive.content,
                    tags = strings(a.getOrNull(3)), tagsFa = strings(a.getOrNull(4)),
                    popularity = a.getOrNull(5)?.jsonPrimitive?.intOrNull ?: 0, category = a.getOrNull(6)?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return ArtCatalogue(entries)
        }

        private fun source(id: String, o: JsonObject) = ArtSource(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: id,
            author = o["author"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            licence = ArtLicence.of(o["licence"]?.jsonPrimitive?.contentOrNull),
            credit = o["credit"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
            home = o["home"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = o.getValue("url").jsonPrimitive.content,
            format = o.getValue("format").jsonPrimitive.content,
            width = o["w"]?.jsonPrimitive?.floatOrNull ?: DEFAULT_BOX,
            height = o["h"]?.jsonPrimitive?.floatOrNull ?: DEFAULT_BOX,
            loops = o["loops"]?.jsonPrimitive?.booleanOrNull ?: false,
        )

        private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        /** Lower-case words, normalised (Persian letter forms), stop words dropped, English plurals and -ing folded. */
        fun tokens(text: String): List<String> = text.lowercase().split(SPLIT).mapNotNull { raw ->
            val w = TextKey.of(raw)
            if (w.isEmpty() || w in STOP) null else stem(w)
        }

        private fun stem(w: String): String = when {
            w.any { it in '؀'..'ۿ' } -> w.removeSuffix("ها").removeSuffix("های").ifEmpty { w }
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
            w.length > 4 && (w.endsWith("ses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }

        private val SPLIT = Regex("""[^\p{L}\p{N}‌]+""")
        private val STOP = setOf("a", "an", "the", "of", "with", "and", "or", "in", "on", "for", "to", "icon", "emoji", "animated", "animation", "picture", "image")
        private val STOP_FA = setOf("این", "آن", "برای", "که", "را", "با", "از", "به", "در", "یک", "هم", "تا", "می", "خیلی", "همه", "چون", "اگر", "باید", "کنید", "کنیم", "است", "هست", "بود", "شما", "ما", "من", "او")
        private const val PREFIX = 4
        private const val PREFIX_MARK = "^"
        private const val MIN_FA = 3
        private const val GRAM_DIMS = 512
        private const val GRAM_WEIGHT = 0.7f
        private const val EXACT = 1f
        private const val COMPOUND = 0.95f
        private const val HEAD = 2f
        private const val COVER = 0.85f
        private const val NAME_BONUS = 0.1f
        private const val PERSIAN_NAME = 0.8f
        private const val PERSIAN_WORD = 0.6f
        private const val POPULARITY = 0.02f
        private const val DEFAULT_BOX = 24f

        /** Below this a hit is not worth showing. */
        const val MIN_RELEVANCE = 0.5f
    }
}
