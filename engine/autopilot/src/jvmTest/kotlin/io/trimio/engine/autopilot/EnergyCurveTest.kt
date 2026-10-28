package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hierarchical rhythm: silence before the strike, and an event gap that follows the energy. */
class EnergyCurveTest {

    @Test
    fun aBigStrikeEarnsItsSilence() {
        val words = timed(talk)
        val score = Score(
            scenes = listOf(
                SceneScore(
                    from = 0,
                    beats = listOf(
                        BeatScore(recipe = "mask-rise", at = 0, energy = 0.5f),
                        BeatScore(recipe = "voucher", at = 13, energy = 0.95f),
                    ),
                ),
            ),
        )
        val calm = EnergyCurve.calm(score, words)
        assertEquals(1, calm.size, "only the strike earns silence")
        val at = words[13].range.startMs / 1000f
        assertTrue(EnergyCurve.inCalm(at - 1f, calm), "the second before the strike is calm")
        assertTrue(EnergyCurve.inCalm(at, calm), "the calm closes on the strike itself")
        assertFalse(EnergyCurve.inCalm(at + 0.5f, calm), "after the strike the edit moves again")
        assertTrue(EnergyCurve.SILENCE in 3f..4f, "the craft keeps 3–4s of silence before a strike")
        assertEquals(
            (at - EnergyCurve.SILENCE).coerceAtLeast(0f).toDouble(),
            calm[0].start.toDouble(),
            0.01,
            "the window opens one silence-length before the strike",
        )
    }

    @Test
    fun theEventGapFollowsTheEnergy() {
        assertTrue(EnergyCurve.gap(0.9f) < EnergyCurve.gap(0.2f), "a hot stretch gets its events sooner")
        assertTrue(EnergyCurve.gap(1f) >= 1.8f && EnergyCurve.gap(0f) <= 4.5f, "the gap stays inside the craft's band")
    }

    // ---------------------------------------------------------------- fixtures

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }
}
