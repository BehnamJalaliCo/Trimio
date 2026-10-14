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

    @Test
    fun theProKindAddsYouTubesDevicesInTheirPlaces() = runBlocking {
        val pilot = LongFormPilot(text, BrandLibrary.load(), VisualLibrary.Empty)
        val out = pilot.produce(LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Pro))
        val placed = out.compiled.beats
        val subscribe = placed.filter { it.recipe == "subscribe" }
        assertEquals(1, subscribe.size, "one subscribe ask")
        assertTrue(subscribe.single().at >= 59f, "after the first minute: ${subscribe.single().at}")
        assertTrue(placed.count { it.recipe == "chapter" } >= 2, "a card at every chapter after the first")
        val endScreen = out.compiled.composition.duration - 20f
        assertTrue(placed.filter { it.recipe != "subtitles" }.all { it.out <= endScreen + 0.5f }, "the end screen is kept clear")
        // Subtitles never yield to the devices (they sit at the top).
        assertTrue(placed.count { it.recipe == "subtitles" } >= 80)
    }

    private companion object {
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    }
}
