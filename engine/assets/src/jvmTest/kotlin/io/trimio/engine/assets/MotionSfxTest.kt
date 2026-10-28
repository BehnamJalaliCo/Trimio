package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A move's sound: its length is the move's, its level follows the move's speed, its peak is the landing. */
class MotionSfxTest {
    private val rate = 48_000
    private val sfx = ProceduralSfx(rate)

    private fun rms(x: FloatArray) = sqrt(x.sumOf { (it * it).toDouble() } / x.size).toFloat()

    /** Where the 10 ms energy is highest: the swell's peak, the instant to land on the cut. */
    private fun peakAt(x: FloatArray): Int {
        val w = rate / 100
        var best = 0
        var bestEnergy = 0.0
        for (i in 0..x.size - w) {
            val e = (i until i + w).sumOf { (x[it] * x[it]).toDouble() }
            if (e > bestEnergy) {
                bestEnergy = e
                best = i
            }
        }
        return best + w / 2
    }

    @Test
    fun whooshLengthFollowsTheMotionDuration() {
        val quick = assertNotNull(sfx.generateMotion("whoosh", seed = 1, durationMs = 300, speed = 0.5f), "300 ms move")
        val long = assertNotNull(sfx.generateMotion("whoosh", seed = 1, durationMs = 700, speed = 0.5f), "700 ms move")
        assertEquals(300L, quick.durationMs, "the sound is the move's length")
        assertEquals(700L, long.durationMs, "the sound is the move's length")
    }

    @Test
    fun whooshIntensityAndFilterFollowTheSpeed() {
        val fast = assertNotNull(sfx.generateMotion("whoosh", seed = 2, durationMs = 400, speed = 0.95f), "fast move")
        val slow = assertNotNull(sfx.generateMotion("whoosh", seed = 2, durationMs = 400, speed = 0.15f), "slow move")
        assertTrue(rms(fast.samples) > rms(slow.samples) * 1.5f, "a faster move is hotter: ${rms(fast.samples)} vs ${rms(slow.samples)}")
        // The filter sweeps higher: more of the energy sits up top (a level-free measure).
        fun brightness(p: PcmAudio): Double {
            var diff = 0.0
            for (i in 1 until p.size) diff += abs(p.samples[i] - p.samples[i - 1])
            return diff / (rms(p.samples) * p.size)
        }
        assertTrue(brightness(fast) > brightness(slow), "a faster move is brighter: ${brightness(fast)} vs ${brightness(slow)}")
    }

    @Test
    fun theSwellPeaksOnTheLanding() {
        val pcm = assertNotNull(sfx.generateMotion("swoosh", seed = 3, durationMs = 500, speed = 0.8f), "swoosh")
        val landing = (pcm.size * ProceduralSfx.MOTION_PEAK).toInt()
        assertTrue(abs(peakAt(pcm.samples) - landing) < rate * 0.03, "peak at ${peakAt(pcm.samples)} samples, the landing at $landing")
    }

    @Test
    fun variantsAndJitterKeepRepeatsFromSoundingCloned() {
        assertTrue(ProceduralSfx.MOTION_VARIANTS in 3..5, "the research asks for 3–5 takes, got ${ProceduralSfx.MOTION_VARIANTS}")
        val takes = (0 until ProceduralSfx.MOTION_VARIANTS).map {
            assertNotNull(sfx.generateMotion("whoosh", seed = 4, durationMs = 400, speed = 0.7f, variant = it), "take $it")
        }
        for (a in takes.indices) for (b in a + 1 until takes.size) {
            assertFalse(takes[a].samples.contentEquals(takes[b].samples), "takes $a and $b are the same sound")
        }
        val first = assertNotNull(sfx.generateMotion("whoosh", seed = 5, durationMs = 400, speed = 0.7f), "seed 5")
        val second = assertNotNull(sfx.generateMotion("whoosh", seed = 6, durationMs = 400, speed = 0.7f), "seed 6")
        assertFalse(first.samples.contentEquals(second.samples), "the pitch/gain jitter changes every take")
        assertTrue(
            first.samples.contentEquals(assertNotNull(sfx.generateMotion("whoosh", seed = 5, durationMs = 400, speed = 0.7f), "seed 5 again").samples),
            "the same seed sounds the same",
        )
    }

    @Test
    fun motionSoundsEndCleanUnderTheCeiling() {
        for (speed in listOf(0.1f, 0.5f, 1f)) {
            val pcm = assertNotNull(sfx.generateMotion("whoosh", seed = 7, durationMs = 400, speed = speed), "speed $speed")
            val peak = pcm.samples.maxOf { abs(it) }
            assertTrue(peak <= 0.9f, "speed $speed peaks at $peak")
            assertTrue(abs(pcm.samples.last()) < 0.01f, "speed $speed ends without a click")
            assertTrue(pcm.samples.none { it.isNaN() }, "speed $speed is finite")
        }
    }
}
