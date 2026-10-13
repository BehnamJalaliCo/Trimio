package io.trimio.engine.asr

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import kotlin.test.Test
import kotlin.test.assertEquals

class SecondOpinionTest {
    private fun w(text: String, s: Long, c: Float) = Word(text, TimeRange(s, s + 300), c, Language.Persian)

    private val words = listOf(w("این", 0, 0.9f), w("ریپو", 300, 0.4f), w("رو", 600, 0.9f), w("به", 900, 0.9f), w("کلادکد", 1200, 0.3f), w("بدی", 1500, 0.95f))

    @Test
    fun onlyUnsureWordsHeardDifferentlyBecomeAlternatives() {
        val alts = SecondOpinion.candidates(words, "این ریپو رو به کلود بدی،")
        assertEquals(listOf(Alternative(4, "کلود", 0.3f)), alts)
    }

    @Test
    fun spansCoverUnsureWordsAndMerge() {
        assertEquals(listOf(0L..2100L), SecondOpinion.spans(words))
        assertEquals(emptyList(), SecondOpinion.spans(words, threshold = 0.1f))
    }
}
