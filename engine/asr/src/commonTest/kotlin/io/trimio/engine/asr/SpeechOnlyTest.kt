package io.trimio.engine.asr

import io.trimio.core.model.audio.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpeechOnlyTest {
    private val rate = 16_000

    /** 10 s: speech at 1–3 s and 6–8 s (a ramp so pieces are recognisable), silence elsewhere. */
    private fun audio(): PcmAudio {
        val x = FloatArray(10 * rate)
        for (i in rate until 3 * rate) x[i] = 0.5f
        for (i in 6 * rate until 8 * rate) x[i] = -0.5f
        return PcmAudio(x, rate)
    }

    @Test
    fun keepsOnlySpeechJoinedByShortGaps() {
        val s = assertNotNull(SpeechOnly.of(audio(), listOf(1f..3f, 6f..8f), gapMs = 200))
        assertEquals((4.2f * rate).toInt(), s.audio.size)
        assertEquals(0.5f, s.audio.samples[0])
        assertEquals(0f, s.audio.samples[2 * rate + 100], "the gap is silence")
        assertEquals(-0.5f, s.audio.samples[(2.2f * rate).toInt() + 10])
        assertEquals(4f, s.speechSeconds, 1e-3f)
    }

    @Test
    fun tokenTimesMapBackToTheSourceTimeline() {
        val s = assertNotNull(SpeechOnly.of(audio(), listOf(1f..3f, 6f..8f), gapMs = 200))
        assertEquals(1000L, s.toSource(0, isStart = true))
        assertEquals(2500L, s.toSource(1500, isStart = true))
        // 2.3 s on the cut timeline is 0.1 s into the second piece (it starts at 2.2 s there, 6 s in the source).
        assertEquals(6100L, s.toSource(2300, isStart = true))
        // Inside the shortened gap: a start belongs to the next piece, an end to the previous one.
        assertEquals(6000L, s.toSource(2100, isStart = true))
        assertEquals(3000L, s.toSource(2100, isStart = false))
        assertEquals(8000L, s.toSource(4200, isStart = false))
    }

    @Test
    fun closeSpansMergeAndAlmostAllSpeechIsLeftAlone() {
        val merged = assertNotNull(SpeechOnly.of(audio(), listOf(1f..3f, 3.1f..3.5f, 6f..8f), gapMs = 200))
        assertEquals(2500L, merged.toSource(1500, isStart = true))
        assertEquals(6000L, merged.toSource(2700, isStart = true), "1–3.5 s is one piece of 2.5 s")
        assertNull(SpeechOnly.of(audio(), listOf(0f..9.9f)), "nothing worth cutting")
        assertNull(SpeechOnly.of(audio(), emptyList()), "no speech: decode everything")
    }
}
