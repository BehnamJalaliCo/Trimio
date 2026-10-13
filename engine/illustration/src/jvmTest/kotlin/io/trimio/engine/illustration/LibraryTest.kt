package io.trimio.engine.illustration

import io.trimio.engine.illustration.generate.DiffusionEngine
import io.trimio.engine.illustration.generate.DiffusionJob
import io.trimio.engine.illustration.generate.DiffusionRecipe
import io.trimio.engine.illustration.generate.GeneratorSource
import io.trimio.engine.illustration.generate.PromptWriter
import io.trimio.engine.illustration.generate.Recipes
import io.trimio.engine.illustration.library.KeywordRanker
import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.library.LibrarySource
import io.trimio.engine.illustration.library.People
import io.trimio.engine.illustration.library.Words
import io.trimio.engine.illustration.quality.StyleCheck
import io.trimio.engine.illustration.quality.StyleStats
import io.trimio.engine.illustration.quality.TextArtefacts
import io.trimio.engine.illustration.style.ArtStyle
import io.trimio.engine.illustration.style.FilmLook
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private val library by lazy { runBlocking { Library.bundled() } }

    @Test
    fun bundledLibraryIsComplete() {
        assertTrue(library.people.size >= 170, "Open Peeps pieces: ${library.people.size}")
        assertTrue(library.items.size >= 220, "objects: ${library.items.size}")
        assertTrue(library.has("hair/Hijab") && library.has("bust/Device") && library.has("face/Concerned"))
        // Every item parses into a drawing and carries Persian keywords.
        for (item in library.items) {
            assertTrue(item.drawing.shapes.isNotEmpty(), "${item.id} draws")
            assertTrue(item.fa.isNotEmpty() && item.en.isNotEmpty(), "${item.id} keywords")
        }
        assertTrue(library.licences.values.all { it in setOf("CC0-1.0", "MIT", "LicenseRef-Trimio-Own") }, "${library.licences}")
    }

    @Test
    fun persianWordsMatchByStem() {
        assertTrue(Words.match("کاربران", "کاربر") > 0f)
        assertTrue(Words.match("بانکی", "بانک") > 0f)
        assertTrue(Words.match("users", "user") > 0f)
        assertEquals(0f, Words.match("bank", "banana"))
        assertEquals("safe", KeywordRanker.head("safe full of gold"))
        assertEquals("گاوصندوق", KeywordRanker.head("گاوصندوق پر از طلا"))
    }

    @Test
    fun rankerPicksTheNamedThing() = runBlocking {
        suspend fun top(q: String) = KeywordRanker.rank(q, library.items, null, 1).first().item.id
        assertEquals("safe", top("safe full of gold"))
        assertEquals("safe", top("گاوصندوق"))
        assertEquals("chart-up", top("نمودار رشد"))
        assertEquals("phone", top("گوشی موبایل"))
        assertEquals("hospital", top("بیمارستان"))
    }

    @Test
    fun castReadsPeopleFeelingsAndActions() {
        val c = People.cast("کاربران", "", "نگران")
        assertEquals(3, c.count)
        assertEquals("Concerned", c.faces.first())
        val man = People.cast("a worried young man", "looking at his phone", "")
        assertEquals(1, man.count)
        assertEquals(listOf("bust/Device"), man.poses)
        assertTrue(People.cast("نمودار رشد", "", "").isEmpty)
        // A woman in a Persian film wears a headscarf.
        val peeps = People.dress(People.cast("زن", "", ""), library, 3, "fa", false)
        assertEquals("Hijab", peeps.single().hair)
    }

    @Test
    fun nothingRelevantGivesNoPicture() = runBlocking {
        val none = LibrarySource(library).illustrate(IllustrationRequest("quantum chromodynamics lagrangian"), FilmLook(), 256)
        assertNull(none)
    }

    @Test
    fun sameSeedSamePicture() = runBlocking {
        val source = LibrarySource(library)
        val r = IllustrationRequest("مرد نگران", action = "گوشی", id = "x")
        val a = source.illustrate(r, FilmLook(seed = 5), 256)!!
        val b = source.illustrate(r, FilmLook(seed = 5), 256)!!
        assertTrue(a.image.argb.contentEquals(b.image.argb))
    }

    @Test
    fun signSlotOnlyWhenANameIsSpoken() = runBlocking {
        val source = LibrarySource(library)
        val with = source.illustrate(IllustrationRequest("مغازه", sign = "میلی"), FilmLook(), 256)!!
        val without = source.illustrate(IllustrationRequest("مغازه"), FilmLook(), 256)!!
        assertNotNull(with.signSlot)
        assertNull(without.signSlot)
    }

    @Test
    fun stylesStayMonochromePlusAccent() = runBlocking {
        val source = LibrarySource(library)
        for (style in ArtStyle.entries) {
            val look = FilmLook(style = style, seed = 9)
            val p = source.illustrate(IllustrationRequest("chart rising", objects = listOf("coin")), look, 320)!!
            val s = StyleStats.of(p.image, look)
            assertTrue(s.foreign < 0.005f, "$style foreign colour ${s.foreign}")
            assertTrue(s.accent > 0.01f, "$style shows the accent")
            assertFalse(TextArtefacts.find(p.image).textLike, "$style no text-like marks")
        }
    }

    @Test
    fun textArtefactsAreFound() {
        // Four letter-like blocks on one baseline (an "H" pattern repeated) read as text.
        val r = Raster.filled(400, 300, Colors.WHITE)
        for (k in 0 until 5) {
            val x0 = 20 + k * 14
            for (y in 140 until 150) {
                for (x in x0 until x0 + 8) {
                    val stem = x < x0 + 2 || x >= x0 + 6
                    if (stem || y in 144..145) r[x, y] = Colors.BLACK
                }
            }
        }
        assertTrue(TextArtefacts.find(r).textLike)
        assertFalse(TextArtefacts.find(Raster.filled(200, 100, Colors.WHITE)).textLike)
    }

    @Test
    fun generatorRetriesOnTextAndStylesTheResult() = runBlocking {
        var calls = 0
        val engine = object : DiffusionEngine {
            override val modelId = "sd15-tcd"
            override val recipe: DiffusionRecipe = Recipes.sd15Lcm
            override suspend fun generate(job: DiffusionJob): Raster {
                calls++
                assertNotNull(job.init, "img2img from the library's sketch")
                assertTrue("pencil sketch" in job.prompt && "text" in job.negative)
                // First answer has a written line, the second is a clean grey disc on white.
                val r = Raster.filled(job.width, job.height, Colors.WHITE)
                if (calls == 1) {
                    for (k in 0 until 6) for (y in 200 until 214) for (x in 100 + k * 18 until 110 + k * 18) if (x % 9 < 3 || y in 206..207) r[x, y] = Colors.BLACK
                } else {
                    for (y in 0 until job.height) for (x in 0 until job.width) {
                        val dx = x - job.width / 2
                        val dy = y - job.height / 2
                        if (dx * dx + dy * dy < 150 * 150) r[x, y] = Colors.argb(255, 120, 120, 130)
                    }
                }
                return r
            }
        }
        val source = GeneratorSource(engine, library)
        val look = FilmLook(seed = 2)
        val p = source.illustrate(IllustrationRequest("گاوصندوق", id = "s"), look, 256)!!
        assertEquals(2, calls)
        assertEquals("generator", p.source)
        assertEquals(look.id, p.look)
        assertTrue(StyleStats.of(p.image, look).foreign < 0.005f)
        assertTrue(Colors.a(p.image[2, 2]) < 10, "white background separated")
    }

    @Test
    fun persianPromptsAreTranslatedForEnglishOnlyModels() {
        val writer = PromptWriter(library)
        val prompt = writer.prompt(IllustrationRequest("زن نگران", objects = listOf("گاوصندوق")), FilmLook(), englishOnly = true)
        assertTrue(prompt.startsWith("woman wearing a headscarf worried"), prompt)
        assertTrue("safe" in prompt, prompt)
        assertFalse(prompt.any { it in '؀'..'ۿ' }, prompt)
    }

    @Test
    fun filmCheckFlagsAForeignLook() = runBlocking {
        val source = LibrarySource(library)
        val look = FilmLook(seed = 4)
        val a = source.illustrate(IllustrationRequest("بانک"), look, 256)!!
        val b = source.illustrate(IllustrationRequest("hospital"), look.copy(seed = 99), 256)!!
        val report = StyleCheck.film(listOf(a, b), look)
        assertTrue(report.issues.any { it.kind == "look" && it.index == 1 })
        assertTrue(StyleCheck.film(listOf(a), look).clean)
    }
}
