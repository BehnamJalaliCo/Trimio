package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Icons
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionLanguageTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = assertTrue(abs(a - b) < eps, "$a != $b")

    @Test
    fun easingsStartAtZeroAndLandExactly() {
        val all = listOf(
            Easing.Linear, Easing.ExpoOut, Easing.ExpoIn, Easing.ExpoInOut, Easing.QuintOut, Easing.SineInOut,
            Easing.BackOut(), Easing.Spring(), Easing.Bezier(0.2f, 0f, 0f, 1f),
        )
        for (e in all) {
            near(e.at(0f), 0f)
            near(e.at(1f), 1f)
        }
        assertTrue(Easing.BackOut(2f).at(0.7f) > 1f, "back overshoots")
    }

    @Test
    fun keyframesInterpolateAndHold() {
        val a = anim(0f, at = 1f) { by(10f, 1f, Easing.Linear); hold(3f); by(0f, 1f, Easing.Linear) }
        near(a.at(0f), 0f)
        near(a.at(1.5f), 5f)
        near(a.at(2.5f), 10f)
        near(a.at(3.5f), 5f)
        near(a.at(9f), 0f)
        near((a + 2f.anim).at(2.5f), 12f)
    }

    @Test
    fun noiseIsDeterministicAndBounded() {
        val n = Anim.Noise(4, 10f, 5f.anim)
        near(n.at(1.234f), n.at(1.234f))
        assertTrue((0..200).all { abs(n.at(it / 37f)) <= 5f })
    }

    @Test
    fun textAnimatorStaggersAndSettles() {
        val a = TextAnimator(TextUnit.Word, UnitState(dy = 1f, opacity = 0f), at = 1f, duration = 0.5f, stagger = 0.1f, ease = Easing.Linear)
        near(a.startOf(3, 5), 1.3f)
        near(a.stateAt(1f, 0, 5).opacity, 0f)
        near(a.stateAt(1.25f, 0, 5).dy, 0.5f)
        assertTrue(a.stateAt(2f, 4, 5).isRest)
        val pulse = a.copy(shape = Shape.Pulse)
        assertTrue(pulse.stateAt(0.5f, 0, 1).isRest && pulse.stateAt(2f, 0, 1).isRest)
    }

    @Test
    fun spokenNumbersAreFound() {
        fun first(s: String) = NumberWords.findAll(s.split(' ')).first()
        first("امروز بیت‌کوین پنج درصد رشد کرد").let { assertEquals(5.0, it.value); assertTrue(it.percent); assertEquals(2, it.count) }
        assertEquals(68_000.0, first("قیمت الان شصت و هشت هزار دلاره").value)
        assertEquals(5.5, first("پنج و نیم درصد").value)
        assertEquals(5.2, first("رشد ۵٫۲٪ بود").value)
        assertEquals(1_250_000.0, first("one million two hundred fifty thousand").value)
        assertEquals(42.0, first("about 42 percent").value)
    }

    @Test
    fun scoresParseFromLooseModelOutput() {
        val score = Score.parse(
            """Sure! ```json
            {"look":"paper","extra":"ignored","scenes":[{"from":2,"beats":[{"recipe":"Impact","text":"سود","energy":"0.9"}]}]}
            ``` hope it helps""",
        )
        assertEquals("paper", score.look)
        assertEquals("slam", Recipes.named(score.scenes[0].beats[0].recipe)?.name)
        assertEquals(0.9f, score.scenes[0].beats[0].energy)
    }

    @Test
    fun looseNamesResolve() {
        listOf("caption", "typewriter", "price", "graph", "badge", "kinetic", "number").forEach { assertNotNull(Recipes.named(it), it) }
        listOf("btc", "pump", "dump", "signal", "tp").forEach { assertNotNull(Icons.path(it), it) }
    }

    @Test
    fun fixedFormattingGroupsAndRounds() {
        assertEquals("68,000", formatFixed(68000f, 0, true))
        assertEquals("5.2", formatFixed(5.2f, 1, true))
        assertEquals("-0.05", formatFixed(-0.049f, 2, false))
    }

    private fun w(text: String, a: Float, b: Float) = Word(text, TimeRange((a * 1000).toLong(), (b * 1000).toLong()), language = Language.Persian)

    @Test
    fun silencesAreTightenedAndTimesRemapped() {
        val words = listOf(w("سلام", 0.2f, 0.6f), w("دوستان.", 0.6f, 1.0f), w("امروز", 1.8f, 2.2f))
        val edit = EditPlan.tighten(words, listOf(1.0f..1.8f), duration = 3f)
        assertEquals(2, edit.segments.size)
        near(edit.duration, (1.06f - 0.12f) + (3f - 1.74f).coerceAtMost(2.65f - 1.74f), 0.02f)
        // The word after the pause now starts a breath after the one before it.
        near(edit.toOutput(1.8f), edit.toOutput(1.0f) + 0.12f, 0.01f)
    }

    @Test
    fun wordsStretchedOverPausesAreClipped() {
        val aligned = EditPlan.alignToSpeech(listOf(w("برات", 7.1f, 7.82f), w("نصب", 7.82f, 8.36f)), listOf(7.2f..7.57f))
        near(aligned[0].range.startMs / 1000f, 7.57f, 0.01f)
    }
}
