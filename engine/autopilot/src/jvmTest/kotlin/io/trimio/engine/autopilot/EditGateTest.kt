package io.trimio.engine.autopilot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.brand.BrandFonts
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.judges.BriefJudge
import io.trimio.engine.autopilot.judges.LayoutJudge
import io.trimio.engine.autopilot.judges.MotionJudge
import io.trimio.engine.autopilot.judges.SpellingJudge
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.TypeSpec
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Geometry
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The jury as a gate for the defects the owner saw on a real edit (8b.1): graphics out of the
 * frame (drawn and measured, not their slots), English text on a Persian video, a graphic that
 * shows something else than what is said, the same device twice running, and a brief whose
 * explicit asks (numbers as graphics, the site card, the bot's handle, energy) were ignored.
 */
class EditGateTest {
    private val sentence = "سلام دوستان امروز درباره‌ی معامله‌گری حرف می‌زنیم. نود درصد تریدرها توی سال اول ضرر می‌کنن. " +
        "ما توی سایت آموزش دادیم و الان سیصد نفر عضو شدن. برای سیگنال به ربات تلگرام ما پیام بدید."
    private val prompt = "ریلز پرانرژی و حرفه‌ای بساز. هوک: همون ثانیهٔ اول. همهٔ سکوت‌ها و مکث‌ها حذف بشه. " +
        "اعداد و کلمه‌های کلیدی رو با المان نشون بده. کارت آدرس سایت nobitrade.ir رو بذار. CTA آخر: ربات تلگرام @NobiSignal_bot."

    private val words: List<Word> = run {
        var t = 300L
        sentence.split(' ').filter { it.isNotEmpty() }.map { w ->
            val word = Word(w, TimeRange(t, t + 320), language = Language.Persian, emphasis = if (w.length > 4) 0.6f else 0.2f)
            t += if (w.endsWith('.')) 600 else 380
            word
        }
    }
    private val transcript = Transcript(Language.Persian, words)
    private val lines = Lines.split(words)
    private val u = Understanding(cta = Cta("dm", ""), hook = Hook(1, "نود درصد"), brief = BriefRead(energy = 0.85f))

    private fun context(score: Score, geometry: Boolean = false, prompt: String = this.prompt): Jury.Context {
        val out = compiler.compile(Compiler.Input(score, transcript, footage = "main"))
        return Jury.Context(out, score, words, lines, u, prompt, source = words, measure = measure, geometry = if (geometry) probe else null)
    }

    private fun at(word: String) = words.indexOfFirst { it.text.trimEnd('.') == word }

    @Test
    fun geometryFindsWordsThatLeaveTheFrame() {
        val inside = TextNode("سود", TypeSpec(BrandFonts.Role.Display, 800, 90f), Color.White.fill(), transform = Transform(x = 540f.anim, y = 400f.anim), start = 0f, end = 3f)
        val outside = TextNode("ضرر", TypeSpec(BrandFonts.Role.Display, 800, 90f), Color.White.fill(), transform = Transform(x = 20f.anim, y = 900f.anim), start = 0f, end = 3f)
        val root = Group(listOf(Group(listOf(inside, outside), name = "scene-0")), name = "root")
        val beats = listOf(Compiler.Placed("slam", "top", 0f, 3f, "سود"), Compiler.Placed("slam", "center", 0f, 3f, "ضرر"))
        val out = Compiler.Output(Composition(1080, 1920, 30, 3f, Color.Black, root), emptyList(), beats, emptyList(), listOf(listOf(inside), listOf(outside)))
        val tracks = probe.trace(out)
        val a = assertNotNull(tracks[0].rest)
        assertTrue(a.left > 300f && a.right < 780f && a.outside(1080f, 1920f) == 0f, "the centred word is where it was put: $a")
        val b = assertNotNull(tracks[1].rest)
        assertTrue(b.outside(1080f, 1920f) > 30f, "half the word hangs off the left edge: $b")
        assertNotNull(tracks[1].resting().firstNotNullOfOrNull { it.type }, "its letters are traced too")
    }

    @Test
    fun theLayoutJudgeReadsWhatIsDrawnAndStepsAFaultyBeatForward() {
        val score = Score(
            look = "noir", captions = CaptionScore(show = false), auto = false,
            scenes = listOf(SceneScore(from = 0, beats = listOf(BeatScore("slam", text = "نود درصد", at = at("نود"), place = "top")))),
        )
        val c = context(score, geometry = true)
        val v = LayoutJudge().score(c)
        assertTrue(v.findings.none { "frame" in it.what }, "a fitted title stays inside: ${v.findings}")
        // The ladder: a pinned layout or the other band first, then a plain form, then out.
        val p = c.compiled.beats.first { it.recipe == "slam" }
        val b = score.scenes[0].beats[0]
        val first = assertNotNull(io.trimio.engine.autopilot.judges.Bench.Ladder.next(b, p, moveFirst = false))
        assertNotNull(first.variant, "the treatment is pinned on the first step")
        assertNull(io.trimio.engine.autopilot.judges.Bench.Ladder.next(first, p, moveFirst = false), "a plain title still at fault leaves")
        val card = BeatScore("network", at = 3, items = listOf("سود", "ضرر"), variant = 0)
        assertEquals("mask-rise", io.trimio.engine.autopilot.judges.Bench.Ladder.next(card, p, moveFirst = false)?.recipe, "a card becomes a plain title of its words")
    }

    @Test
    fun englishOnAPersianVideoIsCaughtAndFixed() {
        val english = Score(
            look = "noir", auto = false,
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore("list", at = 3, until = 6, text = "api | data | سود", place = "top"))),
                SceneScore(from = 7, beats = listOf(BeatScore("url", at = at("سایت"), text = "nobitrade.ir", place = "top", hold = 3f))),
            ),
        )
        val c = context(english)
        val v = SpellingJudge().score(c)
        assertTrue(v.findings.any { "«api»" in it.what && "Persian video" in it.what }, "${v.findings}")
        assertTrue(v.findings.none { "nobitrade" in it.what }, "the brief's own site is welcome: ${v.findings}")
        val fixed = assertNotNull(SpellingJudge().revise(c, v))
        val after = SpellingJudge().score(context(fixed))
        assertTrue(after.findings.none { "Persian video" in it.what }, "${after.findings}")
    }

    @Test
    fun aGraphicShowsWhatIsBeingSaid() {
        val drift = Score(
            look = "noir", auto = false,
            scenes = listOf(
                SceneScore(from = 0),
                // «سیصد نفر» shown ten words before it is said.
                SceneScore(from = 7, beats = listOf(BeatScore("mask-rise", at = 7, text = "سیصد نفر", place = "top"))),
            ),
        )
        val c = context(drift)
        val v = MotionJudge().score(c)
        assertTrue(v.findings.any { "while something else is said" in it.what }, "${v.findings}")
        val fixed = assertNotNull(MotionJudge().revise(c, v))
        assertEquals(at("سیصد"), fixed.scenes[1].beats.single().at, "landed where it is said")
    }

    @Test
    fun theSameDeviceTwiceRunningIsALoop() {
        val twice = Score(
            look = "noir", auto = false,
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore("counter", at = at("نود"), value = 90f, suffix = "٪", place = "top"))),
                SceneScore(from = at("سیصد"), beats = listOf(BeatScore("counter", at = at("سیصد"), value = 300f, place = "top"))),
            ),
        )
        val v = MotionJudge().score(context(twice))
        assertTrue(v.findings.any { "twice back to back" in it.what }, "${v.findings}")
    }

    @Test
    fun oneKindOfElementNeverCarriesThePiece() {
        val titles = listOf("نود درصد", "سال اول", "ضرر می‌کنن", "سایت", "سیصد نفر", "ربات تلگرام")
        val monotone = Score(
            look = "noir", auto = false,
            scenes = titles.map { t -> at(t.split(' ').first()) }.distinct().sorted().mapIndexed { k, i ->
                SceneScore(from = if (k == 0) 0 else i, beats = listOf(BeatScore(if (k % 2 == 0) "mask-rise" else "slam", at = i, text = titles.first { at(it.split(' ').first()) == i }, place = "top")))
            },
        )
        val v = MotionJudge().score(context(monotone))
        assertTrue(v.findings.any { "titles are" in it.what }, "six titles and nothing else: ${v.findings}")
    }

    @Test
    fun aPersianWordTheBriefWritesStaysPersian() {
        val heard = listOf("کلمه", "تتر", "رو", "کامنت", "کن")
        val fixes = Proofreader.fromBrief(heard, "کلمهٔ «تتر» رو کامنت کنن. واژه‌نامه: تتر (Tether)", PersianSpelling.load())
        assertTrue(fixes[1] == null, "«تتر» is not turned into Tether: $fixes")
    }

    @Test
    fun theBriefIsAChecklist() {
        val bare = Score(look = "noir", auto = false, scenes = listOf(SceneScore(from = 0)))
        val c = context(bare)
        val v = BriefJudge().score(c)
        assertTrue(v.findings.any { "numbers as graphics" in it.what }, "the ninety percent and the three hundred: ${v.findings}")
        assertTrue(v.findings.any { "site card" in it.what }, "${v.findings}")
        assertTrue(v.findings.any { "@NobiSignal_bot" in it.what }, "${v.findings}")
        val fixed = assertNotNull(BriefJudge().revise(c, v))
        val beats = fixed.scenes.flatMap { it.beats }
        assertTrue(beats.any { it.recipe == "counter" && it.value == 90f }, "a counter where ninety percent is said: $beats")
        assertTrue(beats.any { it.recipe == "url" && it.text == "nobitrade.ir" }, "$beats")
        assertTrue(beats.any { it.label?.trim('\u200E') == "@NobiSignal_bot" }, "$beats")
        val after = BriefJudge().score(context(fixed))
        assertTrue(after.findings.none { "numbers as graphics" in it.what || "site card" in it.what || "NobiSignal" in it.what }, "${after.findings}")
    }

    @Test
    fun theBriefsOwnWordsWinOverAModelsGuess() {
        val rules = RulesUnderstander.brief(prompt)
        assertEquals(0.85f, rules.energy)
        val merged = rules.over(BriefRead(look = "lumen", energy = 0.3f, music = "chill"))
        assertEquals(0.85f, merged.energy, "«پرانرژی» is not left to the model")
        assertEquals("lumen", merged.look, "the model fills what the brief leaves open")
        assertEquals("auto", RulesUnderstander.brief("highlight the numbers").look, "«light» inside «highlight» is no style word")
        assertEquals(listOf("@NobiSignal_bot"), Glossary.read(prompt).handles)
        assertEquals(emptyList(), Glossary.read("ایمیل a@b.com و robot").handles)
        assertTrue(SpeechAnalysis.strict(prompt), "«همهٔ سکوت‌ها و مکث‌ها حذف» cuts every pause")
        assertTrue(!SpeechAnalysis.strict("ریلز پرانرژی بساز"), "a brief that says nothing of pauses keeps the usual cut")
    }

    @Test
    fun theLoopKeepsTryingWhileItImproves() {
        val bare = Score(look = "noir", auto = false, scenes = listOf(SceneScore(from = 0)))
        val jury = Jury(listOf(BriefJudge()), Jury.Budget(rounds = 4, variations = 0))
        val outcome = runBlocking {
            jury.loop(bare, context = { context(it) }, revise = { _, fix -> fix.score }, vary = { null })
        }
        assertTrue(outcome.rounds >= 1, "the jury sent it back")
        assertTrue(outcome.panel.min > jury.deliberate(context(bare)).min, "and it came back better: ${outcome.panel.summary()}")
        assertNotEquals(bare, outcome.best)
    }

    private companion object {
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
        val compiler by lazy { Compiler(text, runBlocking { BrandLibrary.load() }) }
        val probe by lazy { Geometry(text.measurer, text.fonts) }
        val measure by lazy { LineMeasure { s, type, w, rtl -> text.layout(s, type, w, io.trimio.engine.motion.TextAlign.Center, rtl).lines.size } }
    }
}
