package io.trimio.engine.autopilot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.brand.BrandFonts
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.judges.BriefJudge
import io.trimio.engine.autopilot.judges.CtaJudge
import io.trimio.engine.autopilot.judges.HookJudge
import io.trimio.engine.autopilot.judges.LayoutJudge
import io.trimio.engine.autopilot.judges.MotionJudge
import io.trimio.engine.autopilot.judges.MusicJudge
import io.trimio.engine.autopilot.judges.RhythmJudge
import io.trimio.engine.autopilot.judges.SoundJudge
import io.trimio.engine.autopilot.judges.SpellingJudge
import io.trimio.engine.autopilot.judges.TypographyJudge
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TypeSpec
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Each judge on tiny synthetic edits — a good case it passes and a bad case it catches — and the
 * jury's loop raising a deliberately broken plan (no hook, no call to action, a collision, a dead
 * stretch) to ten from every judge.
 */
class JuryTest {
    // A 19 s promo: a hook line, a quota, money given, a deadline, and a two-step ask.
    private val sentence = "سلام دوستان امروز یه خبر خیلی خوب براتون دارم. این کمپین فقط برای هزار نفر اول فعاله و به هر نفر سیصد تتر هدیه میدن. " +
        "فقط چهل و هشت ساعت دیگه وقت دارید پس عجله کنید. کلمه تتر رو برام کامنت کن، بعد برو دایرکتتو چک کن."
    private val prompt = "ریلز پرانرژی، سبک نوآر، کپشن کلمه‌به‌کلمه. آخرش «کلمهٔ تتر رو کامنت کن» واضح‌ترین چیز باشه، موسیقی پرانرژی."

    private val words: List<Word> = run {
        var t = 300L
        sentence.split(' ').filter { it.isNotEmpty() }.map { w ->
            val word = Word(w, TimeRange(t, t + 320), language = Language.Persian, emphasis = if (w.length > 4) 0.6f else 0.2f)
            t += if (w.endsWith('.')) 700 else 380
            word
        }
    }
    private val transcript = Transcript(Language.Persian, words)
    private val lines = Lines.split(words)
    private val understanding = Understanding(
        cta = Cta("comment", "تتر"), hook = Hook(1, "سیصد تتر"), brief = BriefRead(look = "noir", energy = 0.85f, captions = "word", music = "energetic"),
    )

    /** The good plan: a hook, a figure, titles, a punch-in, the ask with its DM step. */
    private val good = Score(
        look = "noir", bpm = 126f, captions = CaptionScore(maxWords = 3), auto = false,
        scenes = listOf(
            SceneScore(from = 0, beats = listOf(BeatScore("slam", text = "خبر خیلی خوب", time = 0.2f, hold = 2.2f, place = "top"))),
            SceneScore(from = 9, beats = listOf(BeatScore("mask-rise", at = 14, text = "هزار نفر اول", place = "top"))),
            SceneScore(from = 22, beats = listOf(BeatScore("voucher", at = 22, hold = 3.3f, value = 300f, suffix = "تتر", label = "هدیه", items = listOf("Tether"), place = "top"))),
            SceneScore(from = 33, camera = "push-in", beats = listOf(BeatScore("mask-rise", at = 34, text = "عجله کنید", place = "top"))),
            SceneScore(
                from = 36,
                beats = listOf(
                    BeatScore("comment", at = 37, text = "تتر", hold = 6f, label = "کامنت کن", place = "top"),
                    BeatScore("stamp", at = 44, text = "دایرکتتو چک", hold = 1.8f, energy = 0.8f, place = "lower"),
                ),
            ),
        ),
    )

    private fun context(
        score: Score,
        prompt: String = this.prompt,
        u: Understanding = understanding,
        music: MusicMood? = MusicMood.Energetic,
        edit: EditPlan? = null,
        sound: Soundtrack.Result? = null,
        subject: Subject? = null,
        lexicon: PersianSpelling? = null,
    ): Jury.Context {
        val out = compiler.compile(Compiler.Input(score, transcript, footage = "main", edit = edit, subject = subject))
        val body = edit?.remap(transcript)?.words ?: words
        return Jury.Context(
            out, score, body, lines, u, prompt, music = music, edit = edit, source = words, prelude = edit?.preludeWords(transcript).orEmpty(),
            subject = subject, sound = sound, lexicon = lexicon, measure = measure,
        )
    }

