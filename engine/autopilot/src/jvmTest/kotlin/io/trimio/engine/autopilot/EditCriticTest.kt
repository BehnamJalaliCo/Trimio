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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The editorial critic: surprising hook, the word that carries the message, and where the tension sits. */
class EditCriticTest {

    @Test
    fun thePanelReadsHookMessageAndTension() {
        val words = timed(talk)
        val map = story()
        val score = Score(
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore(recipe = "mask-rise", at = 0, energy = 0.5f))),
                SceneScore(from = 4, beats = listOf(BeatScore(recipe = "meter", at = 4, energy = 0.7f))),
                SceneScore(from = 8, beats = listOf(BeatScore(recipe = "voucher", at = 8, until = 12, energy = 0.95f, label = "سیصد و پنجاه تتر"))),
                SceneScore(from = 13, beats = listOf(BeatScore(recipe = "flip", at = 13, energy = 0.6f))),
            ),
        )
        val report = EditCritic.review(score, words, map)
        assertTrue(report.surprisingHook, "setup first and a payoff in the map: ${report.findings}")
        assertTrue(report.virality.hook >= 8f, report.virality.summary())
        assertTrue(report.virality.trend >= 8f, "the callback earns the trend: ${report.virality.summary()}")
        assertEquals("پنجاه", report.messageWord, "the payoff's heaviest repeated word carries the message")
        val expected = words[4].range.startMs / 1000.0
        assertEquals(expected, assertNotNull(report.tensionAt).toDouble(), 0.05, "the tension sits on the turn")
    }

    @Test
    fun anEditWithNoGraphicsIsCalledOut() {
        val report = EditCritic.review(Score(), timed(talk))
        assertEquals(0f, report.virality.hook, "nothing lands — no hook")
        assertFalse(report.surprisingHook)
        assertTrue(report.findings.any { "no graphic" in it }, report.findings.joinToString())
        assertTrue(report.findings.any { "no payoff" in it }, "without a map the piece promises nothing: ${report.findings}")
    }

    @Test
    fun theFromScratchPassDropsWhatTheWordsDoNotCarry() {
        val map = story().copy(
            beats = story().beats + StoryBeat(kind = "turn", from = 4, until = 7, claim = ""),
        )
        val fresh = EditCritic.fresh(map, timed(talk))
        assertEquals(listOf("setup", "turn", "payoff", "callback"), fresh.beats.map { it.kind }, "a claimless beat is dropped, the grounded ones stay")
    }

    // ---------------------------------------------------------------- fixtures

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private fun story() = StoryMap(
        beats = listOf(
            StoryBeat(kind = "setup", from = 0, until = 3, claim = "امروز رفتم صرافی دیدم"),
            StoryBeat(kind = "turn", from = 4, until = 7, claim = "پنجاه درصد تخفیف"),
            StoryBeat(kind = "payoff", from = 8, until = 12, claim = "سیصد و پنجاه تتر"),
            StoryBeat(kind = "callback", from = 13, until = 17, claim = "همان اول گفتم"),
        ),
    )

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }
}
