package io.trimio.engine.perception.judge

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** One training example: a frame's [AestheticFeatures] and the jury's (or the owner's) score for it, 0..10. */
class RatedFrame(val features: FloatArray, val score: Float)

/**
 * Trains [AestheticHead] on the CPU in seconds: standardise, then either ridge regression (closed
 * form) or a one-hidden-layer tanh network (full-batch Adam with weight decay), whichever ranks
 * held-out frames better in k-fold cross-validation. Deterministic for a [seed].
 */
class AestheticTrainer(
    private val names: List<String> = AestheticFeatures.NAMES,
    private val folds: Int = 5,
    private val hidden: Int = 8,
    private val seed: Int = 7,
) {
    fun train(data: List<RatedFrame>): AestheticHead {
        require(data.size >= folds * 2) { "need at least ${folds * 2} rated frames, got ${data.size}" }
        val shuffled = data.shuffled(Random(seed))
        val linear = crossValidate(shuffled) { fit(it, mlp = false) }
        val network = crossValidate(shuffled) { fit(it, mlp = true) }
        val useNetwork = network > linear + MARGIN
        return fit(shuffled, useNetwork).copy(heldOutSpearman = if (useNetwork) network else linear, samples = data.size)
    }

    /** Mean held-out Spearman correlation over the folds. */
    private fun crossValidate(data: List<RatedFrame>, fitter: (List<RatedFrame>) -> AestheticHead): Float {
        val per = data.size / folds
        return (0 until folds).map { k ->
            val test = data.subList(k * per, if (k == folds - 1) data.size else (k + 1) * per)
            val train = data.filterIndexed { i, _ -> i < k * per || i >= k * per + test.size }
            val head = fitter(train)
            spearman(test.map { head.score(it.features) }, test.map { it.score })
        }.average().toFloat()
    }

    private fun fit(data: List<RatedFrame>, mlp: Boolean): AestheticHead {
        val d = names.size
        val mean = FloatArray(d) { j -> data.map { it.features[j] }.average().toFloat() }
        val scale = FloatArray(d) { j ->
            sqrt(data.map { (it.features[j] - mean[j]).let { v -> v * v } }.average()).toFloat().takeIf { it > EPS } ?: 1f
        }
        val x = data.map { r -> FloatArray(d) { (r.features[it] - mean[it]) / scale[it] } }
        val y = data.map { it.score }
        return if (mlp) network(x, y, mean, scale) else ridge(x, y, mean, scale)
    }

    private fun ridge(x: List<FloatArray>, y: List<Float>, mean: FloatArray, scale: FloatArray): AestheticHead {
        val d = mean.size
        val yMean = y.average()
        // (XᵀX + λI) w = Xᵀ(y − ȳ), solved by Gauss–Jordan elimination.
        val a = Array(d) { DoubleArray(d + 1) }
        for (r in x.indices) for (i in 0 until d) {
            for (j in 0 until d) a[i][j] += x[r][i].toDouble() * x[r][j]
            a[i][d] += x[r][i] * (y[r] - yMean)
        }
        for (i in 0 until d) a[i][i] += LAMBDA
        val w = solve(a)
        return AestheticHead(names, mean.toList(), scale.toList(), weights = w.map { it.toFloat() }, bias = yMean.toFloat())
    }

    private fun network(x: List<FloatArray>, y: List<Float>, mean: FloatArray, scale: FloatArray): AestheticHead {
        val d = mean.size
        val rnd = Random(seed)
        val w1 = Array(hidden) { FloatArray(d) { (rnd.nextFloat() - 0.5f) * 2f / sqrt(d.toFloat()) } }
        val b1 = FloatArray(hidden)
        val w2 = FloatArray(hidden) { (rnd.nextFloat() - 0.5f) * 2f / sqrt(hidden.toFloat()) }
        var b2 = y.average().toFloat()
        val params = Adam(hidden * d + hidden + hidden + 1)
        repeat(EPOCHS) {
            val g1 = Array(hidden) { FloatArray(d) }
            val gb1 = FloatArray(hidden)
            val g2 = FloatArray(hidden)
            var gb2 = 0f
            for (r in x.indices) {
                val h = FloatArray(hidden) { j -> tanh(dot(w1[j], x[r]) + b1[j]) }
                val err = (dot(w2, h) + b2 - y[r]) * 2f / x.size
                gb2 += err
                for (j in 0 until hidden) {
                    g2[j] += err * h[j]
                    val back = err * w2[j] * (1 - h[j] * h[j])
                    gb1[j] += back
                    for (i in 0 until d) g1[j][i] += back * x[r][i]
                }
            }
            val flat = FloatArray(params.size)
            var k = 0
            for (j in 0 until hidden) for (i in 0 until d) flat[k++] = g1[j][i] + DECAY * w1[j][i]
            for (j in 0 until hidden) flat[k++] = gb1[j]
            for (j in 0 until hidden) flat[k++] = g2[j] + DECAY * w2[j]
            flat[k] = gb2
            val step = params.step(flat)
            k = 0
            for (j in 0 until hidden) for (i in 0 until d) w1[j][i] -= step[k++]
            for (j in 0 until hidden) b1[j] -= step[k++]
            for (j in 0 until hidden) w2[j] -= step[k++]
            b2 -= step[k]
        }
        return AestheticHead(
            names, mean.toList(), scale.toList(), hidden = w1.map { it.toList() }, hiddenBias = b1.toList(), weights = w2.toList(), bias = b2,
        )
    }

    private class Adam(val size: Int) {
        private val m = FloatArray(size)
        private val v = FloatArray(size)
        private var t = 0

        fun step(g: FloatArray): FloatArray {
            t++
            val c1 = 1f - pow(B1, t)
            val c2 = 1f - pow(B2, t)
            return FloatArray(size) { i ->
                m[i] = B1 * m[i] + (1 - B1) * g[i]
                v[i] = B2 * v[i] + (1 - B2) * g[i] * g[i]
                LR * (m[i] / c1) / (sqrt(v[i] / c2) + ADAM_EPS)
            }
        }

        private fun pow(b: Float, n: Int): Float = exp(n * kotlin.math.ln(b))

        companion object {
            const val B1 = 0.9f
            const val B2 = 0.999f
            const val LR = 0.01f
            const val ADAM_EPS = 1e-8f
        }
    }

    companion object {
        private const val LAMBDA = 1.0
        private const val EPOCHS = 600
        private const val DECAY = 1e-3f
        private const val EPS = 1e-6f
        private const val MARGIN = 0.02f

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        private fun tanh(x: Float): Float {
            val e = exp(2.0 * x.coerceIn(-15f, 15f))
            return ((e - 1) / (e + 1)).toFloat()
        }

        private fun solve(a: Array<DoubleArray>): DoubleArray {
            val n = a.size
            for (c in 0 until n) {
                val p = (c until n).maxByOrNull { abs(a[it][c]) } ?: c
                a[c] = a[p].also { a[p] = a[c] }
                val piv = a[c][c].takeIf { abs(it) > 1e-12 } ?: continue
                for (j in c..n) a[c][j] /= piv
                for (r in 0 until n) if (r != c) {
                    val f = a[r][c]
                    if (f != 0.0) for (j in c..n) a[r][j] -= f * a[c][j]
                }
            }
            return DoubleArray(n) { a[it][n] }
        }

        /** Spearman rank correlation (ties get their mean rank). */
        fun spearman(a: List<Float>, b: List<Float>): Float {
            if (a.size < 2) return 0f
            val ra = ranks(a)
            val rb = ranks(b)
            val ma = ra.average()
            val mb = rb.average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in ra.indices) {
                num += (ra[i] - ma) * (rb[i] - mb)
                da += (ra[i] - ma) * (ra[i] - ma)
                db += (rb[i] - mb) * (rb[i] - mb)
            }
            return if (da == 0.0 || db == 0.0) 0f else (num / sqrt(da * db)).toFloat()
        }

        private fun ranks(v: List<Float>): DoubleArray {
            val order = v.indices.sortedBy { v[it] }
            val out = DoubleArray(v.size)
            var i = 0
            while (i < order.size) {
                var j = i
                while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
                val rank = (i + j) / 2.0
                for (k in i..j) out[order[k]] = rank
                i = j + 1
            }
            return out
        }
    }
}
