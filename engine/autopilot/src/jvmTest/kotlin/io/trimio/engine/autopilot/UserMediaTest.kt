package io.trimio.engine.autopilot

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.AssetReading.Role
import io.trimio.engine.autopilot.workshop.SyntheticAssets
import io.trimio.engine.autopilot.workshop.Workshop
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The creator's media beside the prompt (6.8) and the brief's glossary (6.9): what each asset is
 * (pixels, names, the brief's words, the eyes), which line it belongs to and in what form, and
 * sites and acronyms heard in pieces put back as the brief writes them. All media are synthetic.
 */
class UserMediaTest {
    private val logo = SyntheticAssets.logo("logo", "logo.png").asset
    private val product = SyntheticAssets.product("photo", "IMG_2041.png").asset
    private val screen = SyntheticAssets.screenshot("screen", "IMG_2042.png").asset
    private val clip = SyntheticAssets.clip("clip").asset

    /** Words timed 0.4 s apart, one line per sentence (split on "|"). */
    private fun spoken(text: String): Pair<List<Word>, List<Lines.Line>> {
        val words = mutableListOf<Word>()
        val lines = mutableListOf<Lines.Line>()
        for (sentence in text.split('|')) {
            val first = words.size
            sentence.trim().split(' ').forEach { w -> words += Word(w, TimeRange(words.size * 400L, words.size * 400L + 350L), language = io.trimio.core.model.text.Language.Persian) }
            lines += Lines.Line(first, words.lastIndex)
        }
        return words to lines
    }

    /** One scene per line with a headline, and the ask on the last. */
    private fun planned(lines: List<Lines.Line>) = Score(
        auto = false,
        scenes = lines.mapIndexed { k, l ->
            SceneScore(from = l.first, beats = listOf(if (k == lines.lastIndex) BeatScore("comment", at = l.first) else BeatScore("mask-rise", at = l.first)))
        },
    )

    @Test
    fun pixelsNamesAndTheBriefTellWhatEachAssetIs() {
        val brief = "ریلز لانچ سرم. عکس محصول رو وقتی گفتم سرم بذار، لوگو رو آخر ویدیو نشون بده."
        val readings = runBlocking { AssetReader().read(listOf(logo, product, screen, clip), brief, persian = true) }
        assertEquals(listOf(Role.Logo, Role.Product, Role.Screenshot, Role.Broll), readings.map { it.role }, "a clear square is a logo, a flat tall picture a screenshot")
        assertEquals(listOf("سرم"), readings[1].cue, "«وقتی گفتم سرم بذار»: the cue is the word, not the verb")
        assertEquals(AssetReading.Where.End, readings[0].where, "«لوگو رو آخر ویدیو»")
        val english = AssetReader.rules(UserAsset("l", UserAsset.Kind.Image, logo.thumbnail, logo.alpha, source = "/tmp/logo.png"), "Use logo.png when I say Nike, then a calm outro.")
        assertEquals(listOf("Nike"), english.cue)
        assertEquals(Role.Logo, english.role)
    }

    @Test
    fun eyesSayWhatItIsAndTheCreatorStillWins() {
        val eyes = object : LanguageModel {
            override val id = "fake-eyes"
            override val isLocal = true
            override val canSee = true
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit) = Generation(
                """{"kind":"screenshot","subject":"budget app home","text":"Pennywise","brand":"Pennywise","keywords":["اپ","بودجه","داشبورد"],"keywords_en":["app","budget","dashboard"]}""",
                StopReason.EndTurn, id,
            )
        }
        val r = runBlocking { AssetReader(eyes).read(listOf(product, UserAsset("mark", UserAsset.Kind.Logo, logo.thumbnail)), "", persian = true) }
        assertEquals(Role.Screenshot, r[0].role, "the eyes read the picture")
        assertTrue(listOf("داشبورد", "dashboard", "Pennywise").all { k -> r[0].keywords.contains(k) }, "keywords in both languages: ${r[0].keywords}")
        assertEquals("eyes", r[0].by)
        assertEquals(Role.Logo, r[1].role, "the creator said logo: the eyes do not overrule it")
    }

    @Test
    fun eachAssetLandsOnTheLineThatTalksAboutIt() {
        val (words, lines) = spoken("سلام امروز یه خبر خوب دارم | سرم ویتامین سی ما بالاخره لانچ شد | تا جمعه بیست درصد تخفیف داره | لینک خرید تو بایوئه")
        val brief = "عکس محصول رو وقتی گفتم سرم بذار، لوگو رو آخر ویدیو."
        val assets = listOf(product, logo)
        val readings = assets.map { AssetReader.rules(it, brief, assets) }
        val media = UserMedia(assets, readings, brief)
        val placed = media.place(words, lines, planned(lines))
        val p = placed.first { it.reading.id == "photo" }
        assertEquals(1, p.line)
        assertEquals("سرم", words[p.at].text)
        assertEquals("product", p.recipe)
        assertEquals(3 to "logo-mark", placed.first { it.reading.id == "logo" }.let { it.line to it.recipe }, "a logo with no words signs the call to action")

        val score = media.apply(planned(lines), words, lines, seed = 1)
        val stage = score.scenes.first { it.from == lines[1].first }
        assertEquals("aurora", stage.bg, "a product photo gets a stage of its own")
        assertEquals(listOf("product"), stage.beats.map { it.recipe }, "the line's headline makes room")
        assertEquals("photo", stage.beats.single().media)
        assertEquals("سرم ویتامین سی", stage.beats.single().label, "the card is named in the speaker's words")
        assertNotNull(score.scenes.first { it.from == lines[2].first }.transition, "it cuts back to the speaker")
        val ask = score.scenes.last()
        assertTrue(ask.beats.any { it.recipe == "comment" } && ask.beats.any { it.recipe == "logo-mark" && it.place == "top" }, "the logo above the head, the ask kept")
    }

    @Test
    fun keywordsMatchAcrossScriptsAndClipsBecomeCutIns() {
        val (words, lines) = spoken("I want to tell you something | Last summer I opened a coffee shop downtown | The dashboard shows every bill | Link in bio")
        val brief = "Show the screenshot when I say dashboard."
        val assets = listOf(screen, clip)
        val readings = assets.map { AssetReader.rules(it, brief, assets) }.toMutableList()
        readings[1] = readings[1].copy(keywords = listOf("coffee", "cafe"))
        val placed = UserMedia(assets, readings, brief).place(words, lines, planned(lines))
        assertEquals(2 to "screenshot", placed.first { it.reading.id == "screen" }.let { it.line to it.recipe })
        val c = placed.first { it.reading.id == "clip" }
        assertEquals(1, c.line)
        assertEquals("b-roll", c.recipe, "a clip with time for it cuts in full frame")
        assertEquals(2f, UserMedia.similar("serum", "سرم"), "the same name in the other script")
        assertEquals(1.5f, UserMedia.similar("dashboard", "dashbord"), "a recogniser's slip")
        assertEquals(0f, UserMedia.similar("coffee", "dashboard"))
    }

    @Test
    fun sitesAndAcronymsHeardInPiecesAreWrittenAsTheBriefDoes() {
        val brief = "آموزش اندیکاتور RSI. سایت nobitex.ir رو نشون بده. RSI یعنی شاخص قدرت نسبی، کوتاه توضیح بده."
        val fa = "من همه رو روی چارت نوبیتکس دات آی آر تمرین می‌کنم".split(' ')
        val fixes = Proofreader.fromBrief(fa, brief)
        assertEquals("nobitex.ir", fixes[5])
        assertEquals(listOf("", "", ""), (6..8).map { fixes[it] }, "«دات آی آر» joins the domain")
        val rsi = Proofreader.fromBrief("امروز آر اس آی رو یاد بدم".split(' '), brief)
        assertEquals("RSI", rsi[1])
        assertEquals("RSI", Proofreader.fromBrief("امروز آر‌اس‌آی رو".split(' '), brief)[1], "spelled as one word with half-spaces")
        val en = Proofreader.fromBrief("check nobitex dot ir and the R S I line".split(' '), "Show nobitex.ir and explain RSI.")
        assertEquals("nobitex.ir", en[1])
        assertEquals("RSI", en[6])
        assertNull(Proofreader.fromBrief("امروز آر اس آی رو".split(' '), "یه ریلز جذاب بساز")[1], "no acronym in the brief, nothing to join")
        // The owner's reel: the recogniser wrote his site half in Persian, and that half reached the screen.
        assertEquals("CoinPro.com", Proofreader.fromBrief("اونجا سایت کوینپرو.com".split(' '), "اسم سایت ما CoinPro.com هست.")[2])

        val g = Glossary.read(brief + " لوگو رو از logo.png بردار.")
        assertEquals(listOf("nobitex.ir"), g.sites, "a file name is not a site")
        assertEquals("شاخص قدرت نسبی", g.terms["RSI"])
        assertTrue(g.explain)
    }

    @Test
    fun aSiteGetsItsAddressBarAndAnAskedTermItsMeaning() {
        val (words, lines) = spoken("امروز RSI رو یاد بدم | من روی nobitex.ir تمرین می‌کنم | کامنت بذار")
        val brief = "سایت nobitex.ir رو نشون بده. RSI یعنی شاخص قدرت نسبی، توضیح بده."
        val score = UserMedia(emptyList(), emptyList(), brief).apply(planned(lines), words, lines, seed = 1)
        val url = score.scenes.flatMap { it.beats }.single { it.recipe == "url" }
        assertEquals("nobitex.ir" to 7, url.text to url.at)
        val term = score.scenes.flatMap { it.beats }.single { it.recipe == "lower-third" }
        assertEquals("شاخص قدرت نسبی" to "RSI", term.text to term.label)
        val plain = UserMedia(emptyList(), emptyList(), "سایت nobitex.ir").apply(planned(lines), words, lines, seed = 1)
        assertTrue(plain.scenes.flatMap { it.beats }.none { it.recipe == "lower-third" }, "no definition unless the brief asks for one")
    }

    /** The two workshop pieces with media, edited end to end: every asset used, on its line, in its form, never on the face. */
    @Test
    fun workshopPiecesPlaceEveryAssetOffTheFace() {
        for (id in listOf("fa-launch-perfume-assets", "en-tutorial-app-assets")) {
            val s = Workshop.scenario(id)
            val p = Workshop.run(s, 1L)
            val beats = p.score.scenes.flatMap { it.beats }
            for (a in s.assets) assertTrue(beats.any { it.media == a.id }, "$id: ${a.id} is used: ${p.report.filter { "media" in it }}")
            val subject = Workshop.subject(s)
            val c = p.compiled.composition
            val shown = p.compiled.beats.filter { it.recipe in io.trimio.engine.motion.recipe.MediaRecipes.SHOWS_MEDIA && it.overFootage }
            for (b in shown) {
                val face = subject ?: continue
                val x = minOf(b.right, face.right * c.width) - maxOf(b.left, face.left * c.width)
                val y = minOf(b.bottom, face.bottom * c.height) - maxOf(b.top, face.top * c.height)
                assertTrue(x <= 0f || y <= 0f, "$id: ${b.recipe} at ${b.at}s overlaps the face")
            }
            val placed = p.report.filter { it.startsWith("media:") || it.startsWith("variation") && "media:" in it }
            assertTrue(placed.isNotEmpty(), "$id: the report says where each asset went")
        }
        val perfume = Workshop.run(Workshop.scenario("fa-launch-perfume-assets"), 1L)
        assertTrue(perfume.report.any { "perfume (product) → product" in it && "«عطر»" in it }, "the product photo on «عطر»: ${perfume.report.filter { "media" in it }}")
    }

    /** «مثل این ویدیو بساز»: the clip is a look to follow (its palette, tone and pace), never a picture on screen. */
    @Test
    fun aClipOfferedAsALookIsFollowedNotShown() {
        val brief = "یه ریلز جذاب بساز، دقیقا مثل این ویدیو. اسم سایت ما CoinPro.com هست."
        val readings = runBlocking { AssetReader().read(listOf(clip, logo), brief, persian = true) }
        assertEquals(listOf(Role.Reference, Role.Logo), readings.map { it.role })
        val (words, lines) = spoken("امروز یه نکته مهم دارم | لوگوی ما رو ببینید | کلمه کانال رو کامنت کن")
        val media = UserMedia(listOf(clip, logo), readings, brief)
        assertTrue(media.place(words, lines, planned(lines)).none { it.reading.role == Role.Reference }, "the reference is not placed")
        assertNotNull(media.reference, "its look is read from its frames")
        // A clip the brief says nothing about stays a clip to show.
        assertEquals(Role.Broll, AssetReader.rules(clip, "یه ریلز جذاب بساز").role)
    }
}
