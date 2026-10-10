package io.trimio.engine.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A 3×3 projective matrix (row-major): enough for 2D transforms plus 3D rotation of a flat layer
 * seen in perspective, which is all a layer needs (its points have z = 0).
 */
internal class M3(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
    operator fun times(o: M3): M3 {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        return M3(r)
    }

    fun map(x: Float, y: Float): Offset {
        val w = m[6] * x + m[7] * y + m[8]
        val iw = if (kotlin.math.abs(w) < 1e-6f) 1e6f else 1f / w
        return Offset((m[0] * x + m[1] * y + m[2]) * iw, (m[3] * x + m[4] * y + m[5]) * iw)
    }

    fun mapBounds(r: Rect): Rect {
        val pts = listOf(map(r.left, r.top), map(r.right, r.top), map(r.left, r.bottom), map(r.right, r.bottom))
        return Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** As a Compose 4×4 (column-major) matrix whose projection on z = 0 is this matrix. */
    fun toCompose(): Matrix {
        val v = FloatArray(16)
        v[0] = m[0]; v[4] = m[1]; v[12] = m[2]
        v[1] = m[3]; v[5] = m[4]; v[13] = m[5]
        v[3] = m[6]; v[7] = m[7]; v[15] = m[8]
        v[10] = 1f
        return Matrix(v)
    }

    val isIdentity: Boolean get() = m.contentEquals(IDENTITY.m)

    companion object {
        val IDENTITY = M3()
        fun translate(x: Float, y: Float) = M3(floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f))
        fun scale(x: Float, y: Float) = M3(floatArrayOf(x, 0f, 0f, 0f, y, 0f, 0f, 0f, 1f))
        fun rotate(deg: Float): M3 {
            val r = deg * DEG
            val c = cos(r)
            val s = sin(r)
            return M3(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
        }
        fun skewX(deg: Float) = M3(floatArrayOf(1f, tan(deg * DEG), 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /** Rotation about the X then Y axis of a z = 0 plane, projected from [distance] px. */
        fun rotate3d(xDeg: Float, yDeg: Float, distance: Float): M3 {
            if (xDeg == 0f && yDeg == 0f) return IDENTITY
            val ax = xDeg * DEG
            val ay = yDeg * DEG
            // R = Ry · Rx; only the first two columns matter for z = 0 points.
            val r00 = cos(ay)
            val r01 = sin(ay) * sin(ax)
            val r10 = 0f
            val r11 = cos(ax)
            val r20 = -sin(ay)
            val r21 = cos(ay) * sin(ax)
            return M3(floatArrayOf(r00, r01, 0f, r10, r11, 0f, -r20 / distance, -r21 / distance, 1f))
        }

        private const val DEG = (PI / 180.0).toFloat()
    }
}
