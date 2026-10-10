package io.trimio.engine.motion

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ask said in the last breath gets an end hold: the picture holds, captions end, the end card stays. */
class OutroTest {
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
    private val words = (0 until 12).map { i -> Word("کلمه$i", TimeRange(300L + i * 300L, 580L + i * 300L), language = Language.Persian) }
    private val lastWord = words.last().range.endMs / 1000f

    private fun footage(root: Group): List<MediaNode> = root.children.filterIsInstance<MediaNode>()

    @Test
    fun theSourceRunsOutSoTheLastFrameHolds() {
        // The source ends 0.2 s after the ask: 2 s of hold, 0.2 s of it still running footage.
        val source = lastWord + 0.2f
        val edit = EditPlan.tighten(words, emptyList(), source).withOutro(2f, source)
        val outro = assertNotNull(edit.outro)
        assertEquals(edit.bodyEnd + 2f, edit.duration, 1e-4f)
        assertEquals(0f, outro.running, 1e-4f, "tighten kept the source to its end")
        // Words are where they were: the hold only adds time after them.
        assertEquals(lastWord, edit.toOutput(lastWord) + edit.segments.first().sourceStart, 0.01f)

        val score = Score(auto = false, scenes = listOf(SceneScore(from = 0, beats = listOf(BeatScore("comment", at = 10, text = "کامنت", hold = 30f, place = "lower")))))
        val out = Compiler(probe.renderer.text).compile(Compiler.Input(score, Transcript(Language.Persian, words), footage = "main", edit = edit))
        assertEquals(edit.duration, out.composition.duration, 1e-4f)
        val media = footage(out.composition.root)
        val hold = media.single { it.name == "footage-hold" }
        assertEquals(0f, hold.speed)
        assertEquals(edit.bodyEnd, hold.start, 1e-4f)
        assertTrue(hold.sourceOffset <= source && hold.sourceOffset > source - 0.1f, "the source's last frame: ${hold.sourceOffset}")
        assertEquals(edit.bodyEnd, media.first { it.name == "footage-0" }.end, 1e-4f, "the moving footage stops where the hold starts")
        // Captions end with the words; the end card stays through the hold.
        assertTrue(out.beats.filter { it.recipe == "pop-captions" }.all { it.out <= edit.bodyEnd + 0.4f })
        assertTrue(out.beats.single { it.recipe == "comment" }.out >= edit.duration - 0.05f, "the end card is read in the hold")
    }

    @Test
    fun withMoreSourceTheFootageRunsOnAndTheHoldIsCapped() {
        val edit = EditPlan.tighten(words, emptyList(), lastWord + 10f).withOutro(4f, lastWord + 10f)
        val outro = assertNotNull(edit.outro)
        assertEquals(EditPlan.MAX_OUTRO, outro.length, 1e-4f)
        assertEquals(outro.length, outro.running, 1e-4f)
        val out = Compiler(probe.renderer.text).compile(Compiler.Input(Score(auto = false, scenes = listOf(SceneScore(from = 0))), Transcript(Language.Persian, words), footage = "main", edit = edit))
        val media = footage(out.composition.root)
        assertTrue(media.none { it.name == "footage-hold" })
        assertEquals(Float.POSITIVE_INFINITY, media.last().end)
        // A cold open after the hold was set keeps it.
        val cold = edit.withColdOpen(listOf(1.0f..2.0f), 0.25f)
        assertEquals(outro, cold.outro)
        assertEquals(cold.bodyEnd + outro.length, cold.duration, 1e-4f)
        assertNull(edit.withOutro(0f, lastWord + 10f).outro)
    }
}
