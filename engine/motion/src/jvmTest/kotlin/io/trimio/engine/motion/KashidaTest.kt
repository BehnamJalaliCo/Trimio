package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TATWEEL = '\u0640'
private const val ZWNJ = '\u200C'

/**
 * Kashiida must stay conservative: one tatweel per word, never splitting ZWNJ compounds, and a
 * hard line cap. A line that silently gains tatweel beside spaces or at the edges reads broken
 * on screen and no layout test sees it.
 */
class KashidaTest {

    @Test
    fun isRtlTextFollowsTheFirstStrongCharacter() {
        assertTrue(Kashida.isRtlText("سلام"))
        assertTrue(Kashida.isRtlText("کتاب خوب"))
        assertFalse(Kashida.isRtlText("Hello"))
        assertFalse(Kashida.isRtlText("Trimio"))
        // Mixed: the first strong character decides.
        assertTrue(Kashida.isRtlText("سلام Hello"))
        assertFalse(Kashida.isRtlText("Hello سلام"))
        // Weak characters never decide.
        assertTrue(Kashida.isRtlText("۱۲۳ سلام"))
        assertFalse(Kashida.isRtlText("123"))
        assertFalse(Kashida.isRtlText(""))
    }

    @Test
    fun justifyReturnsInputWhenNotStretched() {
        val line = "کتاب خوب است"
        assertEquals(line, Kashida.justifyWithKashida(line, 1.02f))
        assertEquals(line, Kashida.justifyWithKashida(line, 1.0f))
        assertEquals(line, Kashida.justifyWithKashida(line, 0.9f))
    }

    @Test
    fun justifyReturnsInputWhenNotRtl() {
        val line = "hello world"
        assertEquals(line, Kashida.justifyWithKashida(line, 1.5f))
    }

    @Test
    fun justifyInsertsAtMostOneTatweelPerWordAndRespectsTheCap() {
        val line = "کتاب سلام دنیا بسیار خوب است جهان امروز"
        val result = Kashida.justifyWithKashida(line, 2.0f)
        assertTrue(result != line, "a large stretch must insert tatweel")
        val cap = maxOf(1, line.length / 12)
        assertTrue(result.count { it == TATWEEL } <= cap, "line cap exceeded")
        for (word in result.split(' ')) {
            assertTrue(word.count { it == TATWEEL } <= 1, "word «$word» carries more than one tatweel")
        }
    }

    @Test
    fun justifyIsDeterministic() {
        val line = "کتاب خوب است جهان"
        assertEquals(
            Kashida.justifyWithKashida(line, 1.5f),
            Kashida.justifyWithKashida(line, 1.5f),
        )
    }

    @Test
    fun justifyNeverPutsTatweelAtEdgesOrBesideSpaces() {
        val line = "کتاب سلام دنیا بسیار خوب است جهان امروز"
        val result = Kashida.justifyWithKashida(line, 2.0f)
        for (i in result.indices) {
            if (result[i] != TATWEEL) continue
            assertTrue(i > 0 && i < result.lastIndex, "tatweel at line edge")
            assertTrue(result[i - 1] != ' ' && result[i + 1] != ' ', "tatweel beside a space")
        }
    }

    @Test
    fun stretchableSpansAreNonEmptyForAPersianLine() {
        val spans = Kashida.stretchableSpans("کتاب خوب است")
        assertTrue(spans.isNotEmpty())
        assertTrue(Kashida.stretchableSpans("hello").isEmpty())
    }

    @Test
    fun emptyAndTinyWordsStayUntouched() {
        assertEquals("", Kashida.justifyWithKashida("", 1.5f))
        assertEquals("ا ب", Kashida.justifyWithKashida("ا ب", 1.5f))
        assertEquals("در راه", Kashida.justifyWithKashida("در راه", 2.0f))
    }

    @Test
    fun zwnjWordIsNotSplit() {
        val word = "می‌رود"
        val result = Kashida.justifyWithKashida(word, 2.0f)
        assertEquals(word, result)
        assertTrue(result.contains(ZWNJ))
        assertEquals(emptyList(), Kashida.stretchableSpans(word))
    }

    @Test
    fun singleWordTakesAtMostOneTatweel() {
        val result = Kashida.justifyWithKashida("کتاب", 2.0f)
        assertTrue(result.contains(TATWEEL))
        assertEquals(1, result.count { it == TATWEEL })
    }
}
