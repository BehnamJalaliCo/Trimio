package io.trimio.engine.perception.beats

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.audio.Resampler
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Fft
import io.trimio.engine.perception.Perception
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Beats and downbeats of a piece of music, in seconds. [bpm] is the median tempo; [measured] is
 * false when they came from the model-free fallback. Consumers (music edit, rhythm of cuts and
 * graphic hits) snap to these; [beatsPerBar] is the most common count between downbeats.
 */
data class Beats(val beats: List<Double>, val downbeats: List<Double>, val measured: Boolean) {
    val bpm: Double?
        get() {
            if (beats.size < 2) return null
            val gaps = beats.zipWithNext { a, b -> b - a }.sorted()
            return 60.0 / gaps[gaps.size / 2]
        }

    val beatsPerBar: Int
        get() = downbeats.zipWithNext { a, b -> beats.count { it >= a - EPS && it < b - EPS } }
            .filter { it in 2..MAX_BAR }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: DEFAULT_BAR

    /** The beat nearest [t] within [tolerance] seconds. */
    fun nearestBeat(t: Double, tolerance: Double = Double.MAX_VALUE): Double? =
        beats.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= tolerance }

    fun nearestDownbeat(t: Double, tolerance: Double = Double.MAX_VALUE): Double? =
        downbeats.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) <= tolerance }

    companion object {
        private const val EPS = 1e-3
        private const val MAX_BAR = 7
        private const val DEFAULT_BAR = 4
        val None = Beats(emptyList(), emptyList(), measured = false)
    }
}

/**
 * Beat This! (Foscarin, Schlüter & Widmer, ISMIR 2024; CPJKU, MIT code and weights), checkpoint
 * final0. The host owns the features and the chunking, and both follow the reference exactly:
 * 22.05 kHz mono, STFT n_fft 1024 / hop 441 (50 frames per second) with a periodic Hann window
 * and reflect padding, magnitudes / √1024, the reference's 128-band mel filters, log1p(1000·x);
 * 1500-frame chunks with 6-frame borders (`keep_first`); then the "minimal" postprocessing: peaks
 * of the logits within ±3 frames above 0, merged when adjacent, each downbeat snapped to its
 * nearest beat. No DBN (madmom's model files are non-commercial).
 */
class BeatThis(private val filters: Array<FloatArray>, private val window: FloatArray) {
    /** The log-mel spectrogram, frames × 128. */
    fun spectrogram(audio22k: FloatArray): Array<FloatArray> {
        val mags = Fft.magnitudes(audio22k, N_FFT, HOP, window, scale = 1f / sqrt(N_FFT.toFloat()))
        return Array(mags.size) { f ->
            FloatArray(filters.size) { m ->
                var s = 0f
                val row = filters[m]
                val mag = mags[f]
                for (b in row.indices) s += row[b] * mag[b]
                ln(1f + LOG_MULTIPLIER * s)
            }
        }
    }

    /** Beat and downbeat logits per frame for the whole spectrogram. */
    fun logits(net: Network, spect: Array<FloatArray>): Pair<FloatArray, FloatArray> {
        val n = spect.size
        val beat = FloatArray(n) { MISSING }
        val down = FloatArray(n) { MISSING }
        val starts = starts(n)
        // keep_first: later chunks are written first so earlier ones overwrite the overlap.
        for (start in starts.reversed()) {
            // spect[max(start, 0) until min(start + CHUNK, n)], zero-padded left for a negative start and by up to a border on the right.
            val from = max(start, 0)
            val until = min(start + CHUNK, n)
            val left = max(0, -start)
            val right = max(0, min(BORDER, start + CHUNK - n))
            val length = left + (until - from) + right
            val data = FloatArray(length * MELS)
            for (k in 0 until until - from) spect[from + k].copyInto(data, (left + k) * MELS)
            val out = net.run(mapOf(net.inputNames.first() to Tensor.of(longArrayOf(1, length.toLong(), MELS.toLong()), data)))
            val b = out.getValue("beat").requireFloats()
            val d = out.getValue("downbeat").requireFloats()
            for (k in BORDER until length - BORDER) {
                val frame = start + k
                if (frame in 0 until n) {
                    beat[frame] = b[k]
                    down[frame] = d[k]
                }
            }
        }
        return beat to down
    }

