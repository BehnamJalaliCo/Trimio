package io.trimio.engine.production

import androidx.compose.ui.text.TextMeasurer
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.Planner
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Taste
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.style.StyleBook

/**
 * A short live sample of each of the 28 styles for the gallery: one sample voice-over ("Bitcoin
 * grew five percent today…"), read by the rules, planned by the same planner as a real edit with
 * the style asked for by name, and compiled. So a card shows what the director makes in that
 * style, not an illustration of it.
 */
class StyleSamples(measurer: TextMeasurer, fonts: MotionFonts, private val brands: BrandLibrary = BrandLibrary.Empty) {
    private val compiler = Compiler(TextLayoutEngine(measurer, fonts), brands)

    /**
     * [style]'s sample, over footage (the card draws a stand-in) when [footage]. Motion blur is
     * off: cards are small and many play at once.
     */
    fun sample(style: String, footage: Boolean = true, seed: Long = SEED): Compiler.Output {
        val s = StyleBook.of(style) ?: error("no style $style")
        val brief = "$PROMPT\n\nStyle: ${s.nameEn} (${s.id})"
        val lines = Lines.split(WORDS)
        val understanding = RulesUnderstander.understand(TRANSCRIPT, brief, lines)
        val plan = Planner(Taste(), brief).plan(WORDS, lines, understanding, seed, footage = footage)
        val out = compiler.compile(Compiler.Input(plan.score, TRANSCRIPT, footage = if (footage) FOOTAGE else null, fps = FPS))
        val c = out.composition
        return out.copy(composition = Composition(c.width, c.height, c.fps, c.duration, c.background, c.root, c.camera, shutterAngle = 0f))
    }

    companion object {
        /** The media id the sample's footage answers to. */
        const val FOOTAGE = "main"
        private const val FPS = 30
        private const val SEED = 7L
        private const val PROMPT = "یک ریلز کوتاه و پرانرژی درباره‌ی سیگنال امروز بیت‌کوین"

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
