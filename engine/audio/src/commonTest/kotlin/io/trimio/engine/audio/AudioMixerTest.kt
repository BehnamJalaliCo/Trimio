package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioMixerTest {
    private val rate = 48_000

    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    private fun timeline(durationMs: Long, vararg clips: io.trimio.core.model.timeline.Clip) =
        Timeline(styleId = "test", seed = 0, canvas = CanvasSpec(), durationMs = durationMs, clips = clips.toList())

    @Test
    fun cutsShortenTheVoiceWithoutClicks() = runTest {
        // 3 s of a 300 Hz tone; cut 1.0-2.0 s → 2 s output.
        val voice = PcmAudio(Synth.voice(300.0, -12.0, 3_000, rate), rate)
        val out = AudioMixer().mix(timeline(2_000, CutClip(TimeRange(1_000, 2_000), CutReason.Silence)), voice, 3_000, emptyList()) { null }
        assertEquals(2 * rate, out.size)
        // At the join (1.0 s) the crossfade keeps neighbouring samples close: no step discontinuity.
        val join = rate
        val maxStep = (join - 400 until join + 400).maxOf { abs(out.samples[it + 1] - out.samples[it]) }
        val typicalStep = (rate / 2 until rate / 2 + 800).maxOf { abs(out.samples[it + 1] - out.samples[it]) }
        assertTrue(maxStep <= typicalStep * 1.5f, "click at the cut: step $maxStep vs typical $typicalStep")
    }

    @Test
    fun musicDucksUnderSpeech() = runTest {
        val music = PcmAudio(Synth.sine(220.0, -12.0, 1_000, rate), rate)
        val t = timeline(4_000, MusicClip(TimeRange(0, 4_000), assetId = "bed", gainDb = 0f))
        val out = AudioMixer().mix(t, null, 4_000, listOf(TimeRange(2_000, 4_000))) { music }
        val before = rms(out.samples, (0.5 * rate).toInt(), (1.5 * rate).toInt())
        val during = rms(out.samples, (2.8 * rate).toInt(), (3.8 * rate).toInt())
        val reduction = 20 * log10(during / before)
        assertEquals(-12.0, reduction, 1.0)
        // Look-ahead: already ducked by the time speech starts.
        val atOnset = rms(out.samples, 2 * rate - 480, 2 * rate + 480)
        assertTrue(20 * log10(atOnset / before) < -6, "music must already be down when speech begins")
    }

    @Test
    fun sfxLandsAtItsTimeAndLimiterHoldsCeiling() = runTest {
        val loud = PcmAudio(FloatArray(rate / 10) { if (it % 2 == 0) 1f else -1f }, rate)
        val t = timeline(1_000, SfxClip(TimeRange(500, 600), assetId = "pop", gainDb = 6f))
        val out = AudioMixer().mix(t, null, 1_000, emptyList()) { loud }
        assertTrue(out.samples.take(rate / 2 - 10).all { it == 0f }, "silence before the effect")
        val peakDb = 20 * log10(out.samples.maxOf { abs(it) }.toDouble())
        assertTrue(peakDb <= -0.99, "limiter let $peakDb dBFS through")
        assertTrue(peakDb > -3, "effect should be loud, got $peakDb")
    }
}
