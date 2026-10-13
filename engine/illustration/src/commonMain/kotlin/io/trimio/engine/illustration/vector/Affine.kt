package io.trimio.engine.illustration.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 2-D affine transform: x' = a·x + c·y + e, y' = b·x + d·y + f (SVG's matrix order). */
data class Affine(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val e: Float = 0f, val f: Float = 0f) {
    fun x(x: Float, y: Float): Float = a * x + c * y + e
    fun y(x: Float, y: Float): Float = b * x + d * y + f

    /** This transform applied after [inner] (inner first). */
    operator fun times(inner: Affine): Affine = Affine(
        a * inner.a + c * inner.b, b * inner.a + d * inner.b,
        a * inner.c + c * inner.d, b * inner.c + d * inner.d,
        a * inner.e + c * inner.f + e, b * inner.e + d * inner.f + f,
    )

    /** The mean scale factor (for stroke widths and flattening tolerance). */
    val scale: Float get() = sqrt(kotlin.math.abs(a * d - b * c)).coerceAtLeast(MIN_SCALE)

    companion object {
        val Identity = Affine()
        private const val MIN_SCALE = 1e-6f
        private const val DEG = PI.toFloat() / 180f

        fun translate(x: Float, y: Float) = Affine(e = x, f = y)
        fun scale(sx: Float, sy: Float = sx) = Affine(a = sx, d = sy)
        fun rotate(degrees: Float): Affine {
            val r = degrees * DEG
            return Affine(cos(r), sin(r), -sin(r), cos(r))
        }

        /** Parses an SVG `transform` list (matrix, translate, scale, rotate, skewX/skewY are ignored). */
        fun parse(text: String?): Affine {
            if (text.isNullOrBlank()) return Identity
            var m = Identity
            val re = Regex("(matrix|translate|scale|rotate)\\s*\\(([^)]*)\\)")
            for (op in re.findAll(text)) {
                val n = PathData.numbers(op.groupValues[2])
                val t = when (op.groupValues[1]) {
                    "matrix" -> if (n.size >= 6) Affine(n[0], n[1], n[2], n[3], n[4], n[5]) else Identity
                    "translate" -> translate(n.getOrElse(0) { 0f }, n.getOrElse(1) { 0f })
                    "scale" -> scale(n.getOrElse(0) { 1f }, n.getOrElse(1) { n.getOrElse(0) { 1f } })
                    else -> rotated(n)
                }
                m *= t
            }
            return m
        }

        private fun rotated(n: List<Float>): Affine {
            val r = rotate(n.getOrElse(0) { 0f })
            if (n.size < 3) return r
            return translate(n[1], n[2]) * r * translate(-n[1], -n[2])
        }
    }
}
