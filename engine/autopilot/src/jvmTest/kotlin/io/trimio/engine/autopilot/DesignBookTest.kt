package io.trimio.engine.autopilot

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor's memory: a new line recalls how lines that do the same thing were shown, never a show it cannot fill. */
class DesignBookTest {
    private val book = runBlocking { DesignBook.load() }

    private fun recall(line: String) = DesignBook.recall(line.split(' '), book, k = 2)

    @Test
    fun theBookLoadsAndEveryShowIsOneTheDirectorKnows() {
        assertTrue(book.size >= 50, "${book.size} moments")
        val unknown = book.filter { it.show !in Understanding.SHOWS }
        assertTrue(unknown.isEmpty(), "shows the director cannot name: ${unknown.map { it.show }}")
    }

    @Test
    fun aLineRecallsMomentsThatDoWhatItDoes() {
        // A fee said in a line no one has written down before: a price, not a headline.
        assertEquals("price-tag", recall("هزینه دوره فقط سیصد هزار تومنه").first().show)
        // The ask at the end: the comment field.
        assertEquals("comment", recall("کلمه آموزش رو برام کامنت کن").first().show)
        // Someone else's words: a message.
        assertTrue(recall("یکی از شاگردام پرسید چطوری انگیزه بگیرم").any { it.show == "message" })
        // A line with no figure never recalls a counter, a ring or a price.
        val plain = recall("این روش واقعا جواب میده")
        assertTrue(plain.none { it.show in setOf("counter", "ring", "price-tag", "voucher", "chart") }, plain.toString())
    }

    @Test
    fun theDirectorSeesTheExamplesUnderTheLine() {
        val text = DesignBook.examples(recall("هزینه دوره فقط سیصد هزار تومنه"))
        assertTrue("show=price-tag" in text && "an editor showed a line like" in text, text)
    }
}