    private fun judge(j: Judge, c: Jury.Context) = j.score(c).also { v -> println("${v.judge} ${v.score}: ${v.findings.joinToString(" | ") { "${it.severity} ${it.what}" }}") }

    @Test
    fun theGoodPlanPassesEveryJudge() {
        val panel = Jury().deliberate(context(good))
        println(panel.summary())
        panel.findings().forEach(::println)
        assertTrue(panel.unanimous, panel.summary() + "\n" + panel.findings().joinToString("\n"))
    }

    @Test
    fun hookWantsSomethingStrongByOneAndAHalfSeconds() {
        val late = good.copy(scenes = listOf(good.scenes[0].copy(beats = emptyList())) + good.scenes.drop(1))
        val v = judge(HookJudge(), context(late))
        assertTrue(v.score <= 7f, "nothing until ${'$'}{5.9}s is a failed hook")
        val fixed = HookJudge().revise(context(late), v)!!
        assertEquals(10f, judge(HookJudge(), context(fixed)).score)
    }

    @Test
    fun aColdOpenCutIntoAWordCostsAPoint() {
        // «سیصد تتر هدیه»: words 22–24. Precise: from the first word's start to the last word's end.
        val a = words[22].range.startMs / 1000f - 0.05f
        val b = words[24].range.endMs / 1000f + 0.03f
        val edit = EditPlan.identity(words.last().range.endMs / 1000f + 0.5f)
        val precise = judge(HookJudge(), context(good, edit = edit.withColdOpen(a..b, 0.25f)))
        assertEquals(10f, precise.score, "${precise.findings}")
        // The previous version's fault (the owner's 9/10): the cut clips the last word's tail.
        val sloppy = judge(HookJudge(), context(good, edit = edit.withColdOpen(a..(words[24].range.endMs / 1000f - 0.15f), 0.25f)))
        assertEquals(9f, sloppy.score, "${sloppy.findings}")
    }

    @Test
    fun rhythmCatchesDeadStretchesAndClutter() {
        val sparse = good.copy(scenes = good.scenes.take(1) + good.scenes.last())
        val v = judge(RhythmJudge(), context(sparse))
        assertTrue(v.findings.any { "without a visual change" in it.what })
        val fixed = RhythmJudge().revise(context(sparse), v)!!
        assertTrue(judge(RhythmJudge(), context(fixed)).findings.none { "without a visual change" in it.what })

        val crowded = good.copy(
            scenes = good.scenes.mapIndexed { i, s ->
                if (i != 2) s else s.copy(beats = s.beats + BeatScore("slam", text = "سیصد", time = 9.2f, hold = 2f, place = "center") + BeatScore("stamp", text = "هدیه", time = 9.4f, hold = 2f, place = "lower"))
            },
        )
        assertTrue(judge(RhythmJudge(), context(crowded)).findings.any { "three graphics" in it.what })
        assertEquals(10f, judge(RhythmJudge(), context(good)).score)
    }

    @Test
    fun motionWantsReadableHoldsAndVariety() {
        val short = good.copy(scenes = good.scenes.mapIndexed { i, s -> if (i == 2) s.copy(beats = s.beats.map { it.copy(hold = 1.4f) }) else s })
        val v = judge(MotionJudge(), context(short))
        assertTrue(v.findings.any { "voucher holds" in it.what })
        val fixed = MotionJudge().revise(context(short), v)!!
        assertTrue(judge(MotionJudge(), context(fixed)).findings.none { "voucher holds" in it.what })

        val twice = good.copy(scenes = good.scenes.mapIndexed { i, s -> if (i == 1) s.copy(beats = s.beats.map { it.copy(recipe = "slam") }) else s })
        val r = judge(MotionJudge(), context(twice))
        assertTrue(r.findings.any { "back to back" in it.what }, "${r.findings}")
        assertEquals(10f, judge(MotionJudge(), context(good)).score)
    }

