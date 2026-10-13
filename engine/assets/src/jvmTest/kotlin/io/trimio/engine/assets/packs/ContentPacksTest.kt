package io.trimio.engine.assets.packs

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.assets.art.ArtLicence
import io.trimio.engine.media.WavCodec
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Installed packs as the engine reads them: art rows and documents, flavours, recorded effects, overlays and looks; and the importer. */
class ContentPacksTest {
    private val root = Files.createTempDirectory("content").toFile()

    @BeforeTest
    fun clean() = ContentPacks.reset()

    @AfterTest
    fun reset() {
        ContentPacks.reset()
        root.deleteRecursively()
    }

    private fun write(dir: File, path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun artPack(): File {
        val dir = File(root, "art.team/1")
        write(
            dir, "art/index.json",
            """{"v":1,"sources":{"team":{"title":"Team","licence":"MIT","url":"local:team/{id}","docs":"art/docs/{id}.json","format":"lottie","w":512,"h":512,"loops":true,"flavour":"emoji"},
               "drawn":{"title":"Drawn","licence":"MIT","url":"https://x/{id}","docs":"art/docs/{id}.svg","format":"svg","w":24,"h":24}},
               "art":[["team","rocket-launch","rocket launch",["rocket","launch","startup"],["موشک"],0,"imported"],["drawn","spinner","spinner",["loading"],[],0,""]]}""",
        )
        write(dir, "art/docs/rocket-launch.json", """{"layers":[]}""")
        write(dir, "art/docs/spinner.svg", "<path d='M0 0'/>")
        return dir
    }

    @Test
    fun artRowsDocumentsAndFlavoursComeFromTheInstalledPacks() {
        assertTrue(ContentPacks.artEntries().isEmpty(), "nothing installed: nothing added")
        val g = ContentPacks.generation
        ContentPacks.use(listOf(PackDir("art.team", "Art", artPack().path)))
        assertTrue(ContentPacks.generation > g)
        assertEquals(setOf("team:rocket-launch", "drawn:spinner"), ContentPacks.artEntries().map { it.id }.toSet())
        assertEquals("""{"layers":[]}""", ContentPacks.artDocument("team:rocket-launch"))
        assertEquals("<path d='M0 0'/>", ContentPacks.artDocument("drawn:spinner"))
        assertNull(ContentPacks.artDocument("team:missing"))
        assertEquals(setOf("team"), ContentPacks.artSources("emoji"))
        assertEquals(setOf("drawn"), ContentPacks.artSources("line"), "an SVG set without a flavour is a line set")
        ContentPacks.use(emptyList())
        assertTrue(ContentPacks.artEntries().isEmpty(), "uninstalled: gone at once")
    }

    @Test
    fun inlineCataloguesCarryTheCoreSet() {
        ContentPacks.addCatalogue(
            """{"v":1,"sources":{"core":{"title":"Core","licence":"MIT","url":"https://x/{id}","format":"svg"}},
               "art":[["core","star","star",["star"],[],0,""]],"docs":{"core:star":"<path d='M1 1'/>"}}""",
        )
        assertEquals("<path d='M1 1'/>", ContentPacks.artDocument("core:star"))
        assertTrue(ContentPacks.artEntries().any { it.id == "core:star" })
    }

    @Test
    fun recordedEffectsAreDecodedAtTheMixRateAndPickedByPalette() {
        val dir = File(root, "sound.x/1")
        val tone = PcmAudio(FloatArray(4410) { (0.5 * sin(2 * PI * 440 * it / 44_100)).toFloat() }, 44_100)
        File(dir, "sound/sfx").mkdirs()
        File(dir, "sound/sfx/hit-a.wav").writeBytes(WavCodec.encodePcm16(tone))
        File(dir, "sound/sfx/hit-b.wav").writeBytes(WavCodec.encodePcm16(tone))
        write(
            dir, "sound/index.json",
            """{"sfx":[{"id":"a","cue":"impact","palettes":["punchy"],"file":"sound/sfx/hit-a.wav"},{"id":"b","cue":"impact","palettes":["soft"],"file":"sound/sfx/hit-b.wav"},
                {"id":"evil","cue":"pop","file":"../../etc/passwd"}],"music":[{"id":"m","title":"Sting","mood":"chill","bpm":0,"seconds":2,"file":"sound/music/m.wav"}]}""",
        )
        ContentPacks.use(listOf(PackDir("sound.x", "Sound", dir.path)))
        val pcm = assertNotNull(ContentPacks.sfx("impact", "punchy", 1, 48_000))
        assertEquals(48_000, pcm.sampleRate)
        assertTrue(pcm.size in 4700..5100, "0.1 s at 48 kHz, was ${pcm.size}")
        assertNull(ContentPacks.sfx("whoosh", "punchy", 1, 48_000), "a cue no pack voices stays synthesised")
        assertNull(ContentPacks.sfx("pop", null, 1, 48_000), "paths outside the pack are ignored")
        assertEquals(2, ContentPacks.sfx().size)
        assertEquals("chill", ContentPacks.music().single().mood)
    }

    @Test
    fun overlaysAndLooksAreListed() {
        val dir = File(root, "overlay.x/1")
        write(dir, "overlay/index.json", """{"overlays":[{"id":"grain","name":"Grain","nameFa":"دانه","kind":"grain","file":"overlay/grain.png","blend":"overlay","tags":["film"]}]}""")
        write(dir, "look/index.json", """{"looks":[{"id":"noir","name":"Noir","nameFa":"نوآر","kind":"lut","file":"look/noir.cube","tags":[]}]}""")
        ContentPacks.use(listOf(PackDir("overlay.x", "Overlay", dir.path)))
        assertEquals("overlay", ContentPacks.overlays().single().blend)
        assertTrue(ContentPacks.looks().single().file.endsWith("look/noir.cube"))
    }

    private fun doc(layer: String, ty: Int = 4) = """{"v":"5.7","fr":30,"ip":0,"op":60,"w":200,"h":200,"nm":"Comp","layers":[{"ty":$ty,"nm":"$layer","ks":{},"shapes":[]}]}"""

    @Test
    fun theImporterChecksTagsAndKeepsEarlierImports() {
        val refuseText: (String) -> String? = { if ("\"ty\":5" in it) "text layer" else null }
        val import = LottieImport(refuseText)
        val (first, r1) = import.build(listOf(LottieImport.Item("Rocket_Launch-02.json", doc("Flame")), LottieImport.Item("price.json", doc("Label", ty = 5))))
        assertEquals(listOf("rocket-launch"), r1.added)
        assertEquals("text layer", r1.refused["price.json"])
        val (second, r2) = import.build(listOf(LottieImport.Item("rocket launch.json", doc("Smoke"), tags = listOf("startup"))), existing = first)
        assertEquals(listOf("rocket-launch-2"), r2.added, "a second file of the same name gets its own key")
        val index = second.getValue(LottieImport.INDEX).decodeToString()
        assertTrue("\"rocket-launch\"" in index && "\"rocket-launch-2\"" in index, "earlier imports are kept")
        assertTrue("startup" in index && "flame" in index && "smoke" in index)
        assertTrue(LottieImport.DOCS + "rocket-launch.json" in second)
        assertEquals(listOf("rocket", "launch"), LottieImport.tagsOf("C:\\x\\RocketLaunch_01.json"))
        assertEquals(listOf("startup", "growth") to listOf("موشک"), LottieImport.csvTags("file,tags,tags_fa\nRocket.json,startup;growth,موشک\n")["Rocket.json"])
        // The creator's own files and LottieFiles' free licence pass the licence gate; unknown terms do not.
        assertTrue(ArtLicence.of(LottieImport.OWNER_LICENCE).allowed)
        assertTrue(ArtLicence.of("Lottie-Simple-License").allowed)
        assertTrue(!ArtLicence.of("LicenseRef-Unknown").allowed)
    }
}
