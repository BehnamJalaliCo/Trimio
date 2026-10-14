package io.trimio.engine.motion

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditPlanTest {
    private fun w(text: String, a: Float, b: Float) = Word(text, TimeRange((a * 1000).toLong(), (b * 1000).toLong()), language = Language.Persian)

    @Test
    fun coldOpenPlaysThePayoffFirstAndShiftsTheBody() {
        val words = listOf(w("سلام", 0.2f, 0.6f), w("امروز", 0.7f, 1.1f), w("سیصد", 10f, 10.5f), w("تتر", 10.6f, 11f), w("کامنت", 15f, 15.5f))
        val plain = EditPlan.tighten(words, emptyList(), 16f)
        val cold = plain.withColdOpen(9.9f..11.1f, gap = 0.1f)
        val shift = 1.3f
        assertEquals(1.2f, cold.preludeLength, 1e-4f)
        assertEquals(plain.duration + shift, cold.duration, 1e-3f)
        // Body words keep one place each, later by the prelude.
        val body = cold.remap(Transcript(Language.Persian, words)).words
        assertEquals(words.size, body.size)
        assertEquals(plain.toOutput(0.2f) + shift, body.first().range.startMs / 1000f, 0.01f)
        // The cold open's words are heard at the very start.
        val opening = cold.preludeWords(Transcript(Language.Persian, words))
        assertEquals(listOf("سیصد", "تتر"), opening.map { it.text })
        assertTrue(opening.first().range.startMs < 200 && opening.last().range.endMs <= 1300)
        // Footage and voice play the prelude, then the body.
        assertEquals(cold.prelude, cold.played.first())
        assertEquals(cold.segments.size + 1, cold.played.size)
    }

    @Test
    fun aTightenedColdOpenPlaysBackToBackAndTheBodyRestartsOnItsFirstWord() {
        val words = listOf(w("سلام", 0.5f, 0.9f), w("امروز", 1.0f, 1.4f), w("سیصد", 10f, 10.5f), w("تتر", 10.6f, 11f), w("اولیه", 11.6f, 12f), w("کامنت", 15f, 15.5f))
        val plain = EditPlan.tighten(words, emptyList(), 16f)
        // The pause before «اولیه» tightened out of the opening; the body restarts 20 ms before «سلام».
        val cold = plain.withColdOpen(listOf(9.95f..11.1f, 11.5f..12.05f), gap = 0.25f, restart = 0.48f)
        assertEquals(2, cold.opening.size)
        assertEquals(cold.opening[0].outEnd, cold.opening[1].outStart, 1e-5f)
        assertEquals(9.95f, cold.prelude!!.sourceStart, 1e-5f)
        assertEquals(12.05f, cold.prelude!!.sourceEnd, 1e-5f)
        assertEquals(1.7f, cold.preludeLength, 1e-4f)
        assertEquals(1.95f, cold.bodyStart, 1e-4f)
        assertEquals(0.48f, cold.segments.first().sourceStart, 1e-5f)
        // The first word lands as the transition resolves, everything after it moves alike.
        val body = cold.remap(Transcript(Language.Persian, words)).words
        assertEquals(1.97f, body.first().range.startMs / 1000f, 0.002f)
        assertEquals(plain.duration + 1.95f - plain.segments.first().outStart + (plain.segments.first().sourceStart - 0.48f), cold.duration, 1e-3f)
        val opening = cold.preludeWords(Transcript(Language.Persian, words))
        assertEquals(listOf("سیصد", "تتر", "اولیه"), opening.map { it.text })
        assertEquals(1.15f + 0.1f, opening.last().range.startMs / 1000f, 0.002f)
        assertEquals(plain.cutCount + 2, cold.cutCount)
    }

    @Test
    fun cutsOnTheVideosOwnBeatSkipWholeBeats() {
        // Three pieces with pauses of 1.3 s and 0.3 s cut out, music at 120 bpm (a beat every 0.5 s).
        val plan = EditPlan(listOf(EditPlan.Segment(0f, 4f, 0f), EditPlan.Segment(5.3f, 8f, 4f), EditPlan.Segment(8.3f, 10f, 6.7f)))
        val beat = plan.onBeat(0.5f)
        // 1.3 s skipped becomes 1.0 s (two beats): the second piece starts 0.3 s earlier, in its pause.
        assertEquals(2, beat.segments.size, "a skip under a beat is no cut: ${beat.segments}")
        assertEquals(5.0f, beat.segments[1].sourceStart, 1e-4f)
        assertEquals(4f, beat.segments[1].outStart, 1e-4f)
        assertEquals(10f, beat.segments[1].sourceEnd, 1e-4f)
        beat.segments.zipWithNext { a, b ->
            val skip = b.sourceStart - a.sourceEnd
            assertEquals(0f, skip % 0.5f, 1e-3f, "skip $skip is whole beats")
            assertEquals(a.outEnd, b.outStart, 1e-4f, "the output runs on without a gap")
        }
        // 4 s, then 5.0..10 s: one cut, 9 s in all.
        assertEquals(9f, beat.segments.last().outEnd, 1e-3f)
    }
}
