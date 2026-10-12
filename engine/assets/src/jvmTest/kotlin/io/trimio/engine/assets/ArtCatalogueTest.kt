package io.trimio.engine.assets

import io.trimio.engine.assets.art.ArtCatalogue
import io.trimio.engine.assets.art.ArtLicence
import io.trimio.engine.assets.art.DiskAssetCache
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The animated-picture catalogue: licences, search by English query and Persian line, and the disk cache. */
class ArtCatalogueTest {

    private val bundled: ArtCatalogue? by lazy {
        File("../autopilot/src/commonMain/composeResources/files/art/index.json").takeIf { it.isFile }?.let { ArtCatalogue.parse(it.readText()) }
    }

    @Test
    fun onlyLicencesThatLetACreatorPublishAreAllowed() {
        for (ok in listOf("CC0-1.0", "CC0", "Public domain", "MIT", "Apache-2.0", "ISC", "CC-BY-4.0", "CC BY 4.0", "CC BY 3.0")) {
            assertTrue(ArtLicence.of(ok).allowed, ok)
        }
        assertTrue(ArtLicence.of("CC-BY-4.0").attribution)
        assertTrue(ArtLicence.of("CC BY 3.0").attribution)
        assertFalse(ArtLicence.of("MIT").attribution)
        assertFalse(ArtLicence.of("CC0").attribution)
        for (no in listOf("CC BY-SA 4.0", "CC-BY-SA-4.0", "CC BY-NC 4.0", "CC-BY-NC-SA-4.0", "CC BY-ND 4.0", "GPL-3.0", "Lottie Simple License", "Free for personal use", "", null, "Attribution")) {
            assertFalse(ArtLicence.of(no).allowed, "$no")
        }
    }

    @Test
    fun sourcesUnderARefusedLicenceNeverReachTheCatalogue() {
        val index = """
            {"v":1,"sources":{
              "open":{"title":"Open","licence":"MIT","url":"https://x/{id}","format":"svg","w":24,"h":24},
              "sa":{"title":"ShareAlike","licence":"CC-BY-SA-4.0","url":"https://y/{id}","format":"svg","w":24,"h":24}},
             "art":[["open","rocket","rocket",["rocket","launch"],[],0,""],["sa","rocket","rocket",["rocket"],[],0,""]]}
        """.trimIndent()
        val c = ArtCatalogue.parse(index)
        assertEquals(listOf("open:rocket"), c.entries.map { it.id })
        assertEquals("https://x/rocket", c.entries.single().source.urlOf("rocket"))
    }

    @Test
    fun theDirectorsQueryFindsTheRightAnimation() {
        val c = bundled ?: return
        assertTrue(c.size > 1500, "catalogue size ${c.size}")
        fun top(q: String, line: String = "", sources: Set<String>? = null) = c.search(q, line, sources = sources).firstOrNull()
        assertEquals("noto:1f680", top("rocket")?.entry?.id)
        assertEquals("noto:1f525", top("fire")?.entry?.id)
        assertEquals("noto:1f4b8", top("money with wings")?.entry?.id)
        assertEquals("noto:1f4a1", top("light bulb")?.entry?.id)
        assertEquals("noto:1f4c9", top("chart decreasing")?.entry?.id)
        assertEquals("line-md:heart", top("heart", sources = setOf("line-md"))?.entry?.id)
        // Plurals and a phrase whose head noun names the thing.
        assertEquals("noto:1f680", top("rockets")?.entry?.id)
        assertTrue((top("rocket")?.relevance ?: 0f) >= 0.9f)
        // The Persian line alone, through CLDR's Persian keywords.
        assertEquals("noto:1f680", top("", "این سهم مثل موشک رفت")?.entry?.id)
        // Nothing for nonsense.
        assertNull(top("qwzxv"))
    }

    @Test
    fun relevanceSaysHowWellAPictureShowsTheQuery() {
        val engine = ArtCatalogue.relevance("car engine", "engine", listOf("motor", "engine"))
        val fireEngine = ArtCatalogue.relevance("car engine", "fire engine", listOf("fire", "engine", "truck"))
        val unrelated = ArtCatalogue.relevance("car engine", "banana", listOf("fruit"))
        assertTrue(engine > 0.5f, "engine $engine")
        assertTrue(unrelated < 0.3f, "unrelated $unrelated")
        assertTrue(fireEngine <= engine + 0.15f)
        assertEquals(1f, ArtCatalogue.relevance("rocket", "rocket", emptyList()))
    }

    @Test
    fun theDiskCacheKeepsDocumentsAcrossInstancesAndStaysUnderItsSize() {
        val dir = Files.createTempDirectory("art-cache").toFile()
        try {
            val a = DiskAssetCache(dir.path)
            a.put("https://fonts.gstatic.com/s/e/notoemoji/latest/1f680/lottie.json", "{\"v\":1}")
            val b = DiskAssetCache(dir.path)
            assertEquals("{\"v\":1}", b.get("https://fonts.gstatic.com/s/e/notoemoji/latest/1f680/lottie.json"))
            assertNull(b.get("https://other"))
            val small = DiskAssetCache(dir.path, maxBytes = 2_000)
            repeat(10) { small.put("k$it", "x".repeat(500)) }
            val total = dir.listFiles()!!.sumOf { it.length() }
            assertTrue(total <= 2_000, "cache folder $total bytes")
            assertNotNull(small.get("k9"), "the newest entry is still served (memory)")
        } finally {
            dir.deleteRecursively()
        }
    }
}
