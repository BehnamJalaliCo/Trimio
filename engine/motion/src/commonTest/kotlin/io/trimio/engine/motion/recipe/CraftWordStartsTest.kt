package io.trimio.engine.motion.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

class CraftWordStartsTest {

    @Test
    fun wordStartsComeFromWordTimesNotFromAStagger() {
        val spoken = listOf(1.2f, 1.8f, 2.5f)
        val starts = Craft.wordStarts(cue(spoken), count = 3, stagger = 0.1f)
        for (i in spoken.indices) {
            assertEquals(spoken[i] - Craft.LEAD, starts[i], TOL, "word $i lands on its spoken time (minus the entrance lead), not on a machine rhythm")
        }
    }

    @Test
    fun wordStartsFallBackToAnEvenStaggerWhenNothingWasSpoken() {
        for (wordTimes in listOf(null, emptyList())) {
            val starts = Craft.wordStarts(cue(wordTimes), count = 3, stagger = 0.12f)
            for (i in 0 until 3) {
                assertEquals(AT - Craft.LEAD + i * 0.12f, starts[i], TOL, "word $i staggers evenly from the beat when wordTimes is $wordTimes")
            }
        }
    }

    @Test
    fun partialWordTimesKeepTheRealOnesAndContinueTheTail() {
        val spoken = listOf(1.2f, 1.8f)
        val starts = Craft.wordStarts(cue(spoken), count = 4, stagger = 0.1f)
        assertEquals(spoken[0] - Craft.LEAD, starts[0], TOL, "the known words keep their spoken times")
        assertEquals(spoken[1] - Craft.LEAD, starts[1], TOL, "the known words keep their spoken times")
        assertEquals(spoken[1] - Craft.LEAD + 0.1f, starts[2], TOL, "the tail continues at the stagger cadence after the last spoken word")
        assertEquals(spoken[1] - Craft.LEAD + 0.2f, starts[3], TOL, "the tail continues at the stagger cadence after the last spoken word")
    }

    private val AT = 1.0f
    private val TOL = 1e-4f

    private fun cue(wordTimes: List<Float>?) = Cue(
        text = "یک دو سه",
        at = AT,
        out = 4f,
        x = 540f,
        y = 540f,
        width = 1080f,
        height = 1080f,
        wordTimes = wordTimes,
    )
}
