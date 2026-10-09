package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioDspTest {

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        sqrt((from until to).sumOf { (x[it] * x[it]).toDouble() } / (to - from))

    // --- Loudness ---------------------------------------------------------------------------

    @Test
    fun referenceSineMeasuresCorrectLoudnessAtAnyRate() {
        // BS.1770: a 997 Hz sine at -23 dBFS peak on two channels reads -23 LUFS, so one channel
        // reads 3.01 dB lower.
        for (rate in listOf(48_000, 44_100, 16_000)) {
            val audio = PcmAudio(Synth.sine(997.0, -23.0, 5_000, rate), rate)
            val lufs = LoudnessMeter.measure(audio).integratedLufs
            assertEquals(-26.01, lufs, 0.2, "at $rate Hz")
        }
    }

    @Test
    fun silenceIsGatedOut() {
        val audio = PcmAudio(FloatArray(48_000 * 2), 48_000)
        assertTrue(LoudnessMeter.measure(audio).isSilent)
    }

    @Test
    fun relativeGateIgnoresQuietPauses() {
        val rate = 48_000
        val speech = Synth.sine(997.0, -23.0, 3_000, rate)
        val withPauses = Synth.concat(rate, speech, Synth.noise(-75.0, 3_000, rate), speech)
        assertEquals(-26.01, LoudnessMeter.measure(withPauses).integratedLufs, 0.3)
    }

    @Test
    fun normalizerReachesTargetAndRespectsCeiling() {
        val rate = 16_000
        // -34 dBFS needs ~+23 dB, inside the 24 dB boost limit.
        val quiet = PcmAudio(Synth.sine(440.0, -34.0, 4_000, rate), rate)
        val result = LoudnessNormalizer(targetLufs = -14.0).normalize(quiet)
        assertEquals(-14.0, LoudnessMeter.measure(result.audio).integratedLufs, 0.2)

        // A sine already peaking at -3 dBFS can only gain 2 dB before hitting the -1 dB ceiling.
        val peaky = PcmAudio(Synth.sine(440.0, -3.0, 4_000, rate), rate)
        val capped = LoudnessNormalizer(targetLufs = -2.0, ceilingDb = -1.0).normalize(peaky)
        val peakDb = 20 * log10(capped.audio.samples.maxOf { abs(it) }.toDouble())
        assertTrue(peakDb <= -0.99, "peak $peakDb dBFS exceeds the ceiling")
        assertEquals(2.0, capped.appliedGainDb, 0.05)
    }

    // --- Resampling -------------------------------------------------------------------------

    @Test
    fun resamplerKeepsPassbandAndRejectsAliases() {
        val resampler = Resampler()
        val inRate = 48_000
        val pass = resampler.resample(PcmAudio(Synth.sine(1_000.0, -6.0, 1_000, inRate), inRate), 16_000)
        assertEquals(16_000, pass.size)
        // Skip filter edges; amplitude of an in-band tone is preserved within 0.1 dB.
        val passRatio = rms(pass.samples, 800, 15_200) / rms(Synth.sine(1_000.0, -6.0, 1_000, inRate))
        assertEquals(0.0, 20 * log10(passRatio), 0.1)

        // 10 kHz is above the new 8 kHz Nyquist: it must not fold back as a 6 kHz alias.
        val alias = resampler.resample(PcmAudio(Synth.sine(10_000.0, -6.0, 1_000, inRate), inRate), 16_000)
        val rejection = 20 * log10(rms(alias.samples, 800, 15_200) / rms(Synth.sine(10_000.0, -6.0, 1_000, inRate)))
        assertTrue(rejection < -60, "alias only attenuated by $rejection dB")
    }

    @Test
    fun resamplerHandlesNonIntegerRatios() {
        val out = Resampler().resample(PcmAudio(Synth.sine(440.0, -6.0, 2_000, 44_100), 44_100), 16_000)
        assertEquals(32_000, out.size)
        // Count rising zero crossings to confirm the frequency survived.
        var crossings = 0
        for (i in 1 until out.size) if (out.samples[i - 1] < 0f && out.samples[i] >= 0f) crossings++
        assertTrue(abs(crossings - 880) <= 3, "crossings $crossings")
    }

    // --- Voice activity ---------------------------------------------------------------------

    @Test
    fun vadFindsSpeechRegionsWithin50ms() {
        val rate = 16_000
        val room = { ms: Int -> Synth.noise(-58.0, ms, rate) }
        val audio = Synth.concat(
            rate,
            room(500), Synth.mix(Synth.voice(140.0, -14.0, 1_000, rate), room(1_000)),
            room(800), Synth.mix(Synth.voice(180.0, -16.0, 600, rate), room(600)),
            room(500),
        )
        val speech = VoiceActivityDetector().detect(audio)
        assertEquals(2, speech.size, "found $speech")
        assertEquals(500.0, speech[0].startMs.toDouble(), 50.0)
        assertEquals(2_300.0, speech[1].startMs.toDouble(), 50.0)
        // Ends include the hangover that protects word tails.
        assertTrue(speech[0].endMs in 1_500..1_700, "first end ${speech[0].endMs}")
        assertTrue(speech[1].endMs in 2_900..3_100, "second end ${speech[1].endMs}")
    }

    @Test
    fun vadMergesShortGapsAndDropsClicks() {
        val rate = 16_000
        val audio = Synth.concat(
            rate,
            Synth.noise(-60.0, 400, rate), Synth.voice(150.0, -14.0, 500, rate), Synth.noise(-60.0, 120, rate),
            Synth.voice(150.0, -14.0, 500, rate), Synth.noise(-60.0, 700, rate),
            Synth.voice(150.0, -14.0, 40, rate), Synth.noise(-60.0, 700, rate),
        )
        val speech = VoiceActivityDetector().detect(audio)
        assertEquals(1, speech.size, "a 120 ms breath is merged and a 40 ms click dropped: $speech")
    }

    // --- Pitch and emphasis -----------------------------------------------------------------

    @Test
    fun pitchTrackerFindsFundamental() {
        val rate = 16_000
        for (f0 in listOf(110.0, 150.0, 220.0, 320.0)) {
            val audio = PcmAudio(Synth.voice(f0, -12.0, 600, rate), rate)
            val track = PitchTracker().track(audio, hopMs = 10, frames = 50)
            val voiced = track.filter { it > 0f }.sorted()
            assertTrue(voiced.size > 30, "too few voiced frames for $f0 Hz")
            val median = voiced[voiced.size / 2]
            assertEquals(f0, median.toDouble(), f0 * 0.02, "f0 $f0")
        }
    }

    @Test
    fun pitchTrackerReportsNoiseAsUnvoiced() {
        val rate = 16_000
        val track = PitchTracker().track(PcmAudio(Synth.noise(-12.0, 600, rate), rate), hopMs = 10, frames = 50)
        assertTrue(track.count { it > 0f } < 10, "noise reported as voiced in ${track.count { it > 0f }} frames")
    }

    @Test
    fun stressedWordGetsHighestEmphasis() {
        val rate = 16_000
        val gap = { Synth.noise(-60.0, 150, rate) }
        // Five words; the fourth is louder and higher, like a stressed «سود».
        val parts = listOf(
            Synth.voice(130.0, -18.0, 350, rate), Synth.voice(125.0, -18.0, 300, rate),
            Synth.voice(135.0, -19.0, 350, rate), Synth.voice(190.0, -8.0, 450, rate),
            Synth.voice(120.0, -20.0, 300, rate),
        )
        val pieces = mutableListOf<FloatArray>()
        val words = mutableListOf<Word>()
        var cursor = 150L
        pieces += gap()
        parts.forEachIndexed { i, p ->
            val ms = p.size * 1000L / rate
            words += Word("w$i", TimeRange(cursor, cursor + ms), language = Language.Persian)
            pieces += p
            pieces += gap()
            cursor += ms + 150
        }
        val audio = Synth.concat(rate, *pieces.toTypedArray())
        val analyzer = ProsodyAnalyzer()
        val features = analyzer.features(audio, VoiceActivityDetector().detect(audio), -20f, 0f)
        val scores = analyzer.emphasis(features, words)

        assertEquals(3, scores.indices.maxBy { scores[it] }, "scores $scores")
        assertTrue(scores[3] > 0.7f && scores.filterIndexed { i, _ -> i != 3 }.all { it < 0.5f }, "scores $scores")
    }

    @Test
    fun waveformBarsAreNormalised() {
        val rate = 16_000
        val audio = Synth.concat(rate, Synth.sine(200.0, 0.0, 500, rate), FloatArray(8_000))
        val bars = Waveform.bars(audio, 10)
        assertEquals(10, bars.size)
        assertEquals(1f, bars.first(), 0.01f)
        assertEquals(0f, bars.last())
    }
}