    @Test
    fun typographyReadsTheSceneGraph() {
        assertEquals(10f, judge(TypographyJudge(), context(good)).score)
        val c = context(good)
        fun text(s: String, size: Float, rtl: Boolean) = TextNode(s, TypeSpec(BrandFonts.Role.Display, 600, size), Color.White.fill(), maxWidth = 900f, rtl = rtl, start = 2f, end = 5f, name = "mask-rise")
        val root = Group(listOf(text("سلام دوستان", 18f, rtl = true), text("سلام دوستان", 80f, rtl = false)))
        val bad = c.copy(compiled = c.compiled.copy(composition = Composition(1080, 1920, 30, c.duration, Color.Black, root)))
        val v = judge(TypographyJudge(), bad)
        assertTrue(v.findings.any { "18 px" in it.what }, "tiny text")
        assertTrue(v.findings.any { "left-to-right" in it.what }, "Persian set LTR")
        assertTrue(v.findings.any { "scrims" in it.what }, "no scrims over footage")
    }

    @Test
    fun spellingKnowsEveryWordOnScreen() = runBlocking {
        val lexicon = PersianSpelling.load()
        val wrong = good.copy(
            scenes = good.scenes.mapIndexed { i, s ->
                if (i != 1) s else s.copy(beats = listOf(BeatScore("mask-rise", at = 14, text = "میتونید وچر", label = "50 هزار نفر", place = "top")))
            },
        )
        val v = judge(SpellingJudge(), context(wrong, lexicon = lexicon))
        assertTrue(v.findings.any { "وچر" in it.what }, "unknown word")
        assertTrue(v.findings.any { "میتونید" in it.what }, "missing half-space")
        assertTrue(v.findings.any { "Latin digits" in it.what }, "Latin digits in Persian")
        val fixed = SpellingJudge().revise(context(wrong, lexicon = lexicon), v)!!
        val after = judge(SpellingJudge(), context(fixed, lexicon = lexicon))
        assertTrue(after.findings.none { it.severity == Severity.Major || "Latin digits" in it.what || "«میتونید»" in it.what }, "${after.findings}")
    }

    @Test
    fun layoutKeepsBoxesApartOffTheFaceAndInsideTheSafeArea() {
        val c = context(good)
        val box = Compiler.Placed("slam", "top", 1f, 3f, "a", 100f, 200f, 900f, 500f, overFootage = true)
        val clash = c.copy(compiled = c.compiled.copy(beats = listOf(box, box.copy(recipe = "stamp", at = 1.5f, left = 300f, top = 400f, right = 800f, bottom = 700f))))
        assertTrue(judge(LayoutJudge(), clash).findings.any { "collides" in it.what })
        val face = c.copy(subject = Subject(0.3f, 0.2f, 0.7f, 0.4f), compiled = c.compiled.copy(beats = listOf(box.copy(top = 400f, bottom = 800f))))
        assertTrue(judge(LayoutJudge(), face).findings.any { "face" in it.what })
        val edge = c.copy(compiled = c.compiled.copy(beats = listOf(box.copy(left = 200f, right = 1060f, top = 1200f, bottom = 1500f))))
        assertTrue(judge(LayoutJudge(), edge).findings.any { "buttons on the right" in it.what })
        assertEquals(10f, judge(LayoutJudge(), context(good, subject = Subject(0.3f, 0.32f, 0.7f, 0.6f))).score)
    }

    @Test
    fun soundIsMeasuredOnTheMix() {
        val rate = 48_000
        val n = (context(good).duration * rate).toInt()
        val voice = PcmAudio(FloatArray(n) { i -> (0.1f * sin(2 * PI * 180 * i / rate) * (if ((i / (rate / 3)) % 2 == 0) 1f else 0.05f)).toFloat() }, rate)
        val mastered = Soundtrack(rate).master(voice, EditPlan.identity(n / rate.toFloat()), words, n / rate.toFloat(), MusicMood.Energetic, 1, emptyList())
        assertEquals(10f, judge(SoundJudge(), context(good, sound = mastered)).score)
        val quiet = Soundtrack.Result(PcmAudio(FloatArray(n) { mastered.audio.samples[it] * 0.3f }, rate), mastered.lufs - 10.5, mastered.peakDb - 10.5)
        assertTrue(judge(SoundJudge(), context(good, sound = quiet)).findings.any { "loudness" in it.what })
        val hot = Soundtrack.Result(PcmAudio(FloatArray(n) { (mastered.audio.samples[it] * 1.6f).coerceIn(-0.99f, 0.99f) }, rate), mastered.lufs, -0.1)
        assertTrue(judge(SoundJudge(), context(good, sound = hot)).findings.any { "true peak" in it.what })
        assertNotNull(SoundJudge.remaster(SoundJudge().score(context(good, sound = hot)), Soundtrack.Levels()))
    }

