package io.trimio.engine.autopilot.longform

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The long-form (YouTube) edit on a five-minute talk in three topics: chapters by YouTube's rules, subtitle files
 * a Studio upload takes, the video as recorded (no cut), and for the pro kind its devices in their places.
 */
class LongFormTest {
    /** Five minutes in three topics (bitcoin, cooking, football), a sentence every ~3 s, a long breath between topics. */
    private val words: List<Word> = buildList {
        val topics = listOf(
            listOf("بیت‌کوین", "قیمت", "بازار", "سرمایه", "صرافی", "کیف", "پول", "تراکنش"),
            listOf("آشپزی", "برنج", "زعفران", "قابلمه", "نمک", "روغن", "خورش", "پیاز"),
            listOf("فوتبال", "توپ", "دروازه", "بازیکن", "مربی", "ورزشگاه", "گل", "لیگ"),
        )
        var t = 0L
        topics.forEachIndexed { k, vocab ->
            repeat(33) { s ->
                repeat(6) { i ->
                    val text = vocab[(s + i) % vocab.size] + if (i == 5) "." else ""
                    add(Word(text, TimeRange(t, t + 380), language = Language.Persian))
                    t += 450
                }
                t += 300
            }
            if (k < topics.lastIndex) t += 2_500
        }
    }
    private val duration = words.last().range.endMs / 1000f + 1f
    private val voice = PcmAudio(FloatArray((duration * 48_000).toInt()), 48_000)

    @Test
    fun chaptersFollowYouTubesRules() {
        val lines = Lines.split(words)
        val starts = YouTubeChapters.starts(words, lines, duration)
        assertTrue(starts.size >= 3, "at least three chapters: $starts")
        assertEquals(0, starts.first(), "the first at 0:00")
        val times = starts.map { words[it].range.startMs / 1000f }
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 10f }, "each at least ten seconds: $times")
        // The topic turns are found (within a sentence of the breath between topics).
        val turns = listOf(100f, 200f)
        turns.forEach { turn -> assertTrue(times.any { kotlin.math.abs(it - turn) < 12f }, "a chapter near ${turn}s: $times") }
        assertEquals("0:00", YouTubeChapters.stamp(0f))
        assertEquals("12:05", YouTubeChapters.stamp(725f))
        assertEquals("1:02:09", YouTubeChapters.stamp(3729f))
        assertTrue(YouTubeChapters.starts(words.take(40), Lines.split(words.take(40)), 30f).isEmpty(), "a short video gets none")
    }

    @Test
    fun subtitleFilesAreWhatStudioTakes() {
        val cues = SubtitleFiles.cues(words)
        val srt = SubtitleFiles.srt(cues)
        val vtt = SubtitleFiles.vtt(cues)
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:"), srt.take(80))
        assertTrue(vtt.startsWith("WEBVTT\n\n00:00:00.000 --> "), vtt.take(80))
        assertTrue(cues.zipWithNext().all { (a, b) -> a.to <= b.from }, "cues never overlap")
        assertTrue(cues.all { it.text.split(' ').size <= SubtitleFiles.MAX_WORDS })
        assertEquals(words.size, cues.sumOf { it.text.split(' ').size }, "every word is in the file")
    }

    @Test
    fun theVideoPlaysAsRecordedWithSubtitles() = runBlocking {
        val pilot = LongFormPilot(text, BrandLibrary.load(), VisualLibrary.Empty)
        val out = pilot.produce(LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Subtitles))
        assertEquals(1, out.edit.segments.size, "no cut: ${out.edit.segments.size} segments")
        assertTrue(out.compiled.composition.duration >= duration - 0.5f)
        val placed = out.compiled.beats
        assertTrue(placed.all { it.recipe == "subtitles" }, "subtitles only: ${placed.map { it.recipe }.distinct()}")
        assertTrue(placed.size >= 80, "a subtitle for every line: ${placed.size}")
        val pack = out.youtube!!
        assertTrue(pack.chapters.size >= 3 && pack.description.startsWith("0:00 "), pack.description)
        assertTrue(pack.srt.isNotBlank() && pack.vtt.startsWith("WEBVTT"))
        assertTrue(pack.shorts.isNotEmpty() && pack.shorts.all { it.to - it.from in 30f..61f }, pack.shorts.toString())
    }

    /** A director that titles each chapter (and the video) in a few words. */
    private class Titler : LanguageModel {
        override val id = "titler"
        override val isLocal = true
        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            val text = if ("titles" in request.system) {
                """{"titles":["سه موضوع در پنج دقیقه"],"thumbnail":["سه موضوع"]}"""
            } else {
                """{"title":"${request.messages.last().text.split(' ').first()} در یک نگاه"}"""
            }
            onText(text)
            return Generation(text, StopReason.EndTurn, id)
        }
    }

    @Test
    fun theProKindAddsYouTubesDevicesInTheirPlaces() = runBlocking {
        val pilot = LongFormPilot(text, BrandLibrary.load(), VisualLibrary.Empty, Titler())
        val out = pilot.produce(LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Pro))
        val placed = out.compiled.beats
        val subscribe = placed.filter { it.recipe == "yt-subscribe" }
        assertEquals(1, subscribe.size, "one subscribe ask")
        // Every device is on screen for real: it ends after it starts.
        assertTrue(placed.filter { it.recipe != "subtitles" }.all { it.out > it.at + 1f }, "a device never shown: $placed")
        assertTrue(subscribe.single().at >= 59f, "after the first minute: ${subscribe.single().at}")
        val cards = placed.filter { it.recipe == "yt-chapter" }
        assertTrue(cards.size >= 2, "a card at every chapter after the first")
        // Both sit at the top of the frame: never up together.
        assertTrue(cards.none { c -> c.at < subscribe.single().out && subscribe.single().at < c.out }, "the ask and a card overlap: $placed")
        val endScreen = out.compiled.composition.duration - 20f
        assertTrue(placed.filter { it.recipe != "subtitles" }.all { it.out <= endScreen + 0.5f }, "the end screen is kept clear")
        // Subtitles never yield to the devices (they sit at the top).
        assertTrue(placed.count { it.recipe == "subtitles" } >= 80)
    }

    @Test
    fun spokenNumbersAndBrandsShowOneAtATime() = runBlocking {
        fun said(text: String, from: Long) = text.split(' ').mapIndexed { i, w -> Word(w, TimeRange(from + i * 400L, from + i * 400L + 350), language = Language.Persian) }
        val words = said("سود ما بیست درصد بود.", 10_000) + said("سال ۱۴۰۳ شروع کردیم.", 15_000) + said("روی Binance خریدیم.", 50_000) +
            said("سیگنال تلگرام داریم.", 90_000) + said("پنجاه درصد دیگه.", 95_000)
        val beats = SpokenFacts.beats(words, BrandLibrary.load(), busy = listOf(94f..99f), endScreen = 200f)
        assertEquals(listOf("counter", "logos"), beats.map { it.recipe }, beats.toString())
        assertEquals(20f, beats[0].value, "twenty percent, not the year")
        assertEquals(listOf("Binance"), beats[1].items)
    }

    @Test
    fun withoutADirectorNoChapterCardIsShown() = runBlocking {
        // The rules title a chapter by its opening words: fine in the description, a broken sentence on screen.
        val pilot = LongFormPilot(text, BrandLibrary.load(), VisualLibrary.Empty)
        val out = pilot.produce(LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Pro))
        assertTrue(out.compiled.beats.none { it.recipe == "yt-chapter" })
        assertTrue(out.youtube!!.chapters.size >= 3, "the description still lists them")
    }

    private companion object {
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    }
}
