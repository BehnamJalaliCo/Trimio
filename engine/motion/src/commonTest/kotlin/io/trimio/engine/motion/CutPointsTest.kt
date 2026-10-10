package io.trimio.engine.motion

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.motion.score.CutPoints
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cut points on synthetic speech: voiced bursts with known true edges, a room-tone floor at about
 * -66 dBFS, and recogniser timings deliberately 100–150 ms off.
 */
class CutPointsTest {
    private val rate = 48_000

    /** A voice under construction: room tone, then sounds added at exact source times. */
    private class Take(seconds: Float, val rate: Int) {
        val x = FloatArray((seconds * rate).toInt())

        init {
            var seed = 7
            for (i in x.indices) {
                seed = seed * 1_103_515_245 + 12_345
                x[i] = (((seed ushr 16) and 0x7FFF) / 32_768f - 0.5f) * 0.002f
            }
        }

        /** A vowel: harmonics at [amp] with 6 ms edges; [decay] adds an exponential tail after [b]. */
        fun vowel(a: Float, b: Float, amp: Float = 0.3f, decay: Float = 0f) {
            val end = b + decay * 4f
            for (i in (a * rate).toInt() until minOf(x.size, (end * rate).toInt())) {
                val t = i / rate.toFloat()
                val edge = minOf(1f, (t - a) / 0.006f)
                val env = if (t <= b) minOf(edge, 1f) else if (decay > 0f) exp(-(t - b) / decay) else 0f
                val p = 2f * PI.toFloat() * 160f * t
                x[i] += amp * env * (0.7f * sin(p) + 0.3f * sin(3f * p))
            }
        }

        /** A consonant's burst: a short noisy release at [amp]. */
        fun burst(a: Float, b: Float, amp: Float) {
            var seed = 99
            for (i in (a * rate).toInt() until (b * rate).toInt()) {
                seed = seed * 1_103_515_245 + 12_345
                x[i] += (((seed ushr 16) and 0x7FFF) / 32_768f - 0.5f) * 2f * amp
            }
        }

        fun pcm() = PcmAudio(x, rate)
    }

    @Test
    fun theSpeakersLevelIgnoresRoomTone() {
        val take = Take(6f, rate).apply { vowel(1f, 2f); vowel(4f, 4.5f) }
        val level = CutPoints(take.pcm()).speechDb
        // 0.3 × (0.7, 0.3) harmonics: RMS ≈ 0.162 → about -15.8 dBFS.
        assertEquals(-15.8f, level, 1.5f)
    }

    @Test
    fun anOnsetHeardLateIsCutInTheValleyBeforeIt() {
        // Word one ends at 1.0 s, word two truly starts at 1.30 s; whisper says 1.42 s.
        val take = Take(3f, rate).apply { vowel(0.5f, 1.0f); vowel(1.30f, 1.8f) }
        val cut = CutPoints(take.pcm()).cutIn(1.42f, previousEnd = 1.0f)
        assertTrue(cut.at <= 1.30f - CutPoints.FADE + 0.004f, "the fade-in is done before the onset: ${cut.at}")
        assertTrue(cut.at >= 1.30f - 0.15f, "and close to it: ${cut.at}")
        assertTrue(cut.clean, "in room tone: ${cut.relative} dB")
    }

    @Test
    fun aTailIsNeverClipped() {
        // The vowel stops driving at 1.0 s but rings down (25 ms time constant); whisper ends it at 0.9 s.
        val take = Take(3f, rate).apply { vowel(0.5f, 1.0f, decay = 0.025f); vowel(1.6f, 2f) }
        val voice = CutPoints(take.pcm())
        val cut = voice.cutOut(0.9f, nextStart = 1.6f)
        assertTrue(cut.at >= 1.0f + 0.05f, "after the ring-down: ${cut.at}")
        assertTrue(cut.at < 1.6f, "before the next word: ${cut.at}")
        assertTrue(cut.clean && voice.energyDb(cut.at) <= voice.speechDb - CutPoints.DECAY_DB, "decayed: ${cut.relative} dB")
    }

    @Test
    fun aFinalConsonantAfterItsClosureStaysIn() {
        // «…ت»: the vowel, a 45 ms closure (silent), then a 25 ms release; whisper ends the word in the closure.
        val take = Take(3f, rate).apply { vowel(0.5f, 1.0f); burst(1.045f, 1.07f, 0.12f); vowel(1.7f, 2.1f) }
        val cut = CutPoints(take.pcm()).cutOut(1.02f, nextStart = 1.7f)
        assertTrue(cut.at >= 1.07f, "the release is kept: ${cut.at}")
        assertTrue(cut.clean)
    }

    @Test
    fun wordsRunTogetherAreCutInTheirDipAndSaySo() {
        // No pause: the second word starts 12 dB down for 25 ms, then full. Whisper ends word one 80 ms early.
        val take = Take(3f, rate).apply { vowel(0.5f, 1.0f); vowel(1.0f, 1.025f, amp = 0.075f); vowel(1.025f, 1.5f) }
        val voice = CutPoints(take.pcm())
        val cut = voice.cutOut(0.92f, nextStart = 1.05f)
        assertTrue(cut.at in 1.0f..1.03f, "in the dip: ${cut.at}")
        assertFalse(cut.clean, "a cut inside speech is reported as such")
    }

    @Test
    fun aNeighbourIsNeverFadedInto() {
        // A tiny 30 ms gap between words: the out cut stays before the next word's onset.
        val take = Take(3f, rate).apply { vowel(0.5f, 1.0f); vowel(1.03f, 1.5f) }
        val cut = CutPoints(take.pcm()).cutOut(1.1f, nextStart = 1.12f)
        assertTrue(cut.at in 1.0f..1.035f, "in the gap: ${cut.at}")
    }
}
