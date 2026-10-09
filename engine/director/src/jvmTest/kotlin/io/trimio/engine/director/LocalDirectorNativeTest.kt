package io.trimio.engine.director

import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.TimelineValidator
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The on-device director end to end: real llama.cpp, the complete EditPlan grammar, sanitiser,
 * composer and validator. A 230M model writes poor plans, but it must never write an invalid one.
 */
class LocalDirectorNativeTest {

    @Test
    fun tinyLocalModelProducesARenderablePlan(): Unit = runBlocking {
        if (System.getProperty("trimio.nativeTests") != "true") return@runBlocking
        val words = "سلام دوستان امروز بیت\u200Cکوین پنج درصد رشد کرد سیگنال خرید ما به تارگت رسید پیج رو فالو کنید".split(' ')
        val transcript = Transcript(Language.Persian, words.mapIndexed { i, t -> Word(t, TimeRange(300L + i * 480, 700L + i * 480), language = ScriptDetector.detect(t)) })
        val input = InputSource.Video(MediaUri("file:///clip.mp4"), 9_000, VideoFormat(1080, 1920, 30f))
        val packs = StylePackRepository().all()
        val brief = BriefParser.parse("پرانرژی برای اینستاگرام")
        val draft = RulesDirector.plan(transcript, brief, StyleMatcher.choose(packs, brief), input.durationMs)

        LlamaLanguageModel("lfm-test", System.getProperty("trimio.llama.model"), ChatFormat.Lfm, contextSize = 4096).use { model ->
            val start = System.nanoTime()
            val result = LlmDirector(model).refine(brief.prompt, transcript, input, packs, draft)
            val plan = PlanSanitizer.sanitize(result.plan, draft, transcript, packs.map { it.id }.toSet())
            val pack = packs.first { it.id == plan.styleId }
            val timeline = TimelineComposer.compose(plan, transcript, pack.spec, input, seed = 1)
            println("local director: ${(System.nanoTime() - start) / 1_000_000} ms → $plan")
            assertTrue(TimelineValidator().isRenderable(timeline, input), TimelineValidator().validate(timeline, input).toString())
        }
    }
}
