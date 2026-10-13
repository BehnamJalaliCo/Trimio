package io.trimio.core.model

import io.trimio.core.model.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextKeyTest {
    private val zwnj = "‌"

    @Test
    fun digitsOfEveryScriptAreOneKey() {
        assertEquals("1404", TextKey.of("۱۴۰۴"))
        assertEquals("1404", TextKey.of("١٤٠٤"))
        assertEquals("1404", TextKey.of("1404"))
        assertEquals(TextKey.of("۲۵ درصد"), TextKey.of("25 درصد"))
    }

    @Test
    fun arabicFormsAndHamzaSeatsFoldToPersian() {
        assertEquals(TextKey.of("کتابی"), TextKey.of("كتابي"))
        assertEquals(TextKey.of("مسیول"), TextKey.of("مسئول"))
        assertEquals(TextKey.of("رویا"), TextKey.of("رؤیا"))
        assertEquals(TextKey.of("خانه"), TextKey.of("خانۀ"))
        assertEquals(TextKey.of("اینه"), TextKey.of("آینه"))
        assertEquals(TextKey.of("علم"), TextKey.of("عِلْم"))
        assertEquals(TextKey.of("رشد"), TextKey.of("رشـــد"))
    }

    @Test
    fun keyStaysCompactAcrossSpacesAndHalfSpaces() {
        assertEquals("میروم", TextKey.of("می${zwnj}روم"))
        assertEquals("میروم", TextKey.of("می روم"))
        assertEquals("rocketlaunch", TextKey.of("Rocket launch!"))
        assertEquals("50%", TextKey.of("50%"))
    }

    @Test
    fun phraseKeepsWordBoundariesForSearch() {
        assertEquals("قیمت بیت${zwnj}کوین 1404", TextKey.phrase("قیمتِ  بیت${zwnj}${zwnj}کوین، ۱۴۰۴؟"))
        // A half-space beside a space or punctuation is noise.
        assertEquals("کتاب ها", TextKey.phrase("کتاب$zwnj ها"))
        assertEquals("a b", TextKey.phrase("a b"))
        assertEquals("", TextKey.phrase(" «» "))
    }

    @Test
    fun termsIndexJoinedAndSplitForms() {
        val half = TextKey.terms("می${zwnj}روم")
        assertTrue("میروم" in half && "می" in half && "روم" in half, half.toString())
        val spaced = TextKey.terms("می روم")
        assertTrue("میروم" in spaced && "می" in spaced && "روم" in spaced, spaced.toString())
        val plural = TextKey.terms("کتاب ها")
        assertTrue("کتابها" in plural, plural.toString())
        assertEquals(TextKey.words("بیت${zwnj}کوین"), listOf("بیتکوین"))
        // Latin words are never glued.
        assertEquals(listOf("bitcoin", "price"), TextKey.terms("Bitcoin price"))
    }
}
