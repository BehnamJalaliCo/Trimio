package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextNormalizeTest {

    @Test
    fun presentationFormsAFoldLikeFormsB() {
        // Forms-A peh (FB56) and the precomposed peh land in the same Persian letter.
        assertEquals(
            TextNormalize.normalizeFa("\u067E"),
            TextNormalize.normalizeFa("\uFB56"),
            "Forms-A peh must fold like its base letter",
        )
        // Forms-A farsi-yeh shapes fold exactly like Arabic yeh does.
        assertEquals("ی", TextNormalize.normalizeFa("\uFBFC"), "Forms-A farsi yeh isolated")
        assertEquals("ی", TextNormalize.normalizeFa("\uFBFF"), "Forms-A farsi yeh medial")
        assertEquals("ی", TextNormalize.normalizeFa("\u064A"), "Arabic yeh")
        // Forms-A keheh/gaf/tcheh/jeh keep their dotted identity via the precomposed letter.
        assertEquals("ک", TextNormalize.normalizeFa("\uFB91"), "Forms-A keheh medial → Persian keheh")
        assertEquals("گ", TextNormalize.normalizeFa("\uFB92"), "Forms-A gaf isolated")
        assertEquals("چ", TextNormalize.normalizeFa("\uFB7A"), "Forms-A tcheh isolated")
        assertEquals("ژ", TextNormalize.normalizeFa("\uFB8A"), "Forms-A jeh isolated")
        // Ligatures decompose to their letters, then fold like any other.
        assertEquals("کا", TextNormalize.normalizeFa("\uFC37"), "Forms-A kaf-alef → Persian keheh + alef")
        // Alef maksura initial (FBE8) folds to Persian yeh, same as a bare alef maksura.
        assertEquals("ی", TextNormalize.normalizeFa("\uFBE8"), "Forms-A alef maksura initial")
        assertEquals("ی", TextNormalize.normalizeFa("\u0649"), "bare alef maksura")
    }

    @Test
    fun nuktaIsKeptOnALetterAndDroppedAsAMark() {
        // The dots of a Persian letter are the letter — never stripped.
        assertEquals("پ", TextNormalize.normalizeFa("\uFB56"), "Forms-A peh keeps its nukta as letter identity")
        assertEquals("چ", TextNormalize.normalizeFa("چ"), "precomposed tcheh keeps its dots")
        assertEquals("گ", TextNormalize.normalizeFa("گ"), "precomposed gaf keeps its dots")
        // A loose combining nukta/hamza is a diacritic and goes.
        assertEquals("ب", TextNormalize.normalizeFa("ب\u0654"), "combining hamza above is a mark")
        assertEquals("ب", TextNormalize.normalizeFa("ب\u0655"), "combining hamza below is a mark")
        assertEquals("ب", TextNormalize.normalizeFa("ب\u0658"), "combining nukta is a mark")
        assertEquals("ب", TextNormalize.normalizeFa("ب\u064E"), "fatha is a mark")
        // The vowel-mark path stays callable on its own.
        assertEquals("ب", TextNormalize.stripDiacritics("ب\u0658"), "stripDiacritics drops the nukta mark")
        assertEquals("پ", TextNormalize.stripDiacritics("پ"), "stripDiacritics never touches a letter's dots")
    }

    @Test
    fun persianDigitsAndThousandsSeparator() {
        assertEquals("۱۲۳", TextNormalize.toPersianDigits("\u0661\u0662\u0663"), "Arabic-Indic → Persian digits")
        assertEquals("123", TextNormalize.toPersianDigits("123"), "Latin digits stay as they are")
        assertEquals("۱٬۰۰۰", TextNormalize.toPersianDigits("\u0661\u066C\u0660\u0660\u0660"), "U+066C is preserved")
        assertEquals("۱٬۰۰۰", TextNormalize.toPersianDigits("\u0661\u060C\u0660\u0660\u0660"), "Arabic comma between digits → U+066C")
        assertEquals("۱٬۰۰۰", TextNormalize.toPersianDigits("\u0661,\u0660\u0660\u0660"), "ASCII comma between digits → U+066C")
        assertEquals("1٬234", TextNormalize.toPersianDigits("1\u066C234"), "U+066C between Latin digits is preserved")
        // Decimal separator and a comma outside a number are not thousands separators.
        assertEquals("۵٫۲", TextNormalize.toPersianDigits("\u0665\u066B\u0662"), "U+066B decimal stays")
        assertEquals("hello, world", TextNormalize.toPersianDigits("hello, world"), "comma outside digits is untouched")
        assertEquals(
            "قیمت ۱٬۰۰۰ تومان",
            TextNormalize.normalizeFa("قیمت \u0661,\u0660\u0660\u0660 تومان"),
            "normalize folds digits and the separator together",
        )
        assertEquals(
            "قیمت 1٬000 تومان",
            TextNormalize.normalizeFa("قیمت 1,000 تومان"),
            "separator folds while Latin digits stay Latin",
        )
    }

    @Test
    fun zwnjIsRepairedWhereCertainAndNeverBroken() {
        assertEquals("می‌شود", TextNormalize.normalizeZwnj("می شود"), "«می شود» → «می‌شود»")
        assertEquals("می‌رود", TextNormalize.normalizeZwnj("می‌رود"), "the joined form is already correct")
        assertEquals("کمی رود", TextNormalize.normalizeZwnj("کمی رود"), "«کمی رود» is two words")
        assertFalse(TextNormalize.hasZwnjDefect("می‌رود"), "joined form has no defect")
        assertTrue(TextNormalize.hasZwnjDefect("می رود"), "spaced prefix is the defect")
        // A ZWNJ that is already in the text survives normalizeFa.
        assertTrue(TextNormalize.normalizeFa("می‌خواهد").contains('\u200C'), "ZWNJ survives normalization")
    }

    @Test
    fun transcriptSpellingStaysConservative() {
        // Latin, interior commas and ordinary letters pass through untouched.
        assertEquals("hello world", TextNormalize.normalizeFa("hello world"), "Latin text is left alone")
        assertEquals("یک، دو", TextNormalize.normalizeFa("یک، دو"), "interior Persian comma paces the line")
        assertEquals("می‌رود", TextNormalize.normalizeFa("می‌رود!"), "sentence-final mark goes from the end only")
    }
}
