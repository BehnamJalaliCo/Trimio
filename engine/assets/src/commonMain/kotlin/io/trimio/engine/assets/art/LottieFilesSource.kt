package io.trimio.engine.assets.art

import io.trimio.engine.assets.packs.LottieImport
import io.trimio.engine.assets.packs.OnlineArt
import io.trimio.engine.assets.packs.OnlineHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One answer of the web: its HTTP status, its text, and how long the server asked to wait (429/503). */
data class WebReply(val status: Int, val text: String, val retryAfterSeconds: Long? = null)

/**
 * The network as [LottieFilesSource] sees it: the app gives an HTTP client (with Trimio's own
 * User-Agent), tests a fake server. Null means no answer at all (offline, DNS, refused).
 */
interface WebPort {
    suspend fun get(url: String, maxBytes: Long): WebReply?
    suspend fun postJson(url: String, body: String): WebReply?
}

/**
 * Free public animations of the open Lottie library, looked up on demand for the moment that
 * needs one and kept in a local folder («LottieFiles») that every later edit searches first.
 *
 * A query (the director's English `visual`) is answered from the folder's index when it already
 * holds good pictures for it, or was asked recently; otherwise the website's own public search
 * (the GraphQL gateway a browser visit uses, no key) is asked once, the best few free results —
 * named like the query, small, few layers — are downloaded one by one from the asset CDN, checked
 * by the engine's own reader ([validate]) and kept with a small JSON index (id, title, tags,
 * size, file). Pictures the engine refuses are remembered and never fetched again.
 *
 * Polite and bounded: one request at a time, searches at least [Limits.searchGapMs] apart, at
 * most [Limits.maxDownloads] documents per query and [Limits.windowDownloads] per
 * [Limits.windowMs]; every request waits [Limits.requestMs] at most and one look-up
 * [Limits.callMs]; a 429 or 5xx backs off exponentially (honouring Retry-After), and no answer at
 * all means offline for [Limits.offlineMs]. With [online] off or no [web], only the folder answers.
 */
