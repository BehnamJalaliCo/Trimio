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
}
