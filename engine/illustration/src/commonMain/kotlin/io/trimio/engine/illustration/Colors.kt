package io.trimio.engine.illustration

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Packed 0xAARRGGBB helpers (straight alpha). */
object Colors {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val CLEAR = 0
    private const val MASK = 0xFF
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val SHIFT_A = 24
    private const val MAX = 255f
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f
    private const val HUE_SECTOR = 60f
    private const val FULL_TURN = 360f

    fun a(c: Int): Int = c ushr SHIFT_A
    fun r(c: Int): Int = c shr SHIFT_R and MASK
    fun g(c: Int): Int = c shr SHIFT_G and MASK
    fun b(c: Int): Int = c and MASK

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, MASK) shl SHIFT_A) or (r.coerceIn(0, MASK) shl SHIFT_R) or (g.coerceIn(0, MASK) shl SHIFT_G) or b.coerceIn(0, MASK)

    fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, MASK) shl SHIFT_A)

    /** Relative luminance 0..1 of the colour (sRGB weights on the stored values; good enough for tone). */
    fun luma(c: Int): Float = (LR * r(c) + LG * g(c) + LB * b(c)) / MAX

    /** HSV saturation 0..1. */
    fun saturation(c: Int): Float {
        val hi = max(r(c), max(g(c), b(c)))
        val lo = min(r(c), min(g(c), b(c)))
        return if (hi == 0) 0f else (hi - lo).toFloat() / hi
    }

    /** Hue in degrees 0..360 (0 for greys). */
    fun hue(c: Int): Float {
        val r = r(c) / MAX
        val g = g(c) / MAX
        val b = b(c) / MAX
        val hi = max(r, max(g, b))
        val d = hi - min(r, min(g, b))
        if (d <= 0f) return 0f
        val h = when (hi) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * HUE_SECTOR
        return if (h < 0f) h + FULL_TURN else h
    }

    /** Distance between two hues in degrees, 0..180. */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b).mod(FULL_TURN)
        return if (d > FULL_TURN / 2) FULL_TURN - d else d
    }

    /** Linear blend from [a] to [b] by [t] (all channels). */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * u).roundToInt()
        return argb(ch(a(a), a(b)), ch(r(a), r(b)), ch(g(a), g(b)), ch(b(a), b(b)))
    }

    /** [top] over [bottom] (source-over with straight alpha), [top]'s alpha scaled by [opacity]. */
    fun over(top: Int, bottom: Int, opacity: Float = 1f): Int {
        val ta = a(top) / MAX * opacity
        if (ta <= 0f) return bottom
        val ba = a(bottom) / MAX
        val oa = ta + ba * (1f - ta)
        if (oa <= 0f) return CLEAR
        fun ch(t: Int, b: Int) = ((t * ta + b * ba * (1f - ta)) / oa).roundToInt()
        return argb((oa * MAX).roundToInt(), ch(r(top), r(bottom)), ch(g(top), g(bottom)), ch(b(top), b(bottom)))
    }

    /** Bilinear mix of four pixels in premultiplied space. */
    fun mix4(c00: Int, c10: Int, c01: Int, c11: Int, wx: Float, wy: Float): Int {
        val w00 = (1 - wx) * (1 - wy)
        val w10 = wx * (1 - wy)
        val w01 = (1 - wx) * wy
        val w11 = wx * wy
        val a = a(c00) * w00 + a(c10) * w10 + a(c01) * w01 + a(c11) * w11
        if (a <= 0f) return CLEAR
        fun ch(f: (Int) -> Int) = ((f(c00) * a(c00) * w00 + f(c10) * a(c10) * w10 + f(c01) * a(c01) * w01 + f(c11) * a(c11) * w11) / a).roundToInt()
        return argb(a.roundToInt(), ch(::r), ch(::g), ch(::b))
    }

    /** Parses `#rgb`, `#rrggbb`, `#aarrggbb`-less CSS hex and a few names; null for `none`, gradients and unknown values. */
    fun parse(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.startsWith("#")) {
            val hex = v.drop(1)
            return when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(HEX)?.let { it or BLACK }
                6 -> hex.toIntOrNull(HEX)?.let { it or BLACK }
                else -> null
            }
        }
        return NAMED[v]
    }

    private const val HEX = 16
    private val NAMED = mapOf(
        "black" to BLACK, "white" to WHITE, "red" to -0x10000, "green" to -0xff8000, "blue" to -0xffff01,
        "gray" to -0x7f7f80, "grey" to -0x7f7f80, "yellow" to -0x100, "orange" to -0x5b00, "currentcolor" to BLACK,
    )
}