class LottieFilesSource(
    /** The «LottieFiles» folder (Android app data; Windows `%APPDATA%\Trimio\LottieFiles`). */
    private val dir: String,
    private val web: WebPort?,
    /** Null when the engine draws the Lottie document faithfully, otherwise why not. */
    private val validate: (String) -> String?,
    /** The «جستجوی انیمیشن آنلاین» setting: off, only the folder answers. */
    private val online: () -> Boolean = { true },
    private val limits: Limits = Limits(),
    private val fs: FileSystem = SystemFileSystem,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : OnlineArt {

    /** The pace and budgets of the source (the defaults are the app's). */
    data class Limits(
        val requestMs: Long = 6_000,
        val callMs: Long = 10_000,
        val searchGapMs: Long = 1_000,
        val downloadGapMs: Long = 250,
        val page: Int = 16,
        val maxDownloads: Int = 3,
        val windowMs: Long = 10 * 60_000L,
        val windowSearches: Int = 20,
        val windowDownloads: Int = 10,
        val maxDocBytes: Long = 400_000,
        val maxLayers: Int = 60,
        val maxSeconds: Float = 12f,
        val folderBytes: Long = 48L * 1024 * 1024,
        val backoffMs: Long = 2_000,
        val backoffMaxMs: Long = 15 * 60_000L,
        val offlineMs: Long = 60_000,
        val rememberMs: Long = 30L * 24 * 3_600_000,
        val rememberEmptyMs: Long = 24L * 3_600_000,
        val endpoint: String = ENDPOINT,
    )

    /** One animation in the folder. */
    data class Item(
        val id: String,
        val title: String,
        val tags: List<String>,
        val size: Long,
        val file: String,
        val width: Float = 0f,
        val height: Float = 0f,
        val added: Long = 0,
        val used: Long = 0,
    )

    /** A search result of the website, before anything is downloaded. */
    internal data class Candidate(val id: String, val title: String, val tags: List<String>, val url: String, val size: Long, val likes: Int, val relevance: Float)

    private class Remembered(val ids: List<String>, val at: Long)

    private class State(val items: LinkedHashMap<String, Item>, val refused: MutableSet<String>, val queries: MutableMap<String, Remembered>)

    private val lock = Mutex()
    private val gate = Mutex()
    private var state: State? = null
    private var lastRequest = 0L
    private var lastSearch = 0L
    private var blockedUntil = 0L
    private var failures = 0
    private var offlineUntil = 0L
    private val searches = ArrayDeque<Long>()
    private val downloads = ArrayDeque<Long>()

    /** Requests sent so far (searches and downloads), for the run report and tests. */
    var requests: Int = 0
        private set

    override suspend fun search(query: String, limit: Int, fetch: suspend (String) -> String?): List<OnlineHit> = find(query, limit, network = true)

    override suspend fun local(query: String, limit: Int): List<OnlineHit> = find(query, limit, network = false)

    /** The animations in the folder, newest first. */
    suspend fun items(): List<Item> = lock.withLock { load().items.values.sortedByDescending { it.added } }

    /** What the folder holds on disk, in bytes. */
    fun bytes(): Long = runCatching {
        val d = Path(dir)
        if (!fs.exists(d)) 0L else fs.list(d).sumOf { fs.metadataOrNull(it)?.size ?: 0L }
    }.getOrDefault(0L)

    /** «پاک کردن کش انیمیشن‌ها»: every file of the folder and what the index remembered. */
    suspend fun clear() = lock.withLock {
        runCatching {
            val d = Path(dir)
            if (fs.exists(d)) fs.list(d).forEach { runCatching { fs.delete(it) } }
        }
        state = State(LinkedHashMap(), mutableSetOf(), mutableMapOf())
    }

    // ------------------------------------------------------------------------------- the look-up

    private suspend fun find(query: String, limit: Int, network: Boolean): List<OnlineHit> {
        val q = phrase(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val near = nearby(q, limit)
        if (!network || web == null || !online()) return hits(near)
        if (near.count { it.second >= GOOD } >= limit || remembered(q)) return hits(near)
        val fetched = withTimeoutOrNull(limits.callMs) { fetch(q, limit, near.map { it.first.id }.toSet()) }.orEmpty()
        val all = (near + fetched.map { it to relevance(q, it) }).distinctBy { it.first.id }.sortedByDescending { it.second }.take(limit)
        return hits(all)
    }

    /** The folder's own pictures for [q], best first, with their relevance. */
    private suspend fun nearby(q: String, limit: Int): List<Pair<Item, Float>> = lock.withLock {
        val items = load().items
        if (items.isEmpty()) return@withLock emptyList()
        ArtCatalogue(items.values.map(::entryOf)).search(q, limit = limit * 2).mapNotNull { h -> items[h.entry.key]?.let { it to h.relevance } }
    }

    private suspend fun remembered(q: String): Boolean = lock.withLock {
        val r = load().queries[q] ?: return@withLock false
        clock() - r.at < if (r.ids.isEmpty()) limits.rememberEmptyMs else limits.rememberMs
    }

    /** Searches the website (the whole phrase, then its head noun when the phrase finds nothing usable) and downloads the best few. */
    private suspend fun fetch(q: String, limit: Int, have: Set<String>): List<Item> {
        val refused = lock.withLock { load().refused.toSet() }
        var candidates = searchRemote(q)?.let { rank(q, it, refused + have) } ?: return emptyList()
        val head = q.split(' ').last()
        if (candidates.isEmpty() && head != q) candidates = searchRemote(head)?.let { rank(q, it, refused + have) }.orEmpty()
        val kept = mutableListOf<Item>()
        var tried = 0
        for (c in candidates) {
            if (kept.size >= limit || tried >= limits.maxDownloads) break
            tried++
            download(c)?.let { kept += it }
        }
        lock.withLock {
            load().queries[q] = Remembered(kept.map { it.id }, clock())
            save()
        }
        return kept
    }

    /** The website's results that may be used: free, from the asset CDN, small and simple enough, and named like the query. */
    internal fun rank(q: String, text: String, skip: Set<String>): List<Candidate> {
        val edges = runCatching {
            json.parseToJsonElement(text).jsonObject.getValue("data").jsonObject.getValue("searchPublicAnimations").jsonObject.getValue("edges").jsonArray
        }.getOrNull() ?: return emptyList()
        return edges.mapNotNull { e -> candidate(q, (e as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null) }
            .filter { it.id !in skip && it.relevance >= ArtCatalogue.MIN_RELEVANCE }
            .sortedWith(compareByDescending<Candidate> { it.relevance }.thenBy { it.size / SIZE_STEP }.thenByDescending { it.likes })
    }

    private fun candidate(q: String, n: JsonObject): Candidate? {
        val id = n["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = n["jsonUrl"]?.jsonPrimitive?.contentOrNull?.takeIf(::allowed) ?: return null
        val meta = n["metadata"] as? JsonObject
        val size = meta?.get("contentLength")?.jsonPrimitive?.longOrNull ?: n["lottieFileSize"]?.jsonPrimitive?.longOrNull ?: 0L
        val layers = meta?.get("layers")?.jsonPrimitive?.intOrNull ?: 0
        val seconds = meta?.get("duration")?.jsonPrimitive?.floatOrNull ?: 0f
        if (size > limits.maxDocBytes || layers > limits.maxLayers || seconds > limits.maxSeconds) return null
        val title = n["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { return null }.take(TITLE_MAX)
        val tags = words(n["slug"]?.jsonPrimitive?.contentOrNull) + words(n["description"]?.jsonPrimitive?.contentOrNull).take(DESCRIPTION_WORDS)
        val clean = tags.filter { it !in words(title) }.distinct()
        val likes = n["likesCount"]?.jsonPrimitive?.intOrNull ?: 0
        return Candidate(id, title, clean, url, size, likes, ArtCatalogue.relevance(q, title, clean))
    }

    /** Downloads, checks and keeps one animation; null when it cannot be had or the engine would not draw it faithfully. */
    private suspend fun download(c: Candidate): Item? {
        val reply = request(search = false) { it.get(c.url, limits.maxDocBytes) } ?: return null
        if (reply.status != OK) return null
        val doc = reply.text
        val why = when {
            doc.length > limits.maxDocBytes -> "too large"
            runCatching { json.parseToJsonElement(doc).jsonObject["layers"] is JsonArray }.getOrDefault(false) -> validate(doc)
            else -> "not a Lottie animation"
        }
        return lock.withLock {
            val s = load()
            if (why != null) {
                s.refused += c.id
                save()
                return@withLock null
            }
            val file = "${c.id.filter(Char::isLetterOrDigit)}.json"
            val written = runCatching { write(file, doc) }.isSuccess
            if (!written) return@withLock null
            val box = runCatching { json.parseToJsonElement(doc).jsonObject }.getOrNull()
            val tags = (c.tags + LottieImport.layerWords(doc)).map { it.lowercase() }.filter { it.length > 1 && it !in words(c.title) }.distinct().take(MAX_TAGS)
            val now = clock()
            val item = Item(
                c.id, c.title, tags, doc.encodeToByteArray().size.toLong(), file,
                box?.get("w")?.jsonPrimitive?.floatOrNull ?: 0f, box?.get("h")?.jsonPrimitive?.floatOrNull ?: 0f, now, now,
            )
            s.items[c.id] = item
            trim(s)
            save()
            item
        }
    }

    private suspend fun searchRemote(q: String): String? {
        val body = buildJsonObject {
            put("query", SEARCH)
            put("variables", buildJsonObject { put("q", q); put("n", limits.page) })
        }.toString()
        val reply = request(search = true) { it.postJson(limits.endpoint, body) } ?: return null
        return reply.text.takeIf { reply.status == OK }
    }

    /**
     * One request, or none: never while backing off or offline, nor past the rolling window's
     * share of its kind; [Limits.searchGapMs] after the previous search (a download
     * [Limits.downloadGapMs] after the previous request), [Limits.requestMs] at most.
     */
    private suspend fun request(search: Boolean, send: suspend (WebPort) -> WebReply?): WebReply? = gate.withLock {
        val port = web ?: return@withLock null
        val now = clock()
        if (now < maxOf(blockedUntil, offlineUntil)) return@withLock null
        val log = if (search) searches else downloads
        while (log.isNotEmpty() && now - log.first() > limits.windowMs) log.removeFirst()
        if (log.size >= if (search) limits.windowSearches else limits.windowDownloads) return@withLock null
        log.addLast(now)
        val ready = if (search) maxOf(lastSearch, lastRequest) + limits.searchGapMs else lastRequest + limits.downloadGapMs
        if (ready > now) delay(ready - now)
        requests++
        val reply = try {
            withTimeoutOrNull(limits.requestMs) { attempt(port, send) }
        } finally {
            lastRequest = clock()
            if (search) lastSearch = lastRequest
        }
        note(reply)
        reply
    }

    private suspend fun attempt(port: WebPort, send: suspend (WebPort) -> WebReply?): WebReply? = try {
        send(port)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
        null
    }

    /** Backs off after a 429 or 5xx (doubling, Retry-After when longer), goes quiet after no answer, recovers after a success. */
    private fun note(reply: WebReply?) {
        val now = clock()
        when {
            reply == null -> offlineUntil = now + limits.offlineMs
            reply.status == TOO_MANY || reply.status >= SERVER_ERROR -> {
                failures++
                val doubled = limits.backoffMs shl (failures - 1).coerceAtMost(MAX_SHIFT)
                val asked = (reply.retryAfterSeconds ?: 0L) * MS
                blockedUntil = now + maxOf(doubled, asked).coerceAtMost(limits.backoffMaxMs)
            }
            else -> failures = 0
        }
    }

    // ------------------------------------------------------------------------------ the folder

    /** The documents of [found] (in order), each marked as used now (the folder keeps what edits use). */
    private suspend fun hits(found: List<Pair<Item, Float>>): List<OnlineHit> {
        if (found.isEmpty()) return emptyList()
        lock.withLock {
            val s = load()
            val now = clock()
            found.forEach { (i, _) -> s.items[i.id]?.let { s.items[i.id] = it.copy(used = now) } }
        }
        return found.mapNotNull { (item, _) -> read(item.file)?.let { OnlineHit(entryOf(item), it) } }
    }

    private fun relevance(q: String, item: Item) = ArtCatalogue.relevance(q, item.title, item.tags)

    /** Drops the least recently used animations until the folder fits [Limits.folderBytes]. */
    private fun trim(s: State) {
        var total = s.items.values.sumOf { it.size }
        for (old in s.items.values.sortedBy { maxOf(it.used, it.added) }.toList()) {
            if (total <= limits.folderBytes) break
            total -= old.size
            s.items.remove(old.id)
            runCatching { fs.delete(Path(dir, old.file)) }
        }
    }

    private fun load(): State = state ?: readIndex().also { state = it }

    private fun readIndex(): State {
        val empty = State(LinkedHashMap(), mutableSetOf(), mutableMapOf())
        val o = read(INDEX)?.let { t -> runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() } ?: return empty
        (o["items"] as? JsonArray)?.forEach { e -> itemOf(e as? JsonObject)?.takeIf { fs.exists(Path(dir, it.file)) }?.let { empty.items[it.id] = it } }
        (o["refused"] as? JsonArray)?.forEach { e -> e.jsonPrimitive.contentOrNull?.let(empty.refused::add) }
        (o["queries"] as? JsonObject)?.forEach { (q, v) ->
            val r = v as? JsonObject ?: return@forEach
            empty.queries[q] = Remembered((r["ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(), r["at"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        return empty
    }

    private fun itemOf(o: JsonObject?): Item? {
        if (o == null) return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull ?: 0L
        fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull ?: 0f
        val file = s("file")?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && ".." !in it } ?: return null
        val tags = (o["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        return Item(s("id") ?: return null, s("title").orEmpty(), tags, l("size"), file, f("w"), f("h"), l("added"), l("used"))
    }

    private fun save() {
        val s = state ?: return
        val text = buildJsonObject {
            put("v", 1)
            put("items", JsonArray(s.items.values.map { i ->
                buildJsonObject {
                    put("id", i.id); put("title", i.title); put("tags", JsonArray(i.tags.map(::JsonPrimitive))); put("size", i.size); put("file", i.file)
                    put("w", i.width); put("h", i.height); put("added", i.added); put("used", i.used)
                }
            }))
            put("refused", JsonArray(s.refused.map(::JsonPrimitive)))
            put("queries", JsonObject(s.queries.mapValues { (_, r) -> buildJsonObject { put("ids", JsonArray(r.ids.map(::JsonPrimitive))); put("at", r.at) } }))
        }.toString()
        runCatching { write(INDEX, text) }
    }

    private fun write(name: String, text: String) {
        val d = Path(dir)
        if (!fs.exists(d)) fs.createDirectories(d)
        val tmp = Path(dir, "$name.part")
        fs.sink(tmp).buffered().use { it.writeString(text) }
        fs.atomicMove(tmp, Path(dir, name))
    }

    private fun read(name: String): String? = runCatching {
        val p = Path(dir, name)
        if (!fs.exists(p)) null else fs.source(p).buffered().use { it.readByteArray().decodeToString() }
    }.getOrNull()

    companion object {
        /** The gateway the website's own pages query (public, no key). */
        const val ENDPOINT = "https://graphql.lottiefiles.com/2022-08"

        /** The folder's name in the app's data. */
        const val FOLDER = "LottieFiles"

        /** How Trimio introduces itself to the website. */
        const val USER_AGENT = "Trimio/1.0 (on-device video editor; fetches single free animations on demand)"

        /** The picture set of these animations in the catalogue (an id only; never shown). */
        val SOURCE = ArtSource(
            id = "lf", title = "Online animations", author = "", licence = ArtLicence.of("Lottie-Simple-License"), credit = null,
            home = "", url = "lf:{id}", format = "lottie", width = 512f, height = 512f, loops = true,
        )

        private const val INDEX = "index.json"
        private const val OK = 200
        private const val TOO_MANY = 429
        private const val SERVER_ERROR = 500
        private const val MS = 1_000L
        private const val MAX_SHIFT = 16
        private const val GOOD = 0.8f
        private const val MAX_TAGS = 24
        private const val TITLE_MAX = 80
        private const val DESCRIPTION_WORDS = 10
        private const val SIZE_STEP = 50_000L
        private const val MAX_WORDS = 4
        private val ASSET_HOSTS = listOf("assets-v2.lottiefiles.com", "assets.lottiefiles.com", "lottie.host")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val SEARCH = "query S(\$q: String!, \$n: Int) { searchPublicAnimations(query: \$q, first: \$n) { edges { node { " +
            "id name slug description lottieFileSize likesCount jsonUrl metadata { layers duration contentLength } } } } }"

        /** The search phrase of a query: lower-case words, no filler ("an animated rocket icon" → "rocket"), four words at most. */
        fun phrase(query: String): String = words(query).take(MAX_WORDS).joinToString(" ")

        private fun words(text: String?): List<String> = text.orEmpty().lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length > 1 && it !in FILLER && it.none { c -> c in '؀'..'ۿ' } }

        private val FILLER = setOf(
            "a", "an", "the", "of", "with", "and", "or", "in", "on", "for", "to", "icon", "emoji", "animated", "animation", "lottie", "picture", "image",
            "json", "loop", "free", "vector", "illustration",
        )

        private fun allowed(url: String): Boolean = url.startsWith("https://") && url.removePrefix("https://").substringBefore('/') in ASSET_HOSTS

        /** An animation of the folder as a catalogue entry, so the director's search, the picker and the jury treat it like any picture. */
        fun entryOf(item: Item) = ArtEntry(SOURCE, item.id, item.title, item.tags)
    }
}
