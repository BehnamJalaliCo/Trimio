package io.trimio.engine.perception

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.perception.align.CtcAligner
import io.trimio.engine.perception.align.ForcedAligner
import io.trimio.engine.perception.beats.BeatThis
import io.trimio.engine.perception.beats.Beats
import io.trimio.engine.perception.speakers.Diarization
import io.trimio.engine.perception.speakers.Fbank
import io.trimio.engine.perception.speakers.SpeakerTurns
import io.trimio.engine.perception.speakers.Turn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioFeaturesTest {
    private fun word(text: String, s: Long, e: Long) = Word(text, TimeRange(s, e), language = Language.Persian)

    @Test
    fun fftFindsASineAtItsBin() {
        val n = 64
        val re = FloatArray(n) { sin(2 * PI * 5 * it / n).toFloat() }
        val im = FloatArray(n)
        Fft.transform(re, im)
        val mags = FloatArray(n / 2) { kotlin.math.sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(5, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2f, mags[5], 1e-3f)
    }

    @Test
    fun stftFramesFollowTorchCentering() {
        val frames = Fft.magnitudes(FloatArray(4410), 1024, 441, Fft.hann(1024))
        assertEquals(1 + 4410 / 441, frames.size)
        assertEquals(513, frames[0].size)
    }

    @Test
    fun beatThisChunksLikeTheReference() {
        assertEquals(listOf(-6), BeatThis.starts(1000))
        // 4000 frames: -6, 1482, then the last moved to end exactly at the piece end minus the border.
        assertEquals(listOf(-6, 1482, 4000 - 1494), BeatThis.starts(4000))
    }

    @Test
    fun minimalPostprocessingPicksPeaksAndSnapsDownbeats() {
        val beat = FloatArray(200) { -5f }
        val down = FloatArray(200) { -5f }
        for (f in listOf(10, 35, 60, 85, 110)) beat[f] = 3f
        beat[61] = 3f // adjacent duplicate → one peak at 60.5
        down[11] = 2f // a downbeat a frame off the beat
        val beats = BeatThis.peaks(beat, down)
        assertEquals(listOf(0.2, 0.7, 1.21, 1.7, 2.2), beats.beats.map { kotlin.math.round(it * 100) / 100.0 })
        assertEquals(listOf(0.2), beats.downbeats)
        assertEquals(120.0, beats.bpm!!, 3.0)
    }

    @Test
    fun beatsSnapAndCountBars() {
        val b = Beats((0 until 16).map { it * 0.5 }, listOf(0.0, 2.0, 4.0, 6.0), measured = true)
        assertEquals(4, b.beatsPerBar)
        assertEquals(1.5, b.nearestBeat(1.6))
        assertNull(b.nearestBeat(1.6, tolerance = 0.05))
        assertEquals(2.0, b.nearestDownbeat(2.7))
    }

    @Test
    fun ctcViterbiFindsTheTimesOfEachToken() {
        // Vocabulary: blank 0, "a" 5, "b" 6, " " 4. Frames: blank, a, a, blank, " ", b, blank.
        val aligner = CtcAligner(mapOf(" " to 4, "a" to 5, "b" to 6))
        val (targets, spans) = aligner.targets(listOf("A", "b."))
        assertContentEquals(intArrayOf(5, 4, 6), targets)
        assertEquals(listOf(0..0, 2..2), spans)
        val columns = intArrayOf(0, 5, 4, 6)
        val best = listOf(0, 1, 1, 0, 2, 3, 0)
        val logp = Array(best.size) { t -> FloatArray(columns.size) { c -> if (c == best[t]) -0.1f else -5f } }
        val path = assertNotNull(CtcAligner.viterbi(logp, targets, columns))
        assertContentEquals(intArrayOf(1, 4, 5), path[0])
        assertContentEquals(intArrayOf(2, 4, 5), path[1])
        assertNull(CtcAligner.viterbi(Array(2) { FloatArray(4) }, targets, columns), "too few frames")
    }

    @Test
    fun alignmentChunksStayShortAndCutAtPauses() {
        val words = (0 until 30).map { i -> word("w$i", i * 1000L, i * 1000L + 700) }
        val chunks = ForcedAligner(io.trimio.engine.perception.Perception(), chunkSeconds = 10f).chunks(words)
        assertEquals(words, chunks.flatten())
        assertTrue(chunks.all { (it.last().range.endMs - it.first().range.startMs) <= 10_000 })
    }

    @Test
    fun omnilingualTokensParse() {
        val a = CtcAligner.fromTokens("<s> 0\n<pad> 1\n  4\nا 5\nب 6\n")
        val (t, _) = a.targets(listOf("با", "اب"))
        assertContentEquals(intArrayOf(6, 5, 4, 5, 6), t)
    }

    @Test
    fun clusteringSeparatesTwoVoices() {
        fun unit(vararg v: Float): FloatArray {
            val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(v.size) { v[it] / n }
        }
        val e = listOf(unit(1f, 0.1f, 0f), unit(0.9f, 0.2f, 0f), unit(0f, 0.1f, 1f), unit(1f, 0f, 0.1f), unit(0.1f, 0f, 1f))
        assertContentEquals(intArrayOf(0, 0, 1, 0, 1), SpeakerTurns.cluster(e))
        val turns = SpeakerTurns.turns(listOf(0f..1f, 1.2f..2f, 2f..3f, 3f..4f, 4.5f..5f), intArrayOf(0, 0, 1, 0, 1))
        assertEquals(listOf(Turn(0, 0f, 2f), Turn(1, 2f, 3f), Turn(0, 3f, 4f), Turn(1, 4.5f, 5f)), turns)
    }

    @Test
    fun diarizationAssignsWordsToTurns() {
        val d = Diarization(listOf(Turn(0, 0f, 2f), Turn(1, 2.5f, 5f)), 2, measured = true)
        val words = listOf(word("a", 100, 400), word("b", 2600, 2800), word("c", 3000, 3400))
        assertEquals(listOf(0, 1, 1), d.speakersOf(words))
        assertEquals(listOf(0, 0), Diarization.single(5f).speakersOf(words.take(2)))
    }

    @Test
    fun fbankHasKaldiShapeAndZeroMean() {
        val x = FloatArray(16_000) { (0.3 * sin(2 * PI * 440 * it / 16_000.0)).toFloat() }
        val f = Fbank.compute(x)
        assertEquals((1 + (16_000 - 400) / 160) * 80, f.size)
        val frames = f.size / 80
        for (m in 0 until 80) assertTrue(abs((0 until frames).sumOf { f[it * 80 + m].toDouble() } / frames) < 1e-3)
        // 440 Hz lands in a low band: its energy (before the mean) is the frame's largest; check the shape only.
        assertTrue(f.all { it.isFinite() })
    }
}
