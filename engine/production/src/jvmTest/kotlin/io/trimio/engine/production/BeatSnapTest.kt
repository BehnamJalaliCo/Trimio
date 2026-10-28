package io.trimio.engine.production

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.BeatGrid
import io.trimio.engine.motion.score.EditPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Real beat-snap: with a grid known the cuts land on it in a ½-beat window (the old 80 ms only
 * nudged a cut that was nearly there already), never onto a word — and an animation's landing
 * snaps the same way.
 */
class BeatSnapTest {

    private fun word(text: String, from: Float, to: Float) =
        Word(text, TimeRange((from * 1000).toLong(), (to * 1000).toLong()), language = Language.Persian)

    /** Two pieces with one join at 1.1 s on the output clock. */
    private val edit = EditPlan(listOf(EditPlan.Segment(0f, 1.1f, 0f), EditPlan.Segment(1.5f, 2.6f, 1.1f)))

    private val words = listOf(word("یک", 0.2f, 1.0f), word("دو", 1.6f, 2.5f))

    @Test
    fun cutsMoveOntoTheBeatWithinHalfABeat() {
        // 120 BPM (0.5 s a beat): the join at 1.1 s is 0.2 s off the beat at 1.3 s — past the old
        // 80 ms window, inside the ½-beat one. The piece after the cut follows it.
        val snapped = Retouch.snapCuts(edit, BeatGrid(120.0, offset = 1.3), words)
        assertEquals(1.3f, snapped.segments[0].sourceEnd, 0.002f, "${snapped.segments}")
        assertEquals(1.3f, snapped.segments[1].outStart, 0.002f, "the piece after the cut follows it")
        assertEquals(edit.segments[1].length, snapped.segments[1].length, 0.002f, "and keeps its length")
    }

    @Test
    fun theSnapIsOnWheneverAGridIsKnown() {
        val grid = BeatGrid(120.0, offset = 1.3)
        assertSame(edit, Retouch.snapped(edit, null, words, beatCuts = null), "no grid, no snap")
        assertSame(edit, Retouch.snapped(edit, grid, words, beatCuts = false), "the creator turned it off")
        assertTrue(Retouch.snapped(edit, grid, words, beatCuts = null) !== edit, "default: the cuts move")
        assertTrue(Retouch.snapped(edit, grid, words, beatCuts = true) !== edit, "asking for it keeps working")
    }

    @Test
    fun aCutNeverRunsIntoAWord() {
        // A beat before the word's end: the piece shortens only to just after its last word.
        val early = Retouch.snapCuts(edit, BeatGrid(120.0, offset = 0.95), words)
        assertEquals(1.03f, early.segments[0].sourceEnd, 0.002f, "a word's tail is never cut into")
        // A deleted word in the gap stays deleted: the piece does not run onto it.
        val gap = listOf(word("یک", 0.2f, 1.0f), word("حذف", 1.15f, 1.45f), word("دو", 1.6f, 2.5f))
        val blocked = Retouch.snapCuts(edit, BeatGrid(120.0, offset = 1.35), gap)
        assertEquals(1.15f, blocked.segments[0].sourceEnd, 0.002f, "${blocked.segments}")
    }

    @Test
    fun landingsSnapToTheGridToo() {
        val grid = BeatGrid(120.0, offset = 1.0)
        assertEquals(0.25, grid.halfBeat, 1e-9, "the window is half a beat")
        assertEquals(1.0, grid.snapLanding(1.2), 1e-9, "a landing 0.2 s off the beat snaps onto it")
        assertEquals(1.5, grid.snapLanding(1.28), 1e-9, "and to the nearest one")
    }
}
