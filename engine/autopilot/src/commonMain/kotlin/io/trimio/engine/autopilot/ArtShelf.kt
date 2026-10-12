package io.trimio.engine.autopilot

import io.trimio.engine.assets.art.ArtCatalogue
import io.trimio.engine.assets.art.ArtEntry
import io.trimio.engine.assets.art.AssetCache
import io.trimio.engine.autopilot.resources.Res
import io.trimio.engine.motion.art.ArtFlavour
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
import io.trimio.engine.motion.visual.VectorIcon
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The animated pictures the director can put in an edit: a searchable catalogue of every
 * licence-clean animated emoji (Noto Animated Emoji, CC BY 4.0) and self-drawing line icon
 * (Material Line Icons, MIT), a small starter pack drawn with no network at all, and the rest
 * fetched on demand ([fetch]: the web, within the edit's time budget) and kept in [cache].
 *
 * A picture that the engine cannot draw faithfully is refused once and never offered again.
 */
class ArtShelf(
    val catalogue: ArtCatalogue,
    private val starter: Map<String, String> = emptyMap(),
    private val cache: AssetCache = AssetCache.Memory(),
) {
    /** An animated picture found for a query, as a vocabulary icon, with its relevance 0..1 and where it came from. */
    data class Found(val icon: VectorIcon, val relevance: Float, val entry: ArtEntry) {
        /** The credit the edit must carry for it (CC BY), or null. */
        val credit: String? get() = entry.source.credit
    }

    private val drawn = HashMap<String, VectorIcon?>()
    private val lock = Mutex()

    /**
     * The best animated pictures for an English [query] and the Persian [line] it illustrates,
     * in the [flavour] the style speaks first (the other one only when it fits much better).
     * Documents come from the starter pack, then the cache, then [fetch] (null: offline).
     */
    suspend fun find(query: String, line: String, flavour: ArtFlavour, limit: Int, fetch: (suspend (String) -> String?)?): List<Found> {
        val own = catalogue.search(query, line, limit * 2, SOURCES.getValue(flavour))
        val best = own.firstOrNull()?.relevance ?: 0f
        val other = if (best >= GOOD) emptyList() else catalogue.search(query, line, limit, SOURCES.getValue(flavour.other()))
            .map { it.copy(relevance = it.relevance - OTHER_FLAVOUR) }
        val hits = (own + other).sortedByDescending { it.relevance }
        val out = mutableListOf<Found>()
        for (hit in hits) {
            if (out.size >= limit) break
            icon(hit.entry, fetch)?.let { out += Found(it, hit.relevance, hit.entry) }
        }
        return out
    }

    /** The picture for [entry], drawn from its document (null when it cannot be had or drawn). */
    suspend fun icon(entry: ArtEntry, fetch: (suspend (String) -> String?)?): VectorIcon? {
        lock.withLock { if (entry.id in drawn) return drawn[entry.id] }
        val doc = document(entry, fetch) ?: return null
        val src = entry.source
        val art = MotionArts.read(src.format, doc, src.width, src.height, src.loops)
        val icon = art?.let { VectorIcon(entry.key, src.id, it.width, it.height, emptyList(), listOf(entry.name) + entry.tags + entry.tagsFa, it) }
        lock.withLock { drawn[entry.id] = icon }
        return icon
    }

    private suspend fun document(entry: ArtEntry, fetch: (suspend (String) -> String?)?): String? {
        starter[entry.id]?.let { return it }
        val url = entry.source.urlOf(entry.key)
        cache.get(url)?.let { return it.ifEmpty { null } }
        val fetched = fetch?.invoke(url) ?: return null
        val doc = if (entry.source.format == MotionArt.FORMAT_SVG) iconifyBody(fetched, entry.key) else fetched
        // A document that came back but holds nothing usable is remembered as empty: not asked again.
        cache.put(url, doc.orEmpty())
        return doc
    }

    /** The SVG body of [name] in an Iconify icon-set answer (`{"icons":{"name":{"body":…}}}`). */
    private fun iconifyBody(text: String, name: String): String? = runCatching {
        Json.parseToJsonElement(text).jsonObject["icons"]?.jsonObject?.get(name)?.jsonObject?.get("body")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun ArtFlavour.other() = if (this == ArtFlavour.Emoji) ArtFlavour.Line else ArtFlavour.Emoji

    companion object {
        private val SOURCES = mapOf(ArtFlavour.Emoji to setOf("noto"), ArtFlavour.Line to setOf("line-md"))

        /** A hit in the style's own flavour this good ends the search there. */
        private const val GOOD = 0.8f

        /** What a picture in the other flavour gives up (it is redrawn in the look's colours, but it was not the style's first voice). */
        private const val OTHER_FLAVOUR = 0.08f

        /**
         * What a video showing the pictures of [score] must credit (CC BY sources), one line per
         * source, e.g. "Animated emoji: Noto Emoji Animation by Google (CC BY 4.0)". The share
         * text or the video's description carries it.
         */
        fun credits(score: io.trimio.engine.motion.score.Score): List<String> {
            val sets = score.scenes.flatMap { s -> s.beats.flatMap { b -> listOfNotNull(b.visual) + b.visuals } }.map { it.substringBefore(':') }.toSet()
            return CREDITS.filterKeys { it in sets }.values.distinct()
        }

        /** Credits by picture set, for sources whose licence asks for one (kept in step with tools/art/build_art_index.py). */
        private val CREDITS = mapOf("noto" to "Animated emoji: Noto Emoji Animation by Google (CC BY 4.0)")

        private var pack: Pair<ArtCatalogue, Map<String, String>>? = null
        private val shelves = HashMap<AssetCache?, ArtShelf>()
        private val loading = Mutex()

        /** The bundled catalogue and starter pack (read once), keeping fetched documents in [cache] (one shelf per cache). */
        suspend fun bundled(cache: AssetCache? = null): ArtShelf = loading.withLock {
            shelves[cache] ?: run {
                val (catalogue, starter) = pack ?: run {
                    val index = Res.readBytes("files/art/index.json").decodeToString()
                    val docs = Json.parseToJsonElement(Res.readBytes("files/art/starter.json").decodeToString()).jsonObject
                        .mapValues { it.value.jsonPrimitive.content }
                    (ArtCatalogue.parse(index) to docs).also { pack = it }
                }
                ArtShelf(catalogue, starter, cache ?: AssetCache.Memory()).also { shelves[cache] = it }
            }
        }
    }
}
