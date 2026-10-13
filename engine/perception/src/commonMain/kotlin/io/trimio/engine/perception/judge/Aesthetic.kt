package io.trimio.engine.perception.judge

import io.trimio.engine.perception.Box
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.face.BusyMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cheap, explainable measurements of a rendered frame, the inputs of the learned look score:
 * exposure and contrast, colourfulness (Hasler–Süsstrunk), sharpness (Laplacian energy), clutter,
 * how much ink sits at the frame edge, left/right and top/bottom balance, and — when known — how
 * much of the face the graphics cover and how much text is on screen.
 */
object AestheticFeatures {
    val NAMES = listOf(
        "brightness", "contrast", "colorfulness", "saturation", "sharpness", "clutter",
        "edgeInk", "balanceX", "balanceY", "faceCovered", "textCoverage", "darkShare", "clipShare",
    )

    /** Features of [frame]; [face] and [text] come from the face and OCR add-ons when available. */
    fun of(frame: Frame, face: Box? = null, graphics: List<Box> = emptyList(), text: List<Box> = emptyList()): FloatArray {
        val f = if (frame.width > MAX_SIDE || frame.height > MAX_SIDE) frame.resized(frame.width * MAX_SIDE / max(frame.width, frame.height), frame.height * MAX_SIDE / max(frame.width, frame.height)) else frame
        val n = f.width * f.height
        var sum = 0.0
        var sq = 0.0
        var rg = 0.0
        var rg2 = 0.0
        var yb = 0.0
        var yb2 = 0.0
        var sat = 0.0
        var dark = 0
        var clip = 0
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val r = f.r(x, y)
            val g = f.g(x, y)
            val b = f.b(x, y)
            val l = f.luma(x, y)
            sum += l
            sq += l * l
            val a = (r - g).toDouble()
            val c = 0.5 * (r + g) - b
            rg += a
            rg2 += a * a
            yb += c
            yb2 += c * c
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (l < DARK) dark++
            if (l > BRIGHT) clip++
        }
        val mean = sum / n
        val std = sqrt(max(0.0, sq / n - mean * mean))
        val sdRg = sqrt(max(0.0, rg2 / n - (rg / n) * (rg / n)))
        val sdYb = sqrt(max(0.0, yb2 / n - (yb / n) * (yb / n)))
        val colorful = sqrt(sdRg * sdRg + sdYb * sdYb) + COLOR_MEAN * sqrt((rg / n) * (rg / n) + (yb / n) * (yb / n))
        val busy = BusyMap.of(f)
        val covered = face?.let { fb -> graphics.sumOf { it.intersection(fb).toDouble() } / max(fb.area.toDouble(), 1e-6) } ?: 0.0
        return floatArrayOf(
            (mean / BYTE).toFloat(), (std / BYTE).toFloat(), (colorful / BYTE).toFloat(), (sat / n).toFloat(),
            sharpness(f), busy.values.average().toFloat(), edgeInk(busy), balance(busy, horizontal = true), balance(busy, horizontal = false),
            min(1.0, covered).toFloat(), text.sumOf { it.area.toDouble() }.toFloat().coerceAtMost(1f), dark.toFloat() / n, clip.toFloat() / n,
        )
    }

    /** Mean absolute Laplacian of luma, scaled to about 0..1. */
    private fun sharpness(f: Frame): Float {
        var s = 0.0
        var n = 0
        for (y in 1 until f.height - 1) for (x in 1 until f.width - 1) {
            val lap = 4 * f.luma(x, y) - f.luma(x - 1, y) - f.luma(x + 1, y) - f.luma(x, y - 1) - f.luma(x, y + 1)
            s += abs(lap)
            n++
        }
        return if (n == 0) 0f else (s / n / LAPLACE_SCALE).toFloat().coerceAtMost(1f)
    }

    /** Busyness in the outer ring of cells: graphics touching the frame edge look accidental. */
    private fun edgeInk(m: BusyMap): Float {
        var s = 0f
        var n = 0
        for (r in 0 until m.rows) for (c in 0 until m.cols) {
            if (r == 0 || c == 0 || r == m.rows - 1 || c == m.cols - 1) {
                s += m[c, r]
                n++
            }
        }
        return if (n == 0) 0f else s / n
    }

    /** |left − right| (or top − bottom) share of busyness: 0 balanced, 1 all on one side. */
    private fun balance(m: BusyMap, horizontal: Boolean): Float {
        var a = 0f
        var b = 0f
        for (r in 0 until m.rows) for (c in 0 until m.cols) {
            val first = if (horizontal) c < m.cols / 2 else r < m.rows / 2
            if (first) a += m[c, r] else b += m[c, r]
        }
        return if (a + b <= 0f) 0f else abs(a - b) / (a + b)
    }

    private const val MAX_SIDE = 256
    private const val BYTE = 255.0
    private const val DARK = 16f
    private const val BRIGHT = 245f
    private const val COLOR_MEAN = 0.3
    private const val LAPLACE_SCALE = 40.0
}

/**
 * The learned look score: a small two-layer network (or a linear model when [hidden] is empty)
 * over standardised [AestheticFeatures], trained on the CPU from the jury's scores of rendered
 * frames. Its weights are a few hundred numbers in JSON; scoring costs well under a millisecond.
 */
@Serializable
data class AestheticHead(
    val features: List<String>,
    val mean: List<Float>,
    val scale: List<Float>,
    val hidden: List<List<Float>> = emptyList(),
    val hiddenBias: List<Float> = emptyList(),
    val weights: List<Float>,
    val bias: Float,
    /** Held-out quality of the fit (Spearman rank correlation with the jury), recorded at training. */
    val heldOutSpearman: Float = 0f,
    val samples: Int = 0,
) {
    /** The predicted jury score (the jury's own 0..10 scale). */
    fun score(features: FloatArray): Float {
        val x = FloatArray(features.size) { (features[it] - mean[it]) / scale[it] }
        val h = if (hidden.isEmpty()) x else FloatArray(hidden.size) { j -> tanh(hidden[j].indices.sumOf { (hidden[j][it] * x[it]).toDouble() }.toFloat() + hiddenBias[j]) }
        return (h.indices.sumOf { (weights[it] * h[it]).toDouble() }.toFloat() + bias).coerceIn(0f, MAX_SCORE)
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private const val MAX_SCORE = 10f
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): AestheticHead = JSON.decodeFromString(serializer(), text)

        private fun tanh(x: Float): Float {
            val e = exp(2.0 * x.coerceIn(-SATURATE, SATURATE))
            return ((e - 1) / (e + 1)).toFloat()
        }

        private const val SATURATE = 15f
    }
}
