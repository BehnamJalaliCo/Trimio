package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The conservative rules hold: Arabic shapes fold to Persian ones, the space-based «می»/«نمی»
 * defect is repaired only where the join is certain, and everything else is left alone.
 */
class TextNormalizeTest {

    @Test
    fun arabicYehAndKafFoldToPersianForms() {
        // Glyph-identical pairs, so the expectations are spelled as escapes.
        assertEquals("\u0639\u0644\u06CC", TextNormalize.normalizeFa("\u0639\u0644\u064A"))
        assertEquals("\u06A9\u062A\u0627\u0628", TextNormalize.normalizeFa("\u0643\u062A\u0627\u0628"))
        assertEquals("\u06CC", TextNormalize.normalizeFa("\u0649"))
    }

    @Test
    fun presentationFormsDecompose() {
        assertEquals("\u06CC", TextNormalize.normalizeFa("\uFEF3"))
        assertEquals("\u06A9", TextNormalize.normalizeFa("\uFEDB"))
        assertEquals("\u0644\u0627", TextNormalize.normalizeFa("\uFEFB"))
        // Lam-alef with a hamza alef folds the alef like every other.
        assertEquals("\u0644\u0627", TextNormalize.normalizeFa("\uFEF7"))
    }

    @Test
    fun hamzaOnAlefFoldsConservatively() {
        assertEquals("\u0627\u0627\u0627", TextNormalize.normalizeFa("\u0623\u0625\u0671"))
        // «ؤ» and «ئ» are letters of their own and stay.
        assertEquals("\u0624\u0626", TextNormalize.normalizeFa("\u0624\u0626"))
    }

    @Test
    fun digitsBecomePersian() {
        assertEquals("\u06F1\u06F2\u06F3", TextNormalize.toPersianDigits("\u0661\u0662\u0663"))
        assertEquals("123", TextNormalize.toPersianDigits("123"))
        assertEquals("\u06F4\u06F56", TextNormalize.normalizeFa("\u0664\u06656"))
    }

    @Test
    fun diacriticsAreStripped() {
        assertEquals("\u0633\u0644\u0627\u0645", TextNormalize.normalizeFa("\u0633\u064E\u0644\u0627\u0645"))
        assertEquals("\u0634\u062F\u062A", TextNormalize.stripDiacritics("\u0634\u062F\u0651\u062A"))
    }

    @Test
    fun sentenceEndsAreStrippedFromBothEnds() {
        assertEquals("می\u200Cرود", TextNormalize.stripSentenceEnds("می\u200Cرود!"))
        // Interior punctuation — the comma that paces the line — stays.
        assertEquals("سلام، دنیا", TextNormalize.stripSentenceEnds("، سلام، دنیا!"))
    }

    @Test
    fun verbPrefixesJoinWithZwnj() {
        assertEquals("می\u200Cرود", TextNormalize.normalizeZwnj("می رود"))
        assertEquals("نمی\u200Cرود", TextNormalize.normalizeZwnj("نمی رود"))
        assertEquals("می\u200Cشود", TextNormalize.normalizeFa("می شود"))
        // The maksura spelling joins too and lands in its Persian form.
        assertEquals("می\u200Cرود", TextNormalize.normalizeZwnj("مى رود"))
        // Word-initial only: «کمی رود» ("a little river") keeps its space.
        assertEquals("کمی رود", TextNormalize.normalizeZwnj("کمی رود"))
    }

    @Test
    fun zwnjRunsAndSpacesTidy() {
        assertEquals("می\u200Cرود", TextNormalize.normalizeZwnj("می\u200C رود"))
        assertEquals("می\u200Cرود", TextNormalize.normalizeZwnj("می \u200C رود"))
        assertEquals("می\u200Cرود", TextNormalize.normalizeZwnj("می\u200C\u200Cرود"))
    }

    @Test
    fun zwnjDefectIsSpottedOnlyOnThePrefix() {
        assertTrue(TextNormalize.hasZwnjDefect("می رود"))
        assertTrue(TextNormalize.hasZwnjDefect("نمی شود"))
        assertFalse(TextNormalize.hasZwnjDefect("می\u200Cرود"))
        assertFalse(TextNormalize.hasZwnjDefect("کمی رود"))
        assertFalse(TextNormalize.hasZwnjDefect("می"))
        assertFalse(TextNormalize.hasZwnjDefect(""))
    }

    @Test
    fun whitespaceCollapsesAndTrims() {
        assertEquals("a b", TextNormalize.normalizeFa("a   b"))
        assertEquals("x", TextNormalize.normalizeFa("  x  "))
        // Two spaces stay: an intentional interword gap, not noise.
        assertEquals("a  b", TextNormalize.normalizeFa("a  b"))
    }

    @Test
    fun invisibleRemnantsGo() {
        assertEquals("می\u200Cرود", TextNormalize.normalizeFa("م\u200Dی\u200E ر\u200Fود"))
    }

    @Test
    fun emptyAndBlankComeBackEmpty() {
        assertEquals("", TextNormalize.normalizeFa(""))
        assertEquals("", TextNormalize.normalizeFa("   "))
        assertEquals("", TextNormalize.normalizeFa("!!!"))
        assertEquals("", TextNormalize.normalizeZwnj(""))
        assertFalse(TextNormalize.hasZwnjDefect(""))
    }
}
