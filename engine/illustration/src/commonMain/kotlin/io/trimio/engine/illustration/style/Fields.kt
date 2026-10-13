package io.trimio.engine.illustration.style

import kotlin.math.max
import kotlin.math.min

/** Float-field filters on [w]×[h] row-major arrays (box blur, max dilation, bilinear sampling). */
object Fields {
    /** Box blur of radius [r], [passes] times (three ≈ a Gaussian of σ ≈ r). */
    fun blur(src: FloatArray, w: Int, h: Int, r: Int, passes: Int = 3): FloatArray {
        if (r <= 0) return src.copyOf()
        val a = src.copyOf()
        val b = FloatArray(a.size)
        repeat(passes) {
            boxH(a, b, w, h, r)
            boxV(b, a, w, h, r)
        }
        return a
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val base = y * w
            var acc = 0f
            for (k in -r..r) acc += src[base + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[base + x] = acc * norm
                acc += src[base + min(x + r + 1, w - 1)] - src[base + max(x - r, 0)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val norm = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc * norm
                acc += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
            }
        }
    }

    /** Max over a (2r+1)² square (separable). */
    fun dilate(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, src[y * w + k])
                tmp[y * w + x] = m
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0f
                for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[k * w + x])
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Bilinear sample of [f] at ([x], [y]), clamped at the borders. */
    fun sample(f: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = fx - x0
        val ay = fy - y0
        val top = f[y0 * w + x0] * (1 - ax) + f[y0 * w + x1] * ax
        val bottom = f[y1 * w + x0] * (1 - ax) + f[y1 * w + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    /** 0 below [a], 1 above [b], smooth between. */
    fun ramp(v: Float, a: Float, b: Float): Float {
        val t = ((v - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
