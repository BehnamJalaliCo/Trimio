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
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.SubtitleStyle
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The plain reel (phase 9.9): silences cut, every word in the picked subtitle style, nothing else on screen. */
class SubtitleReelTest {
    /** Twelve sentences of six words, a voice tone under each word and 1.6 s of silence between sentences. */
    private val words: List<Word>
    private val voice: PcmAudio

    init {
        val rate = 48_000
        val list = mutableListOf<Word>()
        var t = 0.4f
        repeat(12) { s ->
            repeat(6) { i ->
                list += Word(listOf("بازار", "امروز", "قیمت", "بیت‌کوین", "بالا", "رفت")[(s + i) % 6] + if (i == 5) "." else "", TimeRange((t * 1000).toLong(), ((t + 0.38f) * 1000).toLong()), language = Language.Persian)
                t += 0.45f
            }
            t += 1.6f
        }
        words = list
        val samples = FloatArray(((t + 0.5f) * rate).toInt())
        for (w in words) {
            val a = (w.range.startMs * rate / 1000).toInt()
            val b = (w.range.endMs * rate / 1000).toInt()
            for (k in a until b) samples[k] = 0.3f * sin(2 * PI * 180 * k / rate).toFloat()
        }
        voice = PcmAudio(samples, rate)
    }

    @Test
    fun theSilencesGoAndOnlyTheSubtitlesShow() = runBlocking {
        val reel = SubtitleReel(text, BrandLibrary.load(), VisualLibrary.Empty)
        for (style in SubtitleStyle.entries) {
            assertTrue(Recipes.named(style.recipe) != null, "${style.id}: its recipe is registered")
            val out = reel.produce(SubtitleReel.Request(Transcript(Language.Persian, words), voice, "", 1L, style))
            assertTrue(out.edit.cutCount >= 8, "${style.id}: the pauses are cut (${out.edit.cutCount})")
            assertTrue(out.edit.duration < voice.durationMs / 1000f - 10f, "${style.id}: shorter by the silences")
            val placed = out.compiled.beats
            assertTrue(placed.isNotEmpty() && placed.all { it.recipe == style.recipe }, "${style.id}: subtitles only: ${placed.map { it.recipe }.distinct()}")
            val shown = placed.sumOf { it.text.split(' ').size }
            assertEquals(words.size, shown, "${style.id}: every word is on screen")
            assertTrue(out.compiled.composition.width < out.compiled.composition.height, "a reel is 9:16")
        }
    }

    private companion object {
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    }
}
