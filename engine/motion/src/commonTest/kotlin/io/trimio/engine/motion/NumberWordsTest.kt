package io.trimio.engine.motion

import io.trimio.engine.motion.score.NumberWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NumberWordsTest {
    private fun all(s: String) = NumberWords.findAll(s.split(' '))

    @Test
    fun aListOfNumbersIsNotASum() {
        // Shoe sizes 42 and 43, not 85.
        assertEquals(listOf(42.0, 43.0), all("سایز چهل و دو و چهل و سه داره تموم می‌شه").map { it.value })
        assertEquals(listOf(5.0, 6.0), all("پنج شش تا").map { it.value })
        // Composite numbers still add up, place by place.
        assertEquals(125.0, all("صد و بیست و پنج متره").single().value)
        assertEquals(1374.0, all("سال هزار و سیصد و هفتاد و چهار").single().value)
        assertEquals(1_200_000.0, all("یک میلیون و دویست هزار تومن").single().value)
        assertEquals(650_000.0, all("ششصد و پنجاه هزار تومن").single().value)
    }

    @Test
    fun aLoneSadIsAHundredOnlyWhereANumberIs() {
        assertEquals(125.0, all("سد و بیست و پنج متره").single().value)
        assertEquals(100_000.0, all("سد هزار دلار").single().value)
        assertTrue(all("پشت سد کرج").isEmpty())
        assertTrue(all("سد و نیروگاه").isEmpty())
    }

    @Test
    fun yearsAreFlagged() {
        assertTrue(all("تو سال ۱۴۰۱ به پنجاه و دو درصد رسید").first().year)
        assertTrue(all("between 2022 and 2024").all { it.year })
        assertTrue(all("از سال هزار و سیصد و هفتاد و چهار").single().year)
        assertTrue(all("سال نود و هفت گوشت ارزون بود").single().year)
        assertFalse(all("قیمتش ۱۴۰۰ تومن بود").single().year, "an amount")
        assertFalse(all("We made just 5,000 units").single().year)
        assertFalse(all("2000 people came").single().year)
        assertFalse(all("نود و هفت درصد").single().year)
    }

    @Test
    fun percentWords() {
        assertTrue(all("پنجاه درصده").single().percent)
        assertTrue(all("up to 7 per cent of revenue").single().let { it.percent && it.count == 3 })
        assertTrue(all("بیست درصدش رو بذار کنار").single().percent)
        assertNull(all("درصد").firstOrNull())
    }
}