    companion object {
        const val RATE = 22_050
        const val N_FFT = 1024
        const val HOP = 441
        const val FPS = 50.0
        const val MELS = 128
        const val CHUNK = 1500
        const val BORDER = 6
        private const val LOG_MULTIPLIER = 1000f
        private const val MISSING = -1000f
        private const val PEAK = 3

        /** The reference window and mel filters from the export's frontend.json. */
        fun fromFrontend(json: String): BeatThis {
            val o = Json.parseToJsonElement(json).jsonObject
            fun floats(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val window = floats(o.getValue("window"))
            val filters = o.getValue("mel_filters").jsonArray.map(::floats).toTypedArray()
            require(window.size == N_FFT && filters.size == MELS) { "unexpected frontend: window ${window.size}, filters ${filters.size}" }
            return BeatThis(filters, window)
        }

        /** split_piece: chunk starts, the last moved so no chunk is short. */
        fun starts(n: Int): List<Int> {
            val step = CHUNK - 2 * BORDER
            val starts = generateSequence(-BORDER) { it + step }.takeWhile { it < n - BORDER }.toMutableList()
            if (starts.isEmpty()) starts += -BORDER
            if (n > step) starts[starts.lastIndex] = n - (CHUNK - BORDER)
            return starts
        }

        /** The reference "minimal" postprocessing. */
        fun peaks(beat: FloatArray, down: FloatArray): Beats {
            val beats = dedupe(peakFrames(beat)).map { it / FPS }
            val downbeats = dedupe(peakFrames(down)).map { it / FPS }
                .map { d -> beats.minByOrNull { abs(it - d) } ?: d }.distinct().sorted()
            return Beats(beats, downbeats, measured = true)
        }

        private fun peakFrames(x: FloatArray): List<Int> = x.indices.filter { i ->
            val lo = max(0, i - PEAK)
            val hi = min(x.size - 1, i + PEAK)
            x[i] > 0f && (lo..hi).all { x[it] <= x[i] }
        }

        /** deduplicate_peaks(width=1): runs of adjacent frames collapse to their running mean. */
        private fun dedupe(frames: List<Int>): List<Double> {
            if (frames.isEmpty()) return emptyList()
            val out = ArrayList<Double>()
            var p = frames[0].toDouble()
            var c = 1
            for (f in frames.drop(1)) {
                if (f - p <= 1.0) {
                    c++
                    p += (f - p) / c
                } else {
                    out += p
                    p = f.toDouble()
                    c = 1
                }
            }
            out += p
            return out
        }
    }
}

/**
 * Beats for user-imported music: Beat This! when installed (it follows tempo changes and finds
 * downbeats), else [fallback] (the engine's constant-tempo tracker, wired by the caller).
 */
class BeatFinder(
    private val perception: Perception,
    private val fallback: (PcmAudio) -> Beats? = { null },
) {
    suspend fun beats(audio: PcmAudio): Beats {
        val model = if (perception.has(MediaAddOns.beats)) perception.readText(MediaAddOns.beatsFrontend)?.let { runCatching { BeatThis.fromFrontend(it) }.getOrNull() } else null
        val found = model?.let { beatThis ->
            val x = if (audio.sampleRate == BeatThis.RATE) audio.samples else Resampler().resample(audio, BeatThis.RATE).samples
            val spect = beatThis.spectrogram(x)
            perception.network("beats", MediaAddOns.beats) { net -> beatThis.logits(net, spect) }?.let { (b, d) -> BeatThis.peaks(b, d) }
        }
        if (found != null) return found
        perception.note("beats: onset-tracker fallback")
        return fallback(audio) ?: Beats.None
    }
}
