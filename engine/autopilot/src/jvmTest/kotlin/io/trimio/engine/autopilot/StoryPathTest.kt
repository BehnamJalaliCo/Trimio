package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.StoryBeat
import io.trimio.engine.motion.score.StoryMap
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The story path through [Autopilot.produce]: a story map in the request plans every beat through
 * [DesignPass] and [StoryPlanner]; without one the rule planner plans (the fallback), and the
 * self-critique's grades ([ContactSheet], [EditCritic]) reach the report either way.
 */
class StoryPathTest {

    @Test
    fun producePlansTheStoryMapItIsGiven() = runBlocking {
        val production = autopilot().produce(request(map()))
        val report = production.report.joinToString("\n")
        assertEquals(4, production.report.count { it.startsWith("design:") }, "one treatment per beat, with its reason:\n$report")
        assertTrue(report.contains("story planner: 4 scenes from 4 beats"), "the map becomes the score:\n$report")
        assertTrue(report.contains("story: 4 beats (setup, turn, payoff, callback)"), "the report names the map:\n$report")
        assertTrue(production.score.scenes.any { it.camera == "push-in" }, "the payoff is the moment the camera leans in")
        assertTrue(report.contains("contact sheet:") && report.contains("edit critic:"), "the self-critique's grades reach the report:\n$report")
    }

    @Test
    fun withoutAStoryMapTheRulePlannerPlans() = runBlocking {
        val production = autopilot().produce(request(null))
        val report = production.report.joinToString("\n")
        assertTrue(
            production.report.none { it.startsWith("design:") || it.startsWith("story planner:") },
            "the story path stays out of it:\n$report",
        )
        assertTrue(production.report.any { it.startsWith("seed ") }, "the rule planner's own notes stand:\n$report")
    }

    // ---------------------------------------------------------------- fixtures

    private fun autopilot(): Autopilot {
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        return Autopilot(text, brands, VisualLibrary.Empty, model = null, jury = null)
    }

    private fun request(story: StoryMap?) = Autopilot.Request(transcript, voice, prompt, seed = SEED, footage = null, story = story)

    private fun map() = StoryMap(
        beats = listOf(
            StoryBeat(kind = "setup", from = 0, until = 3, claim = "امروز رفتم صرافی دیدم", rationale = "the piece opens on what was seen"),
            StoryBeat(kind = "turn", from = 4, until = 7, claim = "پنجاه درصد تخفیف", rationale = "the turn is the surprise figure"),
            StoryBeat(kind = "payoff", from = 8, until = 12, claim = "سیصد و پنجاه تتر", rationale = "the money is what the piece promises"),
            StoryBeat(kind = "callback", from = 13, until = 17, claim = "همان اول گفتم", rationale = "the callback closes the loop"),
        ),
        objects = listOf("the voucher"),
        banned = listOf("no countdown"),
    )

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private val words: List<Word> = timed(talk)
    private val transcript = Transcript(Language.Persian, words)
    private val voice = run {
        val v = SyntheticVoice(VOICE_S)
        words.forEach { w -> v.word(w.range.startMs / 1000f, w.range.endMs / 1000f) }
        v.pcm()
    }
    private val prompt = "ریلز پرانرژی، سبک نوآر، بدون موسیقی"

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }

    private companion object {
        const val SEED = 3L
        const val VOICE_S = 13f
    }
}