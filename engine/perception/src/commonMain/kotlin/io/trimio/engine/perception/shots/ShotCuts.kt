package io.trimio.engine.perception.shots

import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.Perception
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A shot boundary: the source time the new shot starts, how sure the detector is, and whether it is a gradual transition. */
data class Cut(val time: Float, val confidence: Float, val gradual: Boolean = false)

/**
 * The shots of a video: hard cuts and gradual transitions. Edits cut on these, and B-roll inserts
 * start and end on them so a cutaway never splits a shot in an odd place.
 *
 * [measured] is false when the model-free histogram detector found them.
 */
class ShotCuts(val cuts: List<Cut>, val duration: Float, val measured: Boolean) {
    /** Shot spans [start, end) in seconds, covering the whole video. */
    fun shots(): List<ClosedFloatingPointRange<Float>> {
        val edges = listOf(0f) + cuts.map { it.time }.filter { it > 0f && it < duration } + duration
        return edges.zipWithNext { a, b -> a..b }.filter { it.endInclusive - it.start > 0f }
    }

    /** The cut nearest to [time] within [tolerance] seconds, if any: where an edit point should snap. */
    fun snap(time: Float, tolerance: Float = SNAP): Cut? = cuts.filter { abs(it.time - time) <= tolerance }.minByOrNull { abs(it.time - time) }

    /**
     * Spans where a B-roll cutaway of [minLength]..[maxLength] seconds fits inside one shot, after
     * trimming [margin] at both ends (never across a cut).
     */
    fun brollWindows(minLength: Float = 1.5f, maxLength: Float = 6f, margin: Float = 0.2f): List<ClosedFloatingPointRange<Float>> =
        shots().mapNotNull { s ->
            val start = s.start + margin
            val end = min(s.endInclusive - margin, start + maxLength)
            (start..end).takeIf { end - start >= minLength }
        }

    companion object {
        const val SNAP = 0.25f
    }
}

/**
 * TransNetV2 (Souček & Lokoč 2020, MIT): windows of 100 frames at 48×27 RGB (0..255; the graph
 * scales and applies the sigmoid), sliding by 50 with 25 frames of context on both sides, keeping
 * the middle 50 predictions — the upstream inference scheme. A run of frames above the threshold
 * is one transition; the cut lands after the last frame of the outgoing shot.
 */
object TransNet {
    const val WIDTH = 48
    const val HEIGHT = 27
    private const val WINDOW = 100
    private const val STEP = 50
    private const val CONTEXT = 25

    /** Per-frame cut probabilities (single-frame head, many-hot head) for frames already sized [WIDTH]×[HEIGHT]. */
    fun predict(net: Network, frames: List<Frame>): Pair<FloatArray, FloatArray> {
        val n = frames.size
        val single = FloatArray(n)
        val many = FloatArray(n)
        if (n == 0) return single to many
        val tail = CONTEXT + STEP - (if (n % STEP == 0) STEP else n % STEP)
        val padded = n + CONTEXT + tail
        fun frameAt(i: Int): Frame = frames[(i - CONTEXT).coerceIn(0, n - 1)]
        val plane = HEIGHT * WIDTH * Frame.CHANNELS
        var start = 0
        while (start + WINDOW <= padded) {
            val data = FloatArray(WINDOW * plane)
            for (k in 0 until WINDOW) {
                val px = frameAt(start + k).rgb
                for (j in 0 until plane) data[k * plane + j] = (px[j].toInt() and BYTE).toFloat()
            }
            val out = net.run(mapOf(net.inputNames.first() to Tensor.of(longArrayOf(1, WINDOW.toLong(), HEIGHT.toLong(), WIDTH.toLong(), 3), data)))
            val heads = net.outputNames.map { out.getValue(it).requireFloats() }
            for (k in CONTEXT until CONTEXT + STEP) {
                val frame = start + k - CONTEXT
                if (frame >= n) break
                single[frame] = heads[0][k]
                many[frame] = heads.getOrNull(1)?.get(k) ?: 0f
            }
            start += STEP
        }
        return single to many
    }

    /** Transitions from per-frame probabilities at [fps]. */
    fun cuts(single: FloatArray, many: FloatArray, fps: Float, threshold: Float = THRESHOLD): List<Cut> {
        val cuts = ArrayList<Cut>()
        var i = 0
        while (i < single.size) {
            if (single[i] <= threshold) {
                i++
                continue
            }
            var end = i
            var peak = single[i]
            while (end + 1 < single.size && single[end + 1] > threshold) {
                end++
                peak = max(peak, single[end])
            }
            val gradual = end - i >= GRADUAL_FRAMES || (i..end).any { many[it] > threshold } && end > i
            // The cut lands after the last frame of the outgoing shot: the middle of the run.
            cuts += Cut(((i + end) / 2 + 1) / fps, peak, gradual)
            i = end + 1
        }
        return cuts
    }

    private const val BYTE = 0xFF
    private const val THRESHOLD = 0.5f
    private const val GRADUAL_FRAMES = 3
}

/**
 * Cuts without the model: the colour-histogram difference between consecutive frames against a
 * running baseline (the "content" detector of PySceneDetect, BSD-3, reimplemented). Enough for
 * hard cuts in talking-head footage; it misses most dissolves.
 */
object HistogramCuts {
    private const val BINS = 16
    private const val THRESHOLD = 0.35f
    private const val RATIO = 4f
    private const val MIN_SHOT = 0.4f

    fun detect(frames: List<Frame>, fps: Float): List<Cut> {
        if (frames.size < 2) return emptyList()
        val hist = frames.map(::histogram)
        val diffs = FloatArray(frames.size) { i -> if (i == 0) 0f else distance(hist[i - 1], hist[i]) }
        val cuts = ArrayList<Cut>()
        for (i in 1 until diffs.size) {
            val window = diffs.slice(max(1, i - WINDOW) until i)
            val base = if (window.isEmpty()) 0f else window.sorted()[window.size / 2]
            val last = cuts.lastOrNull()?.time ?: -MIN_SHOT
            if (diffs[i] > THRESHOLD && diffs[i] > base * RATIO && i / fps - last >= MIN_SHOT) cuts += Cut(i / fps, min(1f, diffs[i]))
        }
        return cuts
    }

    /** A normalised 16-level-per-channel histogram (48 bins). */
    private fun histogram(f: Frame): FloatArray {
        val h = FloatArray(BINS * 3)
        val n = f.width * f.height
        for (p in 0 until n) for (c in 0 until 3) h[c * BINS + ((f.rgb[p * 3 + c].toInt() and BYTE) * BINS shr BITS)] += 1f
        for (k in h.indices) h[k] /= n.toFloat()
        return h
    }

    /** Half the L1 distance per channel, averaged: 0 identical, 1 disjoint. */
    private fun distance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (k in a.indices) d += abs(a[k] - b[k])
        return d / 6f
    }

    private const val WINDOW = 25
    private const val BYTE = 0xFF
    private const val BITS = 8
}

/** Shot detection: TransNetV2 when installed, else the histogram detector. Frames must be [TransNet.WIDTH]×[TransNet.HEIGHT]. */
class ShotDetector(private val perception: Perception) {
    suspend fun detect(frames: List<Frame>, fps: Float): ShotCuts {
        val duration = frames.size / fps
        val modelled = perception.network("shots", MediaAddOns.shots) { net ->
            val (single, many) = TransNet.predict(net, frames)
            TransNet.cuts(single, many, fps)
        }
        return modelled?.let { ShotCuts(it, duration, measured = true) } ?: ShotCuts(HistogramCuts.detect(frames, fps), duration, measured = false)
    }
}
