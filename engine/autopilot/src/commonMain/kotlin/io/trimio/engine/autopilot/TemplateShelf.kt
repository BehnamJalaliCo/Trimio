package io.trimio.engine.autopilot

import io.trimio.engine.assets.art.AssetCache
import io.trimio.engine.motion.art.MotionArts
import io.trimio.engine.motion.visual.VectorIcon
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.trimio.engine.autopilot.resources.Res

/**
 * Designers' accents — brush underlines, rings, arrows, bursts, a hand that taps — made in After
 * Effects and published free on the open Lottie library, each one looked at and kept only when it
 * reads as a designer's work over a talking head ([curated], by role). The documents are fetched on
 * demand from the library's CDN and kept in [cache], so an edit made once is made again offline.
 *
 * The page remembers what its last edits wore ([remember], [recent]): an edit keeps one accent per
 * role all the way through (one hand, one underline — a look, not a collage), and the next edit
 * picks other ones, so no two posts look alike.
 */
class TemplateShelf(
    val curated: List<Entry>,
    private val cache: AssetCache = AssetCache.Memory(),
) {
    @Serializable
    data class Entry(val id: String, val role: String, val url: String, val grade: Int = 3)

    private val drawn = HashMap<String, VectorIcon?>()
    private val lock = Mutex()

    /**
     * Up to [perRole] accents for each of [roles], best graded first, those the page wore lately
     * ([avoid]) last; documents from [cache], else [fetch] (null: offline — only kept ones answer).
     */
    suspend fun icons(roles: Set<String>, avoid: Set<String>, perRole: Int, fetch: (suspend (String) -> String?)?): List<VectorIcon> = lock.withLock {
        val out = mutableListOf<VectorIcon>()
        for (role in roles) {
            var taken = 0
            for (e in curated.filter { it.role == role }.sortedWith(compareBy<Entry> { it.id in avoid }.thenByDescending { it.grade })) {
                if (taken >= perRole) break
                icon(e, fetch)?.let { out += it; taken++ }
            }
        }
        out
    }

    private suspend fun icon(e: Entry, fetch: (suspend (String) -> String?)?): VectorIcon? {
        if (e.id in drawn) return drawn[e.id]
        val doc = cache.get(e.url) ?: fetch?.invoke(e.url)?.also { cache.put(e.url, it) } ?: return null
        val art = MotionArts.template(doc).first
        val icon = art?.let { VectorIcon(e.id, SET, it.width, it.height, emptyList(), listOf(e.role), it) }
        drawn[e.id] = icon
        return icon
    }

    /** What the page's last [edits] edits wore. */
    fun recent(edits: Int = MEMORY): Set<String> = history().takeLast(edits).flatten().toSet()

    /** Records what this edit wore, for the next ones to wear something else. */
    fun remember(ids: List<String>) {
        if (ids.isEmpty()) return
        cache.put(HISTORY, json.encodeToString(Worn.serializer(), Worn((history() + listOf(ids.distinct())).takeLast(KEEP))))
    }

    private fun history(): List<List<String>> = cache.get(HISTORY)?.let { runCatching { json.decodeFromString(Worn.serializer(), it).edits }.getOrNull() }.orEmpty()

    @Serializable
    private data class Worn(val edits: List<List<String>>)

    companion object {
        /** The icon set templates travel under in a score's vocabulary (`template:<id>`). */
        const val SET = "template"

        /** Edits whose accents the next edit avoids, and how many the page keeps. */
        const val MEMORY = 3
        private const val KEEP = 12
        private const val HISTORY = "trimio:templates:worn"
        private val json = Json { ignoreUnknownKeys = true }

        private var bundledList: List<Entry>? = null
        private val shelves = HashMap<AssetCache?, TemplateShelf>()
        private val loading = Mutex()

        /** The curated accents as shipped, one shelf per [cache]. */
        suspend fun bundled(cache: AssetCache? = null): TemplateShelf = loading.withLock {
            shelves.getOrPut(cache) {
                val list = bundledList ?: runCatching {
                    json.decodeFromString<List<Entry>>(Res.readBytes("files/templates/curated.json").decodeToString())
                }.getOrDefault(emptyList()).also { bundledList = it }
                TemplateShelf(list, cache ?: AssetCache.Memory())
            }
        }
    }
}
