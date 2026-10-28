package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The keep-alive pass on the energy curve: the gap follows the energy, and a strike keeps its silence. */
class RhythmEnergyTest {

    @Test
    fun aHotStretchTakesAnEventBeforeACalmOne() {
        val words = paced(8)
        val lines = Lines.split(words)
        val hot = mutableListOf(SceneScore(from = 0), SceneScore(from = 5))
        rhythm(words, lines, energy = 1f).keepAlive(hot)
        assertTrue(hot.size > 2 || hot.any { it.beats.isNotEmpty() }, "a 2.5s quiet stretch at full energy gets its event: $hot")
        val cold = mutableListOf(SceneScore(from = 0), SceneScore(from = 5))
        rhythm(words, lines, energy = 0f).keepAlive(cold)
        assertEquals(2, cold.size, "the same stretch at low energy can hold its breath: $cold")
        assertEquals(0, cold.sumOf { it.beats.size }, "nothing was added at low energy: $cold")
    }

    @Test
    fun theSilenceBeforeAStrikeIsLeftEmpty() {
        val words = timed()
        val lines = Lines.split(words)
        val strike = BeatScore(recipe = "voucher", at = 13, until = 17, energy = 0.95f, hold = 3f)
        val scenes = mutableListOf(SceneScore(from = 0, beats = listOf(strike)))
        rhythm(words, lines, energy = 0.5f).keepAlive(scenes)
        val at = words[13].range.startMs / 1000f
        val silence = (at - EnergyCurve.SILENCE)..at
        val added = scenes.flatMap { it.beats }.filter { it !== strike }
        assertTrue(
            added.none { b -> b.at?.let { words[it].range.startMs / 1000f in silence } == true },
            "no event lands in the ${EnergyCurve.SILENCE}s of silence before the strike: ${added.map { it.at }}",
        )
        assertTrue(
            scenes.drop(1).none { sc -> sc.from?.let { words[it].range.startMs / 1000f in silence } == true },
            "and no cut splits it: ${scenes.map { it.from }}",
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun rhythm(words: List<Word>, lines: List<Lines.Line>, energy: Float) = Rhythm(
        words, lines, List(lines.size) { LineRead(show = "none", energy = energy) }, Understanding(), Taste(), Rng(7),
        energy, mutableListOf(), mutableSetOf(), null,
    ) { _, _ -> null }

    /** [n] words 0.5s apart: one line, and a 2.5s quiet stretch in the first scene's five words. */
    private fun paced(n: Int): List<Word> =
        (0 until n).map { i -> Word("w$i", TimeRange(300L + i * 500L, 300L + i * 500L + 420L), language = Language.Persian) }

    private val talk = listOf(
        "امروز رفتم صرافی دیدم.",
        "پنجاه درصد تخفیف داشت.",
        "سیصد و پنجاه تتر دادند.",
        "همان اول گفتم مواظب باشید.",
    )

    private fun timed(): List<Word> {
        var t = 300L
        return talk.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }
}
