package io.trimio.engine.autopilot

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.trimio.engine.assets.art.AssetCache
import io.trimio.engine.motion.art.ArtFlavour
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Animated pictures for the director: offline starter pack, the web through a cache, time limits, and keeping them with an edit. */
class ArtKnowledgeTest {

    /** A tiny valid Lottie document (what the mock web answers). */
    private val lottie = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":4,"ind":1,"ip":0,"op":30,"st":0,"ks":{},""" +
        """"shapes":[{"ty":"rc","p":{"k":[50,50]},"s":{"k":[40,40]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,0.5,0,1]},"o":{"k":100}}]}]}"""

    @Test
    fun theStarterPackAnswersWithNoNetwork() = runBlocking {
        val found = BundledArt().animations("rocket", "", ArtFlavour.Emoji, 2)
        val first = found.first()
        assertEquals("noto:1f680", first.icon.id)
        assertTrue(first.icon.animated && first.relevance >= 0.9f)
        assertEquals("Animated emoji: Noto Emoji Animation by Google (CC BY 4.0)", first.credit)
        // A line style gets the self-drawing line icon.
        val line = BundledArt().animations("heart", "", ArtFlavour.Line, 1).first()
        assertEquals("line-md:heart", line.icon.id)
        assertTrue(!line.icon.colourful && line.credit == null, "MIT: no credit needed")
    }

    @Test
    fun theWebIsAskedOnceThenTheCacheAnswers() = runBlocking {
        val requests = AtomicInteger()
        val engine = MockEngine { request ->
            requests.incrementAndGet()
            assertTrue(request.url.host == "fonts.gstatic.com", "only the document is fetched: ${request.url}")
            respond(lottie, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val cache = AssetCache.Memory()
        val web = WebKnowledge(HttpClient(engine), assets = cache)
        val found = web.animations("chart decreasing", "", ArtFlavour.Emoji, 1)
        assertEquals("noto:1f4c9", found.single().icon.id)
        assertEquals(1, requests.get())
        assertNotNull(cache.get("https://fonts.gstatic.com/s/e/notoemoji/latest/1f4c9/lottie.json"))
        // Another edit (another knowledge, same cache): no request.
        val again = WebKnowledge(HttpClient(engine), assets = cache).animations("chart decreasing", "", ArtFlavour.Emoji, 1)
        assertEquals("noto:1f4c9", again.single().icon.id)
        assertEquals(1, requests.get())
    }

    @Test
    fun aHangingRequestGivesUpAfterSixSeconds() = runBlocking {
        val engine = MockEngine {
            delay(30_000)
            respond(lottie)
        }
        val t0 = System.nanoTime()
        val found = WebKnowledge(HttpClient(engine)).animations("snail", "", ArtFlavour.Emoji, 1)
        val s = (System.nanoTime() - t0) / 1e9
        assertTrue(found.isEmpty())
        assertTrue(s < WebKnowledge.FETCH_MS / 1000.0 * 2 + 1, "gave up after $s s")
    }

    @Test
    fun allLookUpsOfOnePickShareOneBudget() = runBlocking {
        val slow = object : Knowledge {
            override suspend fun animations(query: String, line: String, flavour: ArtFlavour, limit: Int): List<ArtShelf.Found> {
                delay(1_500)
                return emptyList()
            }
        }
        val queries = (1..6).associate { "thing $it" to "" }
        val t0 = System.nanoTime()
        VisualPicker(VisualLibrary.Empty, slow, webBudgetMs = 2_000).pick(queries)
        val s = (System.nanoTime() - t0) / 1e9
        assertTrue(s < 3.0, "six slow look-ups held to the budget: $s s")
    }

    @Test
    fun thePickerPrefersAMovingPictureAndSaysHowRelevantItIs() = runBlocking {
        val result = VisualPicker(VisualLibrary.Empty, BundledArt(), look = "street-hype").pick(mapOf("rocket" to "بیت‌کوین مثل موشک رفت بالا", "qwzxv" to ""))
        assertEquals("noto:1f680", result.picks["rocket"])
        assertTrue((result.relevance["rocket"] ?: 0f) >= 0.9f)
        assertEquals(null, result.picks["qwzxv"])
        assertTrue(result.credits.single().contains("CC BY 4.0"))
        assertNotNull(result.library.find("noto:1f680")?.motion, "the picked animation is in the compiler's vocabulary")
        // A calm line style: a line icon that draws itself, when one shows the thing.
        val calm = VisualPicker(VisualLibrary.Empty, BundledArt(), look = "minimal-mono").pick(mapOf("heart" to ""))
        assertEquals("line-md:heart", calm.picks["heart"])
    }

    @Test
    fun anAnimatedPictureIsKeptWithTheDeliveredEdit() = runBlocking {
        val icon = BundledArt().animations("fire", "", ArtFlavour.Emoji, 1).first().icon
        val json = Json { ignoreUnknownKeys = true }
        val kept = json.decodeFromString(Delivery.Icon.serializer(), json.encodeToString(Delivery.Icon.serializer(), Delivery.Icon.of(icon)))
        val back = kept.icon()
        assertEquals(icon.id, back.id)
        val art = assertNotNull(back.motion, "drawn again from the kept document, no network")
        assertEquals(icon.motion!!.duration, art.duration)
    }

    @Test
    fun feelingsBecomeReactionsAndThingsStickers() {
        assertEquals("live-reaction", ArtBeats.recipe("fire", 1, 3))
        assertEquals("live-reaction", ArtBeats.recipe("laughing face", 1, 3))
        val things = (0 until 30).map { ArtBeats.recipe("rocket", 7, it) }
        assertTrue(things.all { it == "object" || it == "animated" } && "animated" in things && "object" in things)
    }
}
