package io.trimio.engine.autopilot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WrittenFormsTest {
    private val trading = "روزی ده تا سیگنال می‌دیم و استاپ نمی‌خوره کدوم تیپی سود داشت".split(' ')

    @Test
    fun aSpokenSpellingIsWrittenAsItIsWritten() {
        assertEquals("دلار،", WrittenForms.of("دولار،", listOf("پنجاه", "دولار،")))
        assertNull(WrittenForms.of("دلار", listOf("دلار")))
    }

    @Test
    fun aTradeTipIsATipOnlyInATradingPiece() {
        assertEquals("تیپ", WrittenForms.of("تیپی", trading))
        assertEquals("تیپ", WrittenForms.of("تی‌پی", trading))
        // «یه تیپی زده بود» about clothes stays as said.
        assertNull(WrittenForms.of("تیپی", "امروز یه تیپی زده بود که همه دیدن".split(' ')))
    }

    @Test
    fun aSiteHeardHalfInPersianIsTheBriefsSite() {
        assertEquals("coinepro.com،", WrittenForms.of("کوینپرو.com،", emptyList(), listOf("coinepro.com")))
        assertNull(WrittenForms.of("نوبیتکس.com", emptyList(), listOf("coinepro.com")))
    }

    @Test
    fun theReadingWritesTheBriefsSiteAsTheBriefDoes() {
        val read = Understanding(
            entities = listOf(Entity(at = 3, name = "CoinPro.com")),
            lines = listOf(LineRead(title = "همه چیز در CoinPro.com", items = listOf("CoinPro.com"))),
        )
        val fixed = Glossary.read("اسم سایت ما coinepro.com هست").respell(read)
        assertEquals("coinepro.com", fixed.entities.single().name)
        assertEquals("همه چیز در coinepro.com", fixed.lines.single().title)
        assertEquals(listOf("coinepro.com"), fixed.lines.single().items)
    }

    @Test
    fun aWordMisheardByItsFirstLetterIsTheOneThePieceKeepsSaying() {
        val said = "ورودیشم دو میلیون تومنه کلن پنجاه تتر ورودی مونه اصلا به ما پولی نمی‌دی برودیمون رایگانه".split(' ')
        assertEquals("ورودیمون", WrittenForms.of("برودیمون", said))
        // A word said once among others like it stays: no other stem is said twice.
        assertNull(WrittenForms.of("بارون", "امروز بارون اومد و همه جا خیس شد".split(' ')))
    }
}
