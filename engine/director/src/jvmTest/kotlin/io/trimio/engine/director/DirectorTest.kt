package io.trimio.engine.director

import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.style.BoxStyle
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.TimelineValidator
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.core.pipeline.Artifacts
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectorTest {

    // A Persian crypto voice-over with a pause, a filler and a call to action.
    private val transcript = Transcript(
        Language.Persian,
        listOf(
            w("سلام", 300, 650), w("دوستان،", 680, 1150),
            w("امروز", 1350, 1700), w("بیت\u200Cکوین", 1720, 2250, 0.9f),
            w("پنج", 2400, 2700, 0.8f), w("درصد", 2720, 3050), w("رشد", 3080, 3500, 0.85f), w("کرد.", 3520, 3800),
            w("اممم", 5200, 5600), // after a 1.4 s pause
            w("سیگنال", 5700, 6200, 0.75f), w("خرید", 6220, 6550), w("ما", 6580, 6750),
            w("به", 6800, 6950), w("تارگت", 6980, 7500, 0.9f), w("رسید.", 7520, 8000),
            w("پیج", 8300, 8600), w("رو", 8620, 8700), w("فالو", 8720, 9100, 0.7f), w("کنید.", 9120, 9500),
        ),
    )
    private val video = InputSource.Video(MediaUri("file:///clip.mp4"), 10_000, VideoFormat(1080, 1920, 30f))
    private val audio = InputSource.AudioOnly(MediaUri("file:///voice.m4a"), 10_000, CanvasSpec(AspectRatio.Portrait9x16))

    private fun w(text: String, start: Long, end: Long, emphasis: Float = 0.2f) =
        Word(text, TimeRange(start, end), language = ScriptDetector.detect(text), emphasis = emphasis)

    private val packs by lazy { runBlocking { StylePackRepository().all() } }

    @Test
    fun readsSpokenNumbersInBothLanguages() {
        fun at(s: String) = NumberWords.at(s.split(' '), 0)
        assertEquals(NumberWords.Match(5.0, 2, NumberWords.Unit.Percent), at("۵ درصد رشد"))
        assertEquals(NumberWords.Match(25.0, 4, NumberWords.Unit.Percent), at("بیست و پنج درصد"))
        assertEquals(NumberWords.Match(9.0, 2, NumberWords.Unit.Percent), at("نه درصد"))
        assertNull(at("نه نمی\u200Cخوام"), "«نه» is «no» unless a unit follows")
        assertEquals(3_000_000.0, at("3 میلیون تومان")?.value)
        assertEquals(NumberWords.Unit.Toman, at("3 میلیون تومان")?.unit)
        assertEquals(NumberWords.Match(200.0, 3, NumberWords.Unit.Dollar), at("two hundred dollars"))
        assertEquals(NumberWords.Match(12.5, 1, NumberWords.Unit.Percent), at("12.5%"))
        assertEquals(1_500.0, at("هزار و پانصد")?.value)
    }

    @Test
    fun countersAndIconsFromPlainSpeech() {
        val t = Transcript(Language.Persian, listOf(w("درآمد", 0, 400), w("بیست", 500, 800), w("درصد", 800, 1100), w("بیشتر", 1100, 1500), w("شد", 1500, 1700), w("ایده", 4000, 4400), w("طلایی", 4400, 4900)))
        val plan = RulesDirector.plan(t, BriefParser.parse(""), packs.first(), 6_000)
        assertEquals(ElementCue(1, "counter", "20%", "بیشتر"), plan.elements.first { it.kind == "counter" })
        assertTrue(plan.elements.any { it.kind == "icon" && it.value == "bulb" && it.word == 5 }, plan.elements.toString())
        assertEquals("rocket", IconMatcher.match("rockets to the moon"))
        assertEquals("trophy", IconMatcher.match("قهرمان"))
    }

    @Test
    fun parsesPersianAndEnglishBriefs() {
        val fa = BriefParser.parse("یه ویدیوی پرانرژی برای ریلز با سبک نئوبروتال بساز، بدون موزیک، روی «سود» تأکید کن. عنوان: راز سود روزانه")
        assertEquals("neobrutalism", fa.styleId)
        assertEquals(0.85f, fa.energy)
        assertEquals(false, fa.music)
        assertTrue(Lexicon.norm("سود") in fa.emphasize)
        assertEquals("راز سود روزانه", fa.headline)

        val en = BriefParser.parse("Calm luxury bitcoin explainer with karaoke captions, don't cut the pauses")
        assertEquals(0.35f, en.energy)
        assertTrue(Lexicon.Topic.Crypto in en.topics)
        assertEquals(CaptionMode.Karaoke, en.captionMode)
        assertEquals(false, en.cutSilences)
        assertEquals(null, en.styleId)

        assertEquals("liquid-glass", BriefParser.parse("Liquid Glass please").styleId)
    }

    @Test
    fun picksStylesFromNamesTagsAndMood() {
        assertEquals("neobrutalism", StyleMatcher.choose(packs, BriefParser.parse("آموزشی و فان")).id)
        assertEquals("liquid-glass", StyleMatcher.choose(packs, BriefParser.parse("تحلیل فارکس لوکس")).id)
        assertEquals("kinetic-typography", StyleMatcher.choose(packs, BriefParser.parse("hype energetic ad")).id)
        assertEquals("liquid-glass", StyleMatcher.choose(packs, BriefParser.parse("")).id, "no signal → default style")
        assertEquals("neobrutalism", StyleMatcher.choose(packs, BriefParser.parse("hype"), explicitId = "neobrutalism").id)
    }

    @Test
    fun rulesDirectorFindsTheStory() {
        val pack = packs.first { it.id == "liquid-glass" }
        val plan = RulesDirector.plan(transcript, BriefParser.parse("سیگنال کریپتو"), pack, 10_000)
        val texts = transcript.words.map { it.text }

        assertTrue(3 in plan.emphasis, "بیت\u200Cکوین is emphasised: ${plan.emphasis.map { texts[it] }}")
        assertTrue(plan.emphasis.none { texts[it] in setOf("به", "رو", "ما") }, "no function words")
        // "بیت\u200Cکوین پنج درصد رشد" is one market move: a ticker card, not a separate counter.
        assertEquals(ElementCue(3, "ticker", "BTC", "+5"), plan.elements.first())
        assertTrue(plan.elements.none { it.kind == "counter" && it.word == 4 })
        assertTrue(plan.elements.any { it.kind == "badge" && it.word == 9 }, "signal badge: ${plan.elements}")
        assertEquals(7, plan.hookEnd, "hook = first sentence")
        assertEquals(15, plan.ctaStart, "CTA = the follow line")
        assertEquals("uplifting", plan.music)
    }

    @Test
    fun composerCutsSilenceAndFillersAndMapsEverythingToOutputTime() {
        val pack = packs.first { it.id == "liquid-glass" }
        val plan = RulesDirector.plan(transcript, BriefParser.parse(""), pack, 10_000)
        val timeline = TimelineComposer.compose(plan, transcript, pack.spec, video, seed = 7)

        val cuts = timeline.clipsOf<CutClip>()
        assertTrue(cuts.any { it.range.startMs >= 3_800 && it.range.endMs <= 5_200 }, "the pause is cut: $cuts")
        assertTrue(cuts.any { it.range.startMs <= 5_200 && it.range.endMs >= 5_600 }, "the filler is cut")
        val edit = EditMap(10_000, cuts.map { it.range })
        assertEquals(edit.outputDurationMs, timeline.durationMs)
        assertTrue(timeline.durationMs < 9_000)

        val captions = timeline.clipsOf<CaptionClip>()
        assertTrue(captions.none { it.text == "اممم" })
        assertEquals(transcript.words.size - 1, captions.size)
        assertTrue(captions.first { it.wordIndex == 3 }.emphasis >= 0.9f)
        assertTrue(captions.filter { it.wordIndex !in plan.emphasis }.all { it.emphasis < pack.spec.captions.emphasis.threshold })
        val ticker = timeline.clipsOf<ElementClip>().first { it.assetId.startsWith("ticker") }
        assertEquals(mapOf("symbol" to "BTC", "change" to "5", "digits" to "fa"), ticker.params)
        assertTrue(timeline.clipsOf<SfxClip>().isNotEmpty())
        assertEquals("music/uplifting", timeline.clipsOf<MusicClip>().single().assetId)
        assertTrue(TimelineValidator().isRenderable(timeline, video), TimelineValidator().validate(timeline, video).toString())
    }

    @Test
    fun audioOnlyGetsAFullBackgroundAndItsOwnCanvas() {
        val pack = packs.first { it.id == "kinetic-typography" }
        val plan = RulesDirector.plan(transcript, BriefParser.parse("بدون موزیک"), pack, 10_000)
        val timeline = TimelineComposer.compose(plan, transcript, pack.spec, audio, seed = 1)
        assertEquals(audio.canvas, timeline.canvas)
        assertEquals(TimeRange(0, timeline.durationMs), timeline.clipsOf<BackgroundClip>().single().range)
        assertTrue(timeline.clipsOf<MusicClip>().isEmpty())
        assertTrue(TimelineValidator().isRenderable(timeline, audio))
    }

    @Test
    fun qualityControlFixesContrast() {
        val pack = packs.first { it.id == "neobrutalism" }
        val clash = pack.spec.copy(
            palette = pack.spec.palette.copy(text = "#FFFF66"),
            captions = pack.spec.captions.copy(box = BoxStyle.Brutal, boxColor = "#FFF200"),
        )
        val plan = EditPlan(styleId = pack.id, energy = 0.7f)
        val (fixed, notes) = QualityControl().tuneStyle(clash, plan, video)
        assertTrue(QualityControl.contrast(fixed.palette.text, "#FFF200") >= 4.5)
        assertTrue(notes.isNotEmpty())
        assertEquals(21.0, QualityControl.contrast("#000000", "#FFFFFF"), 0.01)
    }

    @Test
    fun sanitizerRepairsWildModelOutput() {
        val pack = packs.first()
        val draft = RulesDirector.plan(transcript, BriefParser.parse(""), pack, 10_000)
        val wild = EditPlan(
            styleId = "vaporwave-9000", energy = 7f, captionMode = "Explode", music = "dubstep",
            hookEnd = 400, ctaStart = 2, emphasis = listOf(3, 3, 4, 99, -1),
            elements = listOf(
                ElementCue(4, "counter", "five"), ElementCue(5, "counter", "6%"), // too close to the first
                ElementCue(13, "icon", "rocket"), ElementCue(40, "badge", "x"), ElementCue(9, "hologram"),
            ),
            headline = "x".repeat(200),
        )
        val clean = PlanSanitizer.sanitize(wild, draft, transcript, packs.map { it.id }.toSet())
        assertEquals(draft.styleId, clean.styleId)
        assertEquals(1f, clean.energy)
        assertEquals("auto", clean.captionMode)
        assertEquals(draft.music, clean.music)
        assertEquals(draft.hookEnd, clean.hookEnd)
        assertEquals(listOf(3, 4), clean.emphasis, "duplicates and out-of-range words removed (energy 1 allows 400 ms spacing)")
        assertEquals(listOf(ElementCue(4, "counter", "5"), ElementCue(13, "icon", "rocket")), clean.elements, "unknown icon names map to the closest drawable icon")
        assertEquals(48, clean.headline.length)
    }

    @Test
    fun directionStageUsesTheModelAndHonoursTheBrief(): Unit = runBlocking {
        val reply = """{"styleId":"kinetic-typography","energy":0.9,"captionMode":"auto","cutSilences":true,"cutFillers":true,
            "music":"energetic","hookEnd":7,"ctaStart":15,"emphasis":[3,6,13],
            "elements":[{"word":4,"kind":"counter","value":"5%","label":"رشد"},{"word":13,"kind":"icon","value":"check","label":""}],
            "headline":"بیت\u200Cکوین پرواز کرد","summaryFa":"ادیت پرانرژی با شمارنده","summaryEn":"High-energy edit with a counter"}"""
        val model = FakeModel(Generation(reply, StopReason.EndTurn, "claude-opus-5-5"))
        val (context, signals) = context("بدون موزیک، پرانرژی")
        DirectionStage(StylePackRepository(), { model }).run(context)

        val timeline = context.artifacts.require(StandardArtifacts.Timeline)
        val style = context.artifacts.require(StandardArtifacts.Style)
        assertEquals("kinetic-typography", timeline.styleId)
        assertEquals("kinetic-typography", style.id)
        assertTrue(timeline.clipsOf<MusicClip>().isEmpty(), "the brief said no music, whatever the model said")
        assertTrue(timeline.clipsOf<ElementClip>().any { it.assetId == "icon/check" })
        assertTrue(model.requests.single().schema != null)
        assertTrue(model.released)
        assertTrue(signals.filterIsInstance<LiveSignal.PlanStep>().any { "هوک" in it.textFa })
        assertTrue(signals.none { it is LiveSignal.Note && "جایگزین" in it.textFa })
    }

    @Test
    fun refusalFallsBackToTheRulesAndTellsTheUser(): Unit = runBlocking {
        val model = FakeModel(Generation("", StopReason.Refusal, "claude-opus-5-5"))
        val (context, signals) = context("")
        DirectionStage(StylePackRepository(), { model }).run(context)
        assertNotNull(context.artifacts[StandardArtifacts.Timeline])
        assertTrue(signals.any { it is LiveSignal.Note && "رد کرد" in it.textFa })
    }

    @Test
    fun fallbackModelAnswerIsDisclosed(): Unit = runBlocking {
        val draftJson = """{"styleId":"liquid-glass","energy":0.6,"emphasis":[3],"elements":[]}"""
        val model = FakeModel(Generation(draftJson, StopReason.EndTurn, "claude-opus-4-8"))
        val (context, signals) = context("")
        DirectionStage(StylePackRepository(), { model }).run(context)
        assertTrue(signals.any { it is LiveSignal.Note && "claude-opus-4-8" in it.textEn })
    }

    @Test
    fun worksWithNoModelAndNoSpeech(): Unit = runBlocking {
        val (context, _) = context("", words = false)
        DirectionStage(StylePackRepository(), { null }).run(context)
        val timeline = context.artifacts.require(StandardArtifacts.Timeline)
        assertEquals(10_000, timeline.durationMs)
        assertTrue(timeline.clipsOf<CaptionClip>().isEmpty())
    }

    private class FakeModel(private val reply: Generation) : LanguageModel {
        override val id = "claude-opus-5-5"
        override val isLocal = false
        val requests = mutableListOf<GenerationRequest>()
        var released = false
        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            requests += request
            onText(reply.text)
            return reply
        }
        override suspend fun release() { released = true }
    }

    private fun context(prompt: String, words: Boolean = true): Pair<StageContext, List<LiveSignal>> {
        val signals = mutableListOf<LiveSignal>()
        val artifacts = Artifacts().apply { if (words) set(StandardArtifacts.Transcript, transcript) }
        val ctx = object : StageContext {
            override val job = JobSpec("job", video, prompt, director = DirectorBackend.Cloud, seed = 3)
            override val artifacts = artifacts
            override fun progress(fraction: Float) = Unit
            override suspend fun emit(signal: LiveSignal) { signals += signal }
        }
        return ctx to signals
    }
}
