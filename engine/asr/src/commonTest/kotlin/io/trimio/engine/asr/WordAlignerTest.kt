package io.trimio.engine.asr

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordAlignerTest {

    @Test
    fun onsetSnapsToTheFirstVoicedFrameInsideTheWindow() {
        val features = features(FloatArray(FRAMES) { i -> if (i in 5..15) LOUD_DB else QUIET_DB })
        val aligned = WordAligner().align(listOf(word("hello", 30L, 120L)), features)
        assertEquals(50L, aligned.single().range.startMs, "the word starts on the voice's onset (50 ms), not on the recogniser's guess")
    }

    @Test
    fun alignedWordsStayOrderedAndNeverOverlap() {
        val features = features(FloatArray(FRAMES) { QUIET_DB })
        val words = listOf(word("first", 100L, 200L), word("second", 150L, 300L))
        val aligned = WordAligner().align(words, features)
        assertEquals(words.size, aligned.size, "every word survives alignment")
        for ((a, b) in aligned.zipWithNext()) {
            assertTrue(a.range.startMs <= b.range.startMs, "words keep their order: ${a.text} then ${b.text}")
            assertTrue(a.range.endMs <= b.range.startMs, "words never overlap: ${a.text} ends ${a.range.endMs}, ${b.text} starts ${b.range.startMs}")
        }
        for (w in aligned) {
            assertTrue(w.range.startMs < w.range.endMs, "${w.text} keeps a positive duration")
        }
    }

    @Test
    fun outputIsMonotonicWhenOnsetsPullWordsTogether() {
        val features = features(FloatArray(FRAMES) { i -> if (i in 3..20) LOUD_DB else QUIET_DB })
        val words = listOf(word("one", 40L, 80L), word("two", 90L, 140L), word("three", 150L, 220L))
        val aligned = WordAligner().align(words, features)
        assertTrue(aligned.zipWithNext().all { (a, b) -> a.range.startMs <= b.range.startMs }, "starts never run backwards")
        assertTrue(aligned.zipWithNext().all { (a, b) -> a.range.endMs <= b.range.startMs }, "a word never begins before the previous one ends")
        assertTrue(aligned.all { it.range.endMs - it.range.startMs >= MIN_WORD_MS }, "no word is squeezed out of existence")
    }

    private const val FRAMES = 25
    private const val QUIET_DB = -50f
    private const val LOUD_DB = -10f
    private const val MIN_WORD_MS = 60L

    private fun features(energy: FloatArray) = AudioFeatures(
        hopMs = 10,
        energyDb = energy,
        pitchHz = FloatArray(energy.size),
        integratedLufs = -16f,
        appliedGainDb = 0f,
        speech = emptyList(),
    )

    private fun word(text: String, startMs: Long, endMs: Long) =
        Word(text = text, range = TimeRange(startMs, endMs), language = Language.English)
}
