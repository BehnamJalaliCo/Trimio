package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KashidaTest {

    private val tatweel = '\u0640'
    private val zwnj = '\u200C'

    @Test
    fun kashidaReallyWidensTheLineUnderAProbeMeasurer() {
        val probe = probeMeasure()
        val line = "کتاب باران خانه"
        val before = probe(line)
        val target = before + 30f
        val stretched = Kashida.justifyToWidth(line, target, probe)
        val after = probe(stretched)
        assertTrue(after > before, "kashida must widen the line under the probe: $before → $after")
        assertTrue(after >= target, "the loop runs until the probe sees the target: $after < $target")
        assertTrue(stretched.count { it == tatweel } >= 1, "at least one tatweel is inserted")
    }

    @Test
    fun stretchingStopsOnceTheProbeSeesTheTarget() {
        val probe = probeMeasure()
        val line = "کتاب باران خانه سلامت"
        // One tatweel is 10 units on this probe — ask for exactly that much more.
        val target = probe(line) + 10f
        val stretched = Kashida.justifyToWidth(line, target, probe)
        assertEquals(1, stretched.count { it == tatweel }, "a 10-unit shortfall needs one tatweel on this probe")
        assertTrue(probe(stretched) >= target, "measured width reaches the target")
    }

    @Test
    fun theFirstOpeningLandsInTheMiddleOfTheLine() {
        val probe = probeMeasure()
        val line = "کتاب باران خانه"
        val stretched = Kashida.justifyToWidth(line, probe(line) + 10f, probe)
        val words = stretched.split(' ')
        assertEquals(3, words.size, "word count is unchanged")
        assertEquals(0, words[0].count { it == tatweel }, "first word stays clean on an early stop")
        assertEquals(1, words[1].count { it == tatweel }, "middle word takes the first opening — even spread")
        assertEquals(0, words[2].count { it == tatweel }, "third word stays clean on an early stop")
    }

    @Test
    fun atMostOneTatweelPerWord() {
        val probe = probeMeasure()
        val line = "کتابخانه بارانی خانگی سلامتی"
        // Ask for far more than the ceilings allow.
        val stretched = Kashida.justifyToWidth(line, probe(line) + 500f, probe)
        for (word in stretched.split(' ')) {
            assertTrue(
                word.count { it == tatweel } <= 1,
                "word '$word' took more than one tatweel",
            )
        }
    }

    @Test
    fun zwnjCompoundsAreNeverBroken() {
        val probe = probeMeasure()
        val zwnjLine = "می‌رود می‌خواهد"
        val untouched = Kashida.justifyToWidth(zwnjLine, probe(zwnjLine) + 80f, probe)
        assertEquals(zwnjLine, untouched, "ZWNJ compounds take no tatweel and stay whole")
        assertTrue(untouched.contains(zwnj), "ZWNJ survives")
        assertFalse(untouched.contains(tatweel), "no tatweel is forced into a ZWNJ word")

        val mixed = "می‌رود کتاب باران"
        val stretched = Kashida.justifyToWidth(mixed, probe(mixed) + 20f, probe)
        val first = stretched.split(' ').first()
        assertFalse(first.contains(tatweel), "the ZWNJ word is skipped even on a mixed line")
        assertTrue(stretched.contains(zwnj), "ZWNJ survives on a mixed line")
        assertTrue(stretched.count { it == tatweel } >= 1, "the plain words still stretch")
    }

    @Test
    fun ltrLinesAndTinyRatiosAreLeftAlone() {
        val probe = probeMeasure()
        assertEquals("hello world", Kashida.justifyToWidth("hello world", 400f, probe), "LTR is never stretched")
        val line = "کتاب باران"
        assertEquals(line, Kashida.justifyWithKashida(line, 1.0f), "ratio at 1 is noise")
        assertEquals(line, Kashida.justifyWithKashida(line, 1.01f), "ratio at 1.01 is noise")
    }

    @Test
    fun ratioFormStretchesUnderTheDefaultAdvanceModel() {
        val line = "کتاب باران خانه"
        val stretched = Kashida.justifyWithKashida(line, 1.2f)
        assertTrue(stretched.count { it == tatweel } >= 1, "the ratio form still opens joins")
        assertTrue(stretched.length > line.length, "the ratio form widens the line")
        assertFalse(stretched.contains(zwnj), "no phantom ZWNJ appears")
    }

    @Test
    fun stretchableSpansListEveryLegalJoin() {
        val spans = Kashida.stretchableSpans("کتاب")
        assertTrue(spans.isNotEmpty(), "کتاب has eligible joins")
        for (i in spans) {
            assertTrue(i in 1 until "کتاب".length, "span $i sits strictly inside the word")
        }
        assertTrue(Kashida.stretchableSpans("می‌رود").isEmpty(), "ZWNJ compounds expose no span")
        assertTrue(Kashida.stretchableSpans("hello").isEmpty(), "LTR exposes no span")
    }

    private fun probeMeasure(unit: Float = 10f): (String) -> Float = { s -> s.length * unit }
}
