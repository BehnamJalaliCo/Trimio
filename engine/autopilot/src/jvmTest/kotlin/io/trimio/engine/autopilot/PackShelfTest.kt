package io.trimio.engine.autopilot

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.trimio.engine.assets.art.ArtEntry
import io.trimio.engine.assets.art.ArtLicence
import io.trimio.engine.assets.art.ArtSource
import io.trimio.engine.assets.packs.ContentPacks
import io.trimio.engine.assets.packs.OnlineHit
import io.trimio.engine.assets.packs.PackDir
import io.trimio.engine.motion.art.ArtFlavour
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Installed content packs join the director's picture search with no network (catalogue merge),
 * and a connected online service is asked only when neither the bundled pictures nor the packs
 * show a query well (packs first, online second).
 */
class PackShelfTest {
    private val root = Files.createTempDirectory("shelf").toFile()

    private val lottie = """{"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[{"ty":4,"ind":1,"ip":0,"op":30,"st":0,"ks":{},""" +
        """"shapes":[{"ty":"rc","p":{"k":[50,50]},"s":{"k":[40,40]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,0.5,0,1]},"o":{"k":100}}]}]}"""

    @AfterTest
    fun reset() {
        ContentPacks.use(emptyList())
        ContentPacks.online = null
        root.deleteRecursively()
    }

    private fun install() {
        val dir = File(root, "art.team/1")
        File(dir, "art/docs").mkdirs()
        File(dir, "art/index.json").writeText(
            """{"v":1,"sources":{"team":{"title":"Team","licence":"Lottie-Simple-License","url":"local:team/{id}","docs":"art/docs/{id}.json",""" +
                """"format":"lottie","w":100,"h":100,"loops":true,"flavour":"emoji"}},"art":[["team","hot-air-balloon","hot air balloon",["balloon","trip","travel"],["بالن"],0,""]]}""",
        )
        File(dir, "art/docs/hot-air-balloon.json").writeText(lottie)
        ContentPacks.use(listOf(PackDir("art.team", "Art", dir.path)))
    }

    @Test
    fun anInstalledPackIsSearchedOffline() = runBlocking {
        val before = BundledArt().animations("hot air balloon", "", ArtFlavour.Emoji, 3).map { it.icon.id }
        assertTrue("hot-air-balloon" !in before)
        install()
        val found = BundledArt().animations("hot air balloon", "", ArtFlavour.Emoji, 3)
        assertEquals("team:hot-air-balloon", found.first().entry.id, "found: ${found.map { it.entry.id to it.relevance }}")
        assertEquals("team", found.first().entry.source.id)
        assertTrue(found.first().icon.animated)
        // The Persian line finds it by its Persian keyword too.
        assertTrue(BundledArt().animations("", "یک بالن بزرگ", ArtFlavour.Emoji, 3).any { it.entry.id == "team:hot-air-balloon" })
    }

    @Test
    fun onlineIsAskedOnlyWhenNothingHereShowsTheQuery() = runBlocking {
        val asked = mutableListOf<String>()
        val source = ArtSource("lottiefiles", "LottieFiles", "", ArtLicence.of("Lottie-Simple-License"), null, "", "lottiefiles:{id}", "lottie", 100f, 100f, true)
        ContentPacks.online = object : io.trimio.engine.assets.packs.OnlineArt {
            override suspend fun search(query: String, limit: Int, fetch: suspend (String) -> String?): List<OnlineHit> {
                asked += query
                return listOf(OnlineHit(ArtEntry(source, "42", "zebra crossing", listOf("zebra", "crossing", "street")), lottie))
            }
        }
        val web = WebKnowledge(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
        val zebra = web.animations("zebra crossing", "", ArtFlavour.Emoji, 2)
        assertEquals("lottiefiles:42", zebra.first().entry.id)
        // A query the bundled pictures answer well never reaches the service.
        web.animations("rocket", "", ArtFlavour.Emoji, 2)
        assertEquals(listOf("zebra crossing"), asked)
        // Offline (no fetch at all) the service is not asked either.
        BundledArt().animations("zebra crossing", "", ArtFlavour.Emoji, 2)
        assertEquals(1, asked.size)
    }
}