    @Test
    fun musicFollowsTheBrief() {
        assertEquals(10f, judge(MusicJudge(), context(good)).score)
        assertTrue(judge(MusicJudge(), context(good, prompt = "بدون موسیقی")).score <= 7f, "music against a no-music brief")
        val calm = judge(MusicJudge(), context(good, u = understanding.copy(brief = understanding.brief.copy(music = "auto")), music = MusicMood.Chill))
        assertTrue(calm.findings.any { "high-energy" in it.what }, "${calm.findings}")
        val offGrid = judge(MusicJudge(), context(good.copy(bpm = null)))
        assertTrue(offGrid.findings.any { "beat grid" in it.what })
        assertEquals(126f, MusicJudge().revise(context(good.copy(bpm = null)), offGrid)?.bpm)
    }

    @Test
    fun briefFidelityChecksLookCaptionsKeywordAndElements() {
        assertEquals(10f, judge(BriefJudge(), context(good)).score)
        val off = good.copy(look = "paper", captions = CaptionScore(maxWords = 5))
        val v = judge(BriefJudge(), context(off))
        assertTrue(v.findings.any { "noir" in it.what } && v.findings.any { "word-by-word" in it.what })
        val fixed = BriefJudge().revise(context(off), v)!!
        assertEquals("noir", fixed.look)
        assertTrue(fixed.captions.maxWords <= 3)
        val countdown = judge(BriefJudge(), context(good, prompt = "$prompt شمارش معکوس برای چهل و هشت ساعت"))
        assertTrue(countdown.findings.any { "countdown" in it.what }, "a countdown asked, the deadline said, none shown")
    }

    @Test
    fun theCallToActionOwnsTheEnd() {
        assertEquals(10f, judge(CtaJudge(), context(good)).score)
        val none = good.copy(scenes = good.scenes.dropLast(1))
        assertTrue(judge(CtaJudge(), context(none)).score <= 7f)
        val noStep = good.copy(scenes = good.scenes.dropLast(1) + good.scenes.last().copy(beats = good.scenes.last().beats.take(1)))
        val v = judge(CtaJudge(), context(noStep))
        assertTrue(v.findings.any { "DM" in it.what })
        val fixed = CtaJudge().revise(context(noStep), v)!!
        assertEquals(10f, judge(CtaJudge(), context(fixed)).score)
    }

    @Test
    fun theLoopRaisesABrokenPlanToTen() = runBlocking {
        // No hook, a stack forced over a title (a collision), nothing after the figure (a dead stretch), no call to action.
        val broken = good.copy(
            scenes = listOf(
                SceneScore(from = 0),
                SceneScore(from = 9, beats = listOf(BeatScore("mask-rise", at = 14, text = "هزار نفر اول", place = "top"), BeatScore("stack", text = "سیصد تتر", time = 6.2f, hold = 2.5f, place = "full"))),
                SceneScore(from = 22, beats = listOf(BeatScore("voucher", at = 22, hold = 3.3f, value = 300f, suffix = "تتر", label = "هدیه", items = listOf("Tether"), place = "top"))),
            ),
        )
        val jury = Jury(budget = Jury.Budget(rounds = 5, variations = 0))
        val start = jury.deliberate(context(broken))
        println("broken: ${start.summary()}")
        start.findings().forEach(::println)
        assertTrue(start.of("hook")!!.score < 10f && start.of("cta")!!.score < 10f && start.of("rhythm")!!.score < 10f && start.of("layout")!!.score < 10f)
        val outcome = jury.loop(broken, context = { context(it) }, revise = { _, s -> s }, vary = { null }, log = ::println)
        outcome.panel.findings().forEach(::println)
        assertTrue(outcome.panel.unanimous, outcome.panel.summary())
    }

    private companion object {
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
        val compiler by lazy { Compiler(text, runBlocking { BrandLibrary.load() }) }
        val measure by lazy { LineMeasure { s, type, w, rtl -> text.layout(s, type, w, io.trimio.engine.motion.TextAlign.Center, rtl).lines.size } }
    }
}
