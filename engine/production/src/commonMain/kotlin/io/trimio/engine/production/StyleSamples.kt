package io.trimio.engine.production

import androidx.compose.ui.text.TextMeasurer
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.style.StyleBook

/**
 * A short live sample of each of the 28 styles for the gallery: the style's own headline word
 * entering the way the style likes (its favourite entrance, mark and stage), then a figure and a
 * line of the sample voice-over ("Bitcoin grew five percent today…"), compiled by the same
 * compiler and drawn by the same renderer as a real edit. So a card shows the style's voice in
 * motion, not an illustration of it.
 */
class StyleSamples(measurer: TextMeasurer, fonts: MotionFonts, private val brands: BrandLibrary = BrandLibrary.Empty) {
    private val compiler = Compiler(TextLayoutEngine(measurer, fonts), brands)

    /** [style]'s sample with [word] (a word in its voice: «نوآر», «سود!») as the headline. Motion blur is off: many cards play at once. */
    fun sample(style: String, word: String): Compiler.Output {
        val s = StyleBook.of(style) ?: error("no style $style")
        val stage = s.rhythm.stages.maxByOrNull { it.value }?.key ?: "aurora"
        val entrance = s.motion.entrances.filterKeys { it in TEXT_ENTRANCES }.maxByOrNull { it.value }?.key
            ?: if (s.motion.energy >= LIVELY) "slam" else "mask-rise"
        val then = s.motion.enter.maxByOrNull { it.value }?.key ?: "cut"
        val score = Score(
            look = s.id, background = s.background, lens = s.lens, auto = false, captions = CaptionScore(show = false),
            scenes = listOf(
                SceneScore(time = 0f, bg = stage, camera = "push-in", beats = listOf(BeatScore(entrance, time = HEADLINE_AT, hold = HEADLINE_HOLD, text = word, emphasis = listOf(word), place = "center", energy = s.motion.energy.coerceIn(0f, 1f)))),
                SceneScore(
                    time = SECOND_AT, bg = stage, transition = then,
                    beats = listOf(
                        BeatScore("counter", time = SECOND_AT + LEAD, hold = FIGURE_HOLD, value = FIGURE, from = 0f, prefix = "+", suffix = "%", decimals = 1, label = LABEL, place = "center"),
                    ),
                ),
            ),
        )
        val out = compiler.compile(Compiler.Input(score, TRANSCRIPT, duration = DURATION, fps = FPS))
        val c = out.composition
        return out.copy(composition = Composition(c.width, c.height, c.fps, c.duration, c.background, c.root, c.camera, shutterAngle = 0f))
    }

    companion object {
        /** The moment a paused card shows: the headline has landed. */
        fun still(output: Compiler.Output): Float = output.beats.firstOrNull()?.let { it.at + HOLD } ?: (output.composition.duration * SHARE)

        private const val HOLD = 0.9f
        private const val SHARE = 0.3f
        private const val FPS = 30
        private const val DURATION = 5.6f
        private const val HEADLINE_AT = 0.35f
        private const val HEADLINE_HOLD = 2.3f
        private const val SECOND_AT = 2.9f
        private const val LEAD = 0.25f
        private const val FIGURE_HOLD = 2.3f
        private const val FIGURE = 5.2f
        private const val LIVELY = 0.6f
        private const val LABEL = "بیت‌کوین امروز"
        private val TEXT_ENTRANCES = setOf("mask-rise", "slam", "type-on", "blur-in", "flip", "spread", "stack", "glitch")

        /** The sample voice-over, word by word (seconds), as a recogniser would hear it. */
        private val SAID = listOf(
            "امروز" to 0.15f, "بیت‌کوین" to 0.55f, "پنج" to 1.1f, "درصد" to 1.4f, "رشد" to 1.85f, "کرد." to 2.15f,
            "سیگنال" to 2.9f, "خرید" to 3.35f, "را" to 3.7f, "از" to 3.85f, "دست" to 4.0f, "نده!" to 4.3f,
        )

        private val WORDS: List<Word> = SAID.mapIndexed { i, (w, t) ->
            val end = SAID.getOrNull(i + 1)?.second?.minus(GAP) ?: (t + LAST)
            Word(w, TimeRange((t * MS).toLong(), (end * MS).toLong()), language = Language.Persian)
        }
        private val TRANSCRIPT = Transcript(Language.Persian, WORDS)
        private const val GAP = 0.05f
        private const val LAST = 0.5f
        private const val MS = 1000f
    }
}
