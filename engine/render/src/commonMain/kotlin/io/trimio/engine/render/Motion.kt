package io.trimio.engine.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Time-based motion for rendering. Unlike UI animation (which is state-driven), every value here is
 * a pure function of time, so any frame can be rendered independently and exports are deterministic.
 */
object Motion {

    /**
     * Unit step response of a damped spring at [tMs] after release: 0 → 1 with overshoot when
     * [damping] < 1. Same parameters as the UI motion tokens, evaluated in closed form.
     */
    fun spring(tMs: Float, damping: Float = 0.6f, stiffness: Float = 500f): Float {
        if (tMs <= 0f) return 0f
        val t = tMs / 1000f
        val w0 = sqrt(stiffness)
        return if (damping < 1f) {
            val wd = w0 * sqrt(1f - damping * damping)
            val envelope = exp(-damping * w0 * t)
            1f - envelope * (cos(wd * t) + (damping * w0 / wd) * sin(wd * t))
        } else {
            1f - exp(-w0 * t) * (1f + w0 * t)
        }
    }

    /** CSS-style cubic bezier easing, solved for x with Newton iterations. */
    class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
        operator fun invoke(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            var t = x
            repeat(8) {
                val dx = bez(t, x1, x2) - x
                val d = deriv(t, x1, x2)
                if (kotlin.math.abs(d) < 1e-6f) return@repeat
                t = (t - dx / d).coerceIn(0f, 1f)
            }
            return bez(t, y1, y2)
        }

        private fun bez(t: Float, a: Float, b: Float): Float {
            val u = 1 - t
            return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
        }

        private fun deriv(t: Float, a: Float, b: Float): Float {
            val u = 1 - t
            return 3 * u * u * a + 6 * u * t * (b - a) + 3 * t * t * (1 - b)
        }
    }

    val easeOutExpo: (Float) -> Float = { x -> if (x >= 1f) 1f else 1f - 2f.pow(-10f * x) }
    val easeInOutSine: (Float) -> Float = { x -> -(cos(PI.toFloat() * x) - 1f) / 2f }
    val emphasized = CubicBezier(0.2f, 0f, 0f, 1f)
    val easeIn = CubicBezier(0.4f, 0f, 1f, 1f)

    fun progress(tMs: Float, durationMs: Float): Float = (tMs / durationMs).coerceIn(0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
