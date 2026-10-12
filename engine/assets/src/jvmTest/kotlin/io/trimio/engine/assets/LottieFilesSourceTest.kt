package io.trimio.engine.assets

import io.trimio.engine.assets.art.LottieFilesSource
import io.trimio.engine.assets.art.WebPort
import io.trimio.engine.assets.art.WebReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The on-demand animation source against a fake website: search, choosing the best few free
 * results, downloading into the folder with its index, answering later edits from the folder,
 * budgets, pacing, back-off and offline.
 */
class LottieFilesSourceTest {

    /** One search result of the fake website. */
    private data class Node(val id: Int, val name: String, val size: Long = 6_000, val layers: Int = 2, val host: String = "assets-v2.lottiefiles.com", val doc: String? = null) {
        val url get() = "https://$host/a/$id/anim.json"
    }

    /** A tiny valid animation document (a rectangle). */
    private fun lottie(name: String = "shape") = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"nm":"$name","layers":[{"ty":4,"ind":1,"nm":"$name","ip":0,"op":30,"st":0,"ks":{},""" +
        """"shapes":[{"ty":"rc","p":{"k":[50,50]},"s":{"k":[40,40]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,0.5,0,1]},"o":{"k":100}}]}]}"""

    /** A document with a text layer, which the engine's reader refuses. */
    private val withText = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":5,"ind":1,"ip":0,"op":30,"st":0,"ks":{}}]}"""

    /** The engine's check, as the app passes it (here: text layers refused). */
    private val validate: (String) -> String? = { doc -> if ("\"ty\":5" in doc) "text layer" else null }

    /** The website: search results by query, documents by URL, and its moods (offline, slow, rate-limited). */
    private class Site(val results: Map<String, List<Node>>) : WebPort {
        val searches = mutableListOf<String>()
        val gets = mutableListOf<String>()
        val searchTimes = mutableListOf<Long>()
        var offline = false
        var status = 200
        var retryAfter: Long? = null
        var hangMs = 0L
        val docs: Map<String, String> = results.values.flatten().associate { it.url to (it.doc ?: "") }

        override suspend fun postJson(url: String, body: String): WebReply? {
            val q = Json.parseToJsonElement(body).jsonObject.getValue("variables").jsonObject.getValue("q").jsonPrimitive.content
            searches += q
            searchTimes += System.nanoTime() / 1_000_000
            if (offline) return null
            if (hangMs > 0) delay(hangMs)
            if (status != 200) return WebReply(status, "", retryAfter)
            val edges = results[q].orEmpty().joinToString(",") { n ->
                """{"node":{"id":${n.id},"name":"${n.name}","slug":"${n.name.lowercase().replace(' ', '-')}","description":"","lottieFileSize":${n.size},"likesCount":3,""" +
                    """"jsonUrl":"${n.url}","metadata":{"layers":${n.layers},"duration":2,"contentLength":${n.size}}}}"""
            }
            return WebReply(200, """{"data":{"searchPublicAnimations":{"edges":[$edges]}}}""")
        }

        override suspend fun get(url: String, maxBytes: Long): WebReply? {
            gets += url
            if (offline) return null
            if (hangMs > 0) delay(hangMs)
            val doc = docs[url] ?: return WebReply(404, "")
            return WebReply(200, doc)
        }
    }

    private fun folder(): File = Files.createTempDirectory("lottiefiles").toFile().resolve("LottieFiles")

    private val fast = LottieFilesSource.Limits(searchGapMs = 0, downloadGapMs = 0)

    private fun rockets() = Site(
        mapOf(
            "rocket" to listOf(
                Node(1, "rocket", doc = lottie("rocket body")),
                Node(2, "rocket", size = 900_000, doc = lottie()),
                Node(3, "rocket", doc = withText),
                Node(4, "car", doc = lottie()),
                Node(5, "rocket", host = "example.com", doc = lottie()),
                Node(6, "rocket ship", doc = lottie("flame")),
            ),
        ),
    )

    @Test
    fun theBestFewFreeResultsAreDownloadedCheckedAndIndexed() = runBlocking {
        val dir = folder()
        val site = rockets()
        val source = LottieFilesSource(dir.path, site, validate, limits = fast)
        val hits = source.search("an animated rocket icon", 2) { null }
        assertEquals(listOf("rocket"), site.searches, "filler words are not searched")
        assertEquals(setOf("1", "6"), hits.map { it.entry.key }.toSet())
        assertTrue(hits.all { it.document.contains("\"layers\"") && it.entry.source.licence.allowed && it.entry.source.credit == null })
        // Too large, from another host or not about the query: never downloaded. The text-layer one is tried and refused.
        assertEquals(listOf(1, 3, 6).map { "https://assets-v2.lottiefiles.com/a/$it/anim.json" }, site.gets)
        assertEquals(4, source.requests)
        val index = Json.parseToJsonElement(File(dir, "index.json").readText()).jsonObject
        val items = index.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(setOf("1", "6"), items.map { it.getValue("id").jsonPrimitive.content }.toSet())
        items.forEach { i ->
            assertTrue(File(dir, i.getValue("file").jsonPrimitive.content).isFile)
            assertTrue(i.getValue("size").jsonPrimitive.content.toLong() > 0)
        }
        assertTrue("body" in items.first { it["id"]!!.jsonPrimitive.content == "1" }.getValue("tags").jsonArray.map { it.jsonPrimitive.content }, "layer names become keywords")
        assertEquals(listOf("3"), index.getValue("refused").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(source.bytes() > 0)
    }

    @Test
    fun laterEditsAreAnsweredFromTheFolderWithNoRequest() = runBlocking {
        val dir = folder()
        LottieFilesSource(dir.path, rockets(), validate, limits = fast).search("rocket", 2) { null }
        // The next edit: a new source over the same folder.
        val site = rockets()
        val again = LottieFilesSource(dir.path, site, validate, limits = fast)
        assertEquals(setOf("1", "6"), again.search("rocket", 2) { null }.map { it.entry.key }.toSet())
        assertEquals("1", again.search("rockets", 1) { null }.single().entry.key, "plural and singular are one search over the folder")
        assertEquals("6", again.search("rocket ship", 1) { null }.single().entry.key)
        assertEquals(0, again.requests)
        // Offline (no network at all): the folder alone answers.
        assertEquals(setOf("1", "6"), LottieFilesSource(dir.path, null, validate).local("rocket", 2).map { it.entry.key }.toSet())
    }

    @Test
    fun aRefusedAnimationIsNeverFetchedAgain() = runBlocking {
        val dir = folder()
        LottieFilesSource(dir.path, rockets(), validate, limits = fast).search("rocket", 2) { null }
        val site = Site(mapOf("space rocket" to listOf(Node(3, "space rocket", doc = withText), Node(7, "space rocket", doc = lottie()))))
        val hits = LottieFilesSource(dir.path, site, validate, limits = fast).search("space rocket", 1) { null }
        assertEquals("7", hits.single().entry.key)
        assertTrue(site.gets.none { "/3/" in it })
    }

    @Test
    fun tooManyRequestsBacksOffExponentiallyAndHonoursRetryAfter() = runBlocking {
        var now = 1_000_000L
        val site = Site(mapOf("rocket" to listOf(Node(1, "rocket", doc = lottie()))))
        site.status = 429
        val source = LottieFilesSource(folder().path, site, validate, limits = fast.copy(backoffMs = 2_000), clock = { now })
        source.search("rocket", 1) { null }
        assertEquals(1, site.searches.size)
        source.search("money", 1) { null }
        assertEquals(1, site.searches.size, "backing off: no request")
        now += 2_100
        source.search("money", 1) { null }
        assertEquals(2, site.searches.size)
        now += 3_000
        source.search("money", 1) { null }
        assertEquals(2, site.searches.size, "the second wait is twice as long")
        now += 1_200
        site.status = 503
        site.retryAfter = 30
        source.search("money", 1) { null }
        assertEquals(3, site.searches.size)
        now += 10_000
        source.search("money", 1) { null }
        assertEquals(3, site.searches.size, "Retry-After (30 s) is longer than the doubling (8 s)")
        now += 21_000
        site.status = 200
        assertEquals("1", source.search("rocket", 1) { null }.single().entry.key)
        assertEquals(4, site.searches.size)
    }

    @Test
    fun noAnswerMeansOfflineAndTheFolderStillAnswers() = runBlocking {
        val dir = folder()
        LottieFilesSource(dir.path, rockets(), validate, limits = fast).search("rocket", 2) { null }
        val site = Site(emptyMap())
        site.offline = true
        val source = LottieFilesSource(dir.path, site, validate, limits = fast)
        assertEquals("1", source.search("rocket", 1) { null }.single().entry.key)
        assertTrue(source.search("money", 1) { null }.isEmpty())
        assertTrue(source.search("warning", 1) { null }.isEmpty())
        assertEquals(listOf("money"), site.searches, "after no answer the source stays quiet for a while")
    }

    @Test
    fun theSettingOffMeansTheFolderOnly() = runBlocking {
        val dir = folder()
        LottieFilesSource(dir.path, rockets(), validate, limits = fast).search("rocket", 2) { null }
        val site = rockets()
        val off = LottieFilesSource(dir.path, site, validate, online = { false }, limits = fast)
        assertEquals("1", off.search("rocket", 1) { null }.single().entry.key)
        assertTrue(off.search("money", 1) { null }.isEmpty())
        assertEquals(0, site.searches.size + site.gets.size)
    }

    @Test
    fun aHangingSiteIsGivenUpOnWithinTheBudget() = runBlocking {
        val site = rockets()
        site.hangMs = 30_000
        val source = LottieFilesSource(folder().path, site, validate, limits = fast.copy(requestMs = 300, callMs = 800))
        val t0 = System.nanoTime()
        val hits = source.search("rocket", 1) { null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(hits.isEmpty())
        assertTrue(ms < 1_500, "gave up after $ms ms")
    }

    @Test
    fun theCallBudgetStopsDownloadsButKeepsWhatArrived() = runBlocking {
        val dir = folder()
        val site = object : WebPort {
            val inner = rockets()
            override suspend fun postJson(url: String, body: String) = inner.postJson(url, body)
            override suspend fun get(url: String, maxBytes: Long): WebReply? {
                if ("/6/" in url) delay(5_000)
                return inner.get(url, maxBytes)
            }
        }
        val source = LottieFilesSource(dir.path, site, validate, limits = fast.copy(callMs = 600, requestMs = 6_000))
        val t0 = System.nanoTime()
        source.search("rocket", 2) { null }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_500)
        assertEquals(listOf("1"), source.items().map { it.id }, "the first download is kept")
    }

    @Test
    fun searchesArePacedAndTheWindowCapsDownloads() = runBlocking {
        val site = Site(mapOf("money" to (10..15).map { Node(it, "money", doc = lottie()) }))
        val source = LottieFilesSource(folder().path, site, validate, limits = fast.copy(searchGapMs = 300, windowDownloads = 2, maxDownloads = 3))
        source.search("money", 3) { null }
        assertEquals(2, site.gets.size, "two downloads in the window")
        source.search("bitcoin", 1) { null }
        source.search("warning", 1) { null }
        val gaps = site.searchTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 290 }, "searches at least the gap apart: $gaps")
    }

    @Test
    fun theFolderKeepsToItsSizeAndClears() = runBlocking {
        val dir = folder()
        val site = Site(mapOf("money" to listOf(Node(20, "money", doc = lottie())), "coin" to listOf(Node(21, "coin", doc = lottie()))))
        val one = lottie().length.toLong()
        val source = LottieFilesSource(dir.path, site, validate, limits = fast.copy(folderBytes = one + one / 2))
        source.search("money", 1) { null }
        source.search("coin", 1) { null }
        assertEquals(listOf("21"), source.items().map { it.id }, "the older one made room")
        assertTrue(!File(dir, "20.json").exists())
        source.clear()
        assertEquals(0L, source.bytes())
        assertTrue(source.items().isEmpty())
        assertTrue(source.local("coin", 1).isEmpty())
    }
}
