package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.StoryBeat
import io.trimio.engine.motion.score.StoryMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** From story map to score: one treatment per beat with its reason, and a planner that only lays them out in time. */
class StoryPlannerTest {

    @Test
    fun everyBeatEarnsExactlyOneTreatmentWithAReason() {
        val design = DesignPass.design(map(), words)
        assertEquals(4, design.treatments.size, "one treatment per beat: ${design.notes}")
        for (t in design.treatments) assertTrue(t.reason.isNotBlank(), "«${t.beat.claim}» must say why")
        assertEquals(
            listOf("mask-rise", "meter", "voucher", "flip"),
            design.treatments.map { it.beatScore.recipe },
            "setup states, the turn measures, money opens on its card, the callback flips: ${design.notes}",
        )
    }

    @Test
    fun aFigureWithoutMoneyCountsAndAClaimSlams() {
        val figure = map().beats.first { it.kind == "turn" }.let { StoryMap(beats = listOf(it.copy(kind = "payoff"))) }
        assertEquals("counter", DesignPass.design(figure, words).treatments.single().beatScore.recipe, "a number lands as a counter")
        val claim = map().beats.first { it.kind == "setup" }.let { StoryMap(beats = listOf(it.copy(kind = "payoff"))) }
        assertEquals("slam", DesignPass.design(claim, words).treatments.single().beatScore.recipe, "a claim with no figure lands hard")
    }

    @Test
    fun thePlannerOnlyLaysTheTreatmentsOutInTime() {
        val map = map()
        val planned = StoryPlanner.plan(map, words)
        assertEquals(4, planned.score.scenes.size, "one scene per beat: ${planned.notes}")
        val byFrom = map.beats.sortedBy { it.from }
        planned.score.scenes.forEachIndexed { k, sc ->
            assertEquals(byFrom[k].from, sc.from, "scene $k anchors on its beat's first word")
            val beat = sc.beats.single()
            assertEquals(byFrom[k].from, beat.at, "and lands on that word")
            assertTrue((beat.hold ?: 0f) >= 1.5f, "nothing flashes: hold=${beat.hold}")
        }
        assertEquals("push-in", planned.score.scenes[2].camera, "the payoff is the moment the camera leans in")
        assertEquals(4, planned.notes.count { it.startsWith("design:") }, "every treatment keeps its reason: ${planned.notes}")
    }

    @Test
    fun aModelWrittenBeatSnapsToItsWords() {
        val draft = Score(scenes = listOf(SceneScore(beats = listOf(BeatScore(recipe = "slam", text = "پنجاه درصد")))))
        val snapped = StoryPlanner.snap(draft, words)
        assertEquals(4, snapped.scenes.single().beats.single().at, "a quoted beat lands on the word that says it")
    }

    // ---------------------------------------------------------------- fixtures

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private val words: List<Word> = timed(talk)

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

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }
}
