package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryCutTest {
    /** One sentence per line, a second of silence between sentences. */
    private val talk = listOf(
        "اصلا نمی‌دونم چی بگم وای.",
        "رفتم یه کانال سیگنال دیدم روزی ده تا سیگنال می‌ده.",
        "نه تاش استاپ می‌خوره و فقط یکیش سود داره.",
        "ورودیش هم دو میلیون تومنه که خیلی گرونه.",
        "کلمه کانال رو برام بفرست بیا تو کانال ما.",
        "ما همه سیگنال‌ها رو روی سایت گذاشتیم شفاف.",
        "هر کسی می‌تونه نتیجه‌ها رو ببینه و بررسی کنه.",
        "ورودی ما فقط پنجاه تتره و رایگانه.",
        "دوباره نه تاش استاپ می‌خوره و یکیش سود داره.",
        "پشتیبانی هم داریم هر روز جواب می‌دیم.",
        "ربات معاملاتی هم ساختیم که خودکار کار می‌کنه.",
        "اگه می‌خوای بدون دغدغه ترید کنی بیا.",
        "کلمه کانال رو کامنت کن و دایرکتتو چک کن.",
    )

    private fun timed(sentences: List<String>): List<Word> {
        var t = 300L
        return sentences.flatMap { s ->
            s.split(' ').map { w -> Word(w, TimeRange(t, t + 420), language = Language.Persian).also { t += 500 } }.also { t += 1000 }
        }
    }

    private val words = timed(talk)
    private val lines = Lines.split(words, maxWords = 14)
    private fun line(start: String) = lines.indexOfFirst { words[it.first].text == start }
    private fun gone(cut: StoryCut.Cut, k: Int) = lines[k].range.all { it in cut.dropped }
    private fun kept(cut: StoryCut.Cut, k: Int) = lines[k].range.none { it in cut.dropped }

    @Test
    fun theHesitationTheEarlyAskAndTheRepeatGo() {
        val cut = StoryCut.choose(words, lines, keyword = "کانال")
        assertTrue(gone(cut, line("اصلا")), cut.notes.joinToString("\n"))
        // The first ask goes; the last one (and only it) stays.
        assertTrue(gone(cut, line("کلمه")))
        assertTrue(kept(cut, lines.indexOfLast { words[it.first].text == "کلمه" }))
        // «Nine of them hit stop-loss», said again a minute on.
        assertTrue(gone(cut, line("دوباره")), cut.notes.joinToString("\n"))
        // What is said once stays.
        for (start in listOf("رفتم", "نه", "ورودیش", "ما", "ورودی", "ربات", "اگه")) assertTrue(kept(cut, line(start)), start)
    }

    @Test
    fun aClauseGoesWithoutTheRestOfItsLine() {
        // «Everything is on the site» again, between two new things, in one line.
        val more = timed(talk.take(11) + listOf("قیمت‌ها منصفانه‌ست، روی سایت همه سیگنال‌ها رو گذاشتیم شفاف، آموزش رایگان هم داریم.") + talk.drop(11))
        val split = Lines.split(more, maxWords = 14)
        val cut = StoryCut.choose(more, split, keyword = "کانال")
        val said = more.filterIndexed { i, _ -> i !in cut.dropped }.joinToString(" ") { it.text }
        assertTrue("روی سایت همه" !in said, cut.notes.joinToString("\n"))
        assertTrue("قیمت‌ها منصفانه‌ست،" in said && "آموزش رایگان هم داریم." in said, said)
    }

    @Test
    fun theCutReindexesWordsLinesAndReading() {
        val cut = StoryCut.choose(words, lines, keyword = "کانال")
        val transcript = Transcript(Language.Persian, words)
        val reading = Understanding(
            lines = lines.map { LineRead(gist = words[it.first].text) },
            hook = Hook(line("نه")),
            entities = listOf(Entity(at = lines[line("ربات")].first, name = "bot")),
        )
        val edit = EditPlan.identity(words.last().range.endMs / 1000f + 1f)
        val a = StoryCut.apply(cut, edit, transcript, transcript, lines, reading)
        assertEquals(words.size - cut.dropped.size, a.transcript.words.size)
        assertEquals(lines.count { l -> !l.range.all { it in cut.dropped } }, a.lines.size)
        assertEquals(a.lines.size, a.understanding.lines.size)
        a.lines.forEachIndexed { k, l -> assertEquals(a.understanding.lines[k].gist, a.transcript.words[l.first].text) }
        assertEquals("نه", a.transcript.words[a.lines[a.understanding.hook!!.line].first].text)
        assertEquals("ربات", a.transcript.words[a.understanding.entities.single().at].text)
        // The dropped seconds are gone from the body, and every word kept still plays.
        val dropped = cut.dropped.sumOf { (words[it].range.endMs - words[it].range.startMs).toDouble() } / 1000.0
        assertTrue(edit.duration - a.edit.duration > dropped, "${edit.duration} → ${a.edit.duration}")
        for (w in a.transcript.words) {
            val mid = (w.range.startMs + w.range.endMs) / 2000f
            assertTrue(a.edit.segments.any { mid in it.sourceStart..it.sourceEnd }, w.text)
        }
        assertTrue(a.edit.removed.isNotEmpty())
    }

    @Test
    fun aRemovedStretchIsNeverPlayedAgainToKeepAPause() {
        val edit = EditPlan.identity(10f).without(listOf(3f..5f))
        assertEquals(2, edit.segments.size)
        assertEquals(edit, JumpCuts.joined(edit, 0))
    }

    @Test
    fun aShortPieceOrABriefThatKeepsEverythingIsNotCut() {
        val short = timed(talk.take(4))
        assertTrue(StoryCut.choose(short, Lines.split(short)).isEmpty)
        assertTrue(StoryCut.keepsAll("لطفا چیزی حذف نکن فقط زیرنویس بذار"))
        assertFalse(StoryCut.keepsAll("سکوت‌ها و تکراری‌ها رو حذف کن"))
    }

    /**
     * The owner's reel against the reference edit (docs/research/REFERENCE_EDL.md):
     * -Pstory.words=transcript.json. Prints what goes and the agreement in seconds.
     */
    @Test
    fun probeAgainstTheReference() {
        val path = System.getProperty("story.words") ?: return
        val heard = Json.parseToJsonElement(File(path).readText()).jsonArray.map { e ->
            val o = e.jsonObject
            val range = TimeRange((o.getValue("s").jsonPrimitive.float * 1000).toLong(), (o.getValue("e").jsonPrimitive.float * 1000).toLong())
            Word(o.getValue("w").jsonPrimitive.content, range, language = Language.Persian)
        }
        // The voice's pauses, as the recogniser's gaps show them (the app measures them on the voice itself).
        val pauses = heard.zipWithNext().map { (a, b) -> a.range.endMs / 1000f..b.range.startMs / 1000f }.filter { it.endInclusive - it.start >= 0.3f }
        val split = Lines.split(heard, pauses = pauses)
        val cut = StoryCut.choose(heard, split, keyword = "کانال")
        StoryCut.clauses(heard, split).forEach { c -> println("${if (c.first in cut.dropped) "✗" else " "} ${heard[c.first].range.startMs / 1000f} ${heard.textOf(c)}") }
        cut.notes.forEach(::println)
        val kept = listOf(4.98f..18.23f, 18.19f..21.44f, 21.45f..25.2f, 26.39f..38.64f, 44.82f..54.32f, 72.7f..77.45f, 77.11f..91.11f, 91.85f..103.6f, 119.69f..122.19f, 133.42f..152.67f)
        val gone = cut.dropped
        var both = 0f; var oursOnly = 0f; var refOnly = 0f; var neither = 0f
        heard.forEachIndexed { i, w ->
            val d = (w.range.endMs - w.range.startMs) / 1000f
            val ref = kept.any { (w.range.startMs + w.range.endMs) / 2000f in it }
            val ours = i !in gone
            when {
                ours && ref -> both += d
                ours -> oursOnly += d
                ref -> refOnly += d
                else -> neither += d
            }
        }
        println("kept by both ${both}s, kept only by us ${oursOnly}s, cut only by us ${refOnly}s, cut by both ${neither}s")
    }
}
