package io.trimio.engine.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Timing curves. Signature motion is built on a few of them: expo-out for confident entrances,
 * a critically-damped spring for things that land, back/overshoot for hits, and cubic-bezier for
 * anything bespoke. All map 0..1 to (mostly) 0..1; springs may overshoot past 1.
 */
sealed interface Easing {
    fun at(x: Float): Float

    data object Linear : Easing { override fun at(x: Float) = x }

    /** Holds the start value, then jumps (for cuts, flashes and frame-accurate switches). */
    data object Hold : Easing { override fun at(x: Float) = if (x < 1f) 0f else 1f }

    data object ExpoOut : Easing { override fun at(x: Float) = if (x >= 1f) 1f else 1f - 2f.pow(-10f * x) }
    data object ExpoIn : Easing { override fun at(x: Float) = if (x <= 0f) 0f else 2f.pow(10f * (x - 1f)) }
    data object ExpoInOut : Easing {
        override fun at(x: Float) = when {
            x <= 0f -> 0f
            x >= 1f -> 1f
            x < 0.5f -> 2f.pow(20f * x - 10f) / 2f
            else -> (2f - 2f.pow(-20f * x + 10f)) / 2f
        }
    }
    data object QuintOut : Easing { override fun at(x: Float) = 1f - (1f - x).pow(5) }
    data object SineInOut : Easing { override fun at(x: Float) = -(cos(PI.toFloat() * x) - 1f) / 2f }

    /** Overshoot by [amount] then settle (1.70158 is the classic "back"). */
    data class BackOut(val amount: Float = 1.70158f) : Easing {
        override fun at(x: Float): Float {
            val c3 = amount + 1f
            return 1f + c3 * (x - 1f).pow(3) + amount * (x - 1f).pow(2)
        }
    }

    /**
     * Damped spring from 0 to 1 over the normalised duration. [damping] < 1 rings, 1 is critical.
     * The tail is clamped so the curve ends exactly at 1 (keyframes must land on their value).
     */
    data class Spring(val damping: Float = 0.55f, val frequency: Float = 2.2f) : Easing {
        override fun at(x: Float): Float {
            if (x >= 1f) return 1f
            val w = 2f * PI.toFloat() * frequency
            val z = damping.coerceIn(0.05f, 1f)
            val v = if (z < 1f) {
                val wd = w * sqrt(1f - z * z)
                1f - exp(-z * w * x) * (cos(wd * x) + z * w / wd * sin(wd * x))
            } else {
                1f - exp(-w * x) * (1f + w * x)
            }
            // Fade the residual so the last frame is exactly 1.
            val tail = ((x - 0.85f) / 0.15f).coerceIn(0f, 1f)
            return v + (1f - v) * tail
        }
    }

    /** CSS-style cubic-bezier(x1, y1, x2, y2), solved with Newton steps then bisection. */
    data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing {
        private fun bx(t: Float) = 3f * (1 - t) * (1 - t) * t * x1 + 3f * (1 - t) * t * t * x2 + t * t * t
        private fun by(t: Float) = 3f * (1 - t) * (1 - t) * t * y1 + 3f * (1 - t) * t * t * y2 + t * t * t
        private fun dx(t: Float) = 3f * (1 - t) * (1 - t) * x1 + 6f * (1 - t) * t * (x2 - x1) + 3f * t * t * (1 - x2)
        override fun at(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            var t = x
            repeat(6) {
                val d = dx(t)
                if (abs(d) < 1e-5f) return@repeat
                t -= (bx(t) - x) / d
            }
            if (abs(bx(t) - x) > 1e-3f) {
                var lo = 0f
                var hi = 1f
                t = x
                repeat(24) { if (bx(t) < x) lo = t else hi = t; t = (lo + hi) / 2f }
            }
            return by(t)
        }
    }

    companion object {
        /** House entrance: fast, confident, long soft landing. */
        val Enter: Easing = ExpoOut
        val Exit: Easing = ExpoIn
        val Move: Easing = ExpoInOut
        /** For things that land with weight (slams, pops, cards). */
        val Land: Easing = Spring(damping = 0.55f, frequency = 2.0f)
        val Hit: Easing = BackOut(2.2f)
        /** Material-like standard curve for UI-ish moves. */
        val Standard: Easing = Bezier(0.2f, 0f, 0f, 1f)
    }
}
