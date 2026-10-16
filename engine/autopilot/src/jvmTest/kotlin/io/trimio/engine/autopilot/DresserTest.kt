package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VectorIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DresserTest {
    private val words = List(60) { i -> Word("w$i", TimeRange(i * 500L, i * 500L + 400), language = Language.Persian) }
    private fun icon(role: String, id: String) = VectorIcon(id, TemplateShelf.SET, 100f, 40f, emptyList(), listOf(role))

    @Test
    fun aBeatWearsTheAccentOfWhatItIsAndAccentsKeepTheirDistance() {
        val score = Score(
            scenes = listOf(
                SceneScore(
                    from = 0,
                    beats = listOf(
                        BeatScore(recipe = "slam", text = "یه کانال سیگنال", at = 0),
                        BeatScore(recipe = "board-price", at = 4, mark = "loss"),
                        BeatScore(recipe = "board-price", at = 20, mark = "gain"),
                        BeatScore(recipe = "board-steps", at = 40),
                    ),
                ),
            ),
        )
        val icons = listOf(icon("underline", "u1"), icon("cross", "x1"), icon("check", "c1"), icon("tap", "t1"))
        val (dressed, worn) = Dresser.dress(score, words, icons)
        val beats = dressed.scenes.single().beats
        assertEquals("template:u1", beats[0].accent)
        // Two seconds after the first accent: too close, left bare.
        assertNull(beats[1].accent)
        assertEquals("template:c1", beats[2].accent)
        assertEquals("template:t1", beats[3].accent)
        assertEquals(listOf("u1", "c1", "t1"), worn)
    }

    @Test
    fun theNextEditWearsAnotherCaptionStyle() {
        val score = Score(captions = CaptionScore(mark = "meaning", maxWords = 3))
        val (_, first) = Dresser.captions(score, emptySet(), seed = 1)
        val (_, second) = Dresser.captions(score, setOf(first), seed = 1)
        assertTrue(first.startsWith("captions:"))
        assertNotEquals(first, second)
        // A reel not told in cards keeps its own captions.
        assertEquals("", Dresser.captions(Score(), emptySet(), 1).second)
    }
}
