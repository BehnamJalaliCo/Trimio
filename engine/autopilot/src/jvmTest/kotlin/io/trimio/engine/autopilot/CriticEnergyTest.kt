package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.StoryBeat
import io.trimio.engine.motion.score.StoryMap
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The critic on the energy curve: the calm before a big strike is never called dead air, a hot
 * stretch breathes shorter than a cold one, and the self-critique's grades ([ContactSheet],
 * [EditCritic]) come back as reportable findings.
 */
class CriticEnergyTest {

    @Test
    fun theCalmBeforeAStrikeIsNeverDeadAir() {
        val words = paced(20)
        val strike = BeatScore(recipe = "slam", at = 8, hold = 1.5f, energy = 0.95f, text = "ضربه")
        val review = review(words, Score(scenes = listOf(SceneScore(from = 0, beats = listOf(strike)))))
        val at = words[8].range.startMs / 1000f
        assertTrue(review.issues.none { it.kind == "still" && it.at < at }, "the quiet before the strike is its silence: ${review.issues}")
        assertTrue(review.issues.any { it.kind == "still" && it.at > at }, "and ordinary quiet is still caught: ${review.issues}")
    }

    @Test
    fun aHotStretchBreathesShorterThanAColdOne() {
        val words = paced(20)
        fun beats(energy: Float) = Score(
            scenes = listOf(
                SceneScore(
                    from = 0,
                    beats = listOf(
                        BeatScore(recipe = "slam", at = 2, hold = 1.5f, energy = energy, text = "یک"),
                        BeatScore(recipe = "slam", at = 11, hold = 1.5f, energy = energy, text = "دو"),
                    ),
                ),
            ),
        )
        val hot = review(words, beats(0.75f))
        val cold = review(words, beats(0.05f))
        val stretch = words[2].range.startMs / 1000f + 1.5f
        assertTrue(hot.issues.any { it.kind == "still" && it.at in (stretch - 0.2f)..(stretch + 0.2f) }, "a hot 3s gap is dead air: ${hot.issues}")
        assertTrue(cold.issues.none { it.kind == "still" && it.at in (stretch - 0.2f)..(stretch + 0.2f) }, "a cold one can hold: ${cold.issues}")
    }

    @Test
    fun theSelfCritiquesGradesComeBackAsFindings() {
        val words = paced(20)
        val score = Score(
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore(recipe = "slam", at = 2, hold = 1.5f, energy = 0.7f, text = "یک"))),
            ),
        )
        val review = review(words, score, map())
        assertTrue(review.findings.any { it.startsWith("contact sheet:") }, "${review.findings}")
        assertTrue(review.findings.any { it.startsWith("edit critic:") }, "${review.findings}")
    }

    // ---------------------------------------------------------------- fixtures

    private fun review(words: List<Word>, score: Score, map: StoryMap? = null): Critic.Review {
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        val transcript = Transcript(Language.Persian, words)
        val out = Compiler(text, brands).compile(Compiler.Input(score, transcript, footage = "main"))
        return Critic().review(score, out, words, Lines.split(words), cta = null, u = null, map = map)
    }

    private fun map() = StoryMap(
        beats = listOf(
            StoryBeat(kind = "setup", from = 2, until = 5, claim = "یک دو سه", rationale = "the piece opens"),
            StoryBeat(kind = "payoff", from = 8, until = 11, claim = "چهار پنج", rationale = "the promise, paid"),
        ),
    )

    /** [n] words 0.5s apart: one line, room for two strikes and the quiet between them. */
    private fun paced(n: Int): List<Word> =
        (0 until n).map { i -> Word("w$i", TimeRange(300L + i * 500L, 300L + i * 500L + 420L), language = Language.Persian) }
}
