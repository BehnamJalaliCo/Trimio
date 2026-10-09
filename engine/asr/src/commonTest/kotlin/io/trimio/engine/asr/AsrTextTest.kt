package io.trimio.engine.asr

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsrTextTest {

    private fun token(text: String, start: Long, end: Long, p: Float = 0.9f) =
        RecognizedToken(text.encodeToByteArray(), start, end, p)

    @Test
    fun normalizerFixesArabicLettersAndDiacritics() {
        assertEquals("کیفیت عالی است", PersianNormalizer.normalizeChars("كيفي\u064E\u0651ت عالي است"))
        assertEquals("۱۲۳", PersianNormalizer.normalizeChars("١٢٣"))
        assertEquals("بیت\u200Cکوین", PersianNormalizer.normalizeChars("بیت\u200Cکوین")) // ZWNJ preserved
    }

    @Test
    fun punctuationBecomesPersian() {
        assertEquals("خوبه؟ آره، خیلی.", PersianNormalizer.punctuation("خوبه ? آره , خیلی ."))
    }

    @Test
    fun assemblerJoinsPersianLettersSplitAcrossTokens() {
        // «سود» split in the middle of the UTF-8 bytes of «و» (2 bytes: D9 88).
        val bytes = " سود".encodeToByteArray()
        val tokens = listOf(
            RecognizedToken(bytes.copyOfRange(0, 4), 0, 100, 0.9f),
            RecognizedToken(bytes.copyOfRange(4, bytes.size), 100, 300, 0.7f),
        )
        val words = WordAssembler.assemble(tokens, Language.Persian)
        assertEquals(listOf("سود"), words.map { it.text })
        assertEquals(TimeRange(0, 300), words[0].range)
        assertEquals(0.8f, words[0].confidence, 1e-4f)
    }

    @Test
    fun assemblerJoinsAffixesWithZwnjAndMergesTimings() {
        val tokens = listOf(
            token(" ما", 0, 200), token(" می", 220, 350), token(" خواهیم", 360, 700),
            token(" کتاب", 720, 1000), token(" ها", 1010, 1200), token(" را", 1220, 1400), token(".", 1400, 1420),
        )
        val words = WordAssembler.assemble(tokens, Language.Persian)
        assertEquals(listOf("ما", "می\u200Cخواهیم", "کتاب\u200Cها", "را."), words.map { it.text })
        assertEquals(TimeRange(220, 700), words[1].range)
        assertEquals(TimeRange(720, 1200), words[2].range)
    }

    @Test
    fun assemblerDetectsLanguagePerWordAndKeepsOrder() {
        val tokens = listOf(token(" قیمت", 0, 300), token(" BTC", 250, 600), token(" بالا", 620, 900))
        val words = WordAssembler.assemble(tokens, Language.Persian)
        assertEquals(listOf(Language.Persian, Language.English, Language.Persian), words.map { it.language })
        assertTrue(words.zipWithNext().all { (a, b) -> a.range.endMs <= b.range.startMs }, "overlap must be removed")
    }

    @Test
    fun fillersAreFound() {
        fun w(t: String) = Word(t, TimeRange(0, 100), language = Language.Persian)
        assertEquals(listOf(1, 3), FillerDetector.fillerIndices(listOf(w("خب"), w("ا\u0650"), w("امروز"), w("um,"))))
    }

    @Test
    fun alignerSnapsLateAndEarlyBoundariesToOnsets() {
        // Energy track: speech at 300-700 ms and 900-1400 ms, silence elsewhere.
        val hop = 10
        val frames = 200
        val energy = FloatArray(frames) { f ->
            val t = f * hop
            if (t in 300 until 700 || t in 900 until 1400) -20f else -60f
        }
        val features = AudioFeatures(hop, energy, FloatArray(frames), -20f, 0f, emptyList())
        // Recogniser guesses are 80 ms late and 60 ms early.
        val words = listOf(
            Word("یک", TimeRange(380, 760), language = Language.Persian),
            Word("دو", TimeRange(840, 1460), language = Language.Persian),
        )
        val aligned = WordAligner().align(words, features)
        assertTrue(abs(aligned[0].range.startMs - 300) <= 10, "start ${aligned[0].range}")
        assertTrue(abs(aligned[0].range.endMs - 700) <= 10, "end ${aligned[0].range}")
        assertTrue(abs(aligned[1].range.startMs - 900) <= 10, "start ${aligned[1].range}")
        assertTrue(abs(aligned[1].range.endMs - 1400) <= 10, "end ${aligned[1].range}")
    }
}
