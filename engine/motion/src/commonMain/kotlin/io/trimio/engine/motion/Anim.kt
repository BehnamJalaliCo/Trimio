package io.trimio.engine.motion

import kotlin.math.floor
import kotlin.math.sin

/**
 * An animated number. Every property in a composition is one of these, evaluated as a pure
 * function of time (seconds), so any frame renders independently and exports are deterministic.
 */
sealed interface Anim {
    fun at(t: Float): Float

    /** Layers [other] on top (a shake on a move, a pop on a scale…). */
    operator fun plus(other: Anim): Anim = when {
        other is Const && other.v == 0f -> this
        this is Const && v == 0f -> other
        this is Const && other is Const -> Const(v + other.v)
        else -> Sum(this, other)
    }

    operator fun times(other: Anim): Anim = when {
        other is Const && other.v == 1f -> this
        this is Const && v == 1f -> other
        this is Const && other is Const -> Const(v * other.v)
        else -> Product(this, other)
    }

    data class Const(val v: Float) : Anim { override fun at(t: Float) = v }

    /** Keyframes; each key carries the easing used to arrive at it from the previous key. */
    class Keys internal constructor(val keys: List<Key>) : Anim {
        override fun at(t: Float): Float {
            if (keys.size == 1 || t <= keys.first().t) return keys.first().v
            if (t >= keys.last().t) return keys.last().v
            var i = 1
            while (keys[i].t < t) i++
            val a = keys[i - 1]
            val b = keys[i]
            val span = b.t - a.t
            if (span <= 0f) return b.v
            return a.v + (b.v - a.v) * b.ease.at((t - a.t) / span)
        }
    }

    /**
     * Keys joined by velocity-continuous Hermite segments: the handle at each joint is shared by
     * both neighbours (simple handle matching), so chained keys never pop in speed. The per-key
     * [Key.ease] still sets the end slopes of the curve.
     */
    class SmoothKeys internal constructor(val keys: List<Key>) : Anim {
        private val count: Int = keys.size

        override fun at(t: Float): Float {
            if (count == 1 || t <= keys.first().t) return keys.first().v
            if (t >= keys.last().t) return keys.last().v
            var i = 1
            while (keys[i].t < t) i++
            val a = keys[i - 1]
            val b = keys[i]
            val span = b.t - a.t
            if (span <= 0f) return b.v
            val u = (t - a.t) / span
            val m0 = handle(i - 1) * span
            val m1 = handle(i) * span
            val u2 = u * u
            val u3 = u2 * u
            return (2f * u3 - 3f * u2 + 1f) * a.v + (u3 - 2f * u2 + u) * m0 +
                (-2f * u3 + 3f * u2) * b.v + (u3 - u2) * m1
        }

        /** Speed at key [k] — the one handle both adjoining segments use. */
        private fun handle(k: Int): Float {
            val first = keys[0]
            val last = keys[count - 1]
            return when {
                k <= 0 -> {
                    val b = keys[1]
                    val dt = b.t - first.t
                    if (dt <= 0f) 0f else (b.v - first.v) / dt * b.ease.startSpeed()
                }
                k >= count - 1 -> {
                    val a = keys[count - 2]
                    val dt = last.t - a.t
                    if (dt <= 0f) 0f else (last.v - a.v) / dt * last.ease.endSpeed()
                }
                else -> {
                    val a = keys[k - 1]
                    val b = keys[k + 1]
                    val dt = b.t - a.t
                    if (dt <= 0f) 0f else (b.v - a.v) / dt
                }
            }
        }
    }

    data class Key(val t: Float, val v: Float, val ease: Easing = Easing.Standard)

    /**
     * A secondary property (shadow, underline, smear) that trails [primary] by [delaySec].
     * [damping] in 0..1 tightens the tail: 1 rides the delayed value almost exactly, lower
     * values smear a longer, softer trail — the classic follow-through lag.
     */
    class Follow(
        val primary: Anim,
        val delaySec: Float,
        val damping: Float = 0.75f,
    ) : Anim {
        override fun at(t: Float): Float {
            val delay = delaySec.coerceAtLeast(0f)
            val decay = 1f - damping.coerceIn(0f, 1f)
            if (delay <= 0f && decay <= 0f) return primary.at(t)
            var sum = 0f
            var weight = 0f
            var w = 1f
            var lag = 0f
            for (i in 0 until TAPS) {
                sum += w * primary.at(t - delay - lag)
                weight += w
                w *= decay
                lag += TAIL
            }
            return sum / weight
        }

        private companion object {
            const val TAPS = 4
            const val TAIL = 0.04f
        }
    }

    /**
     * [move] with a counter-move in the first fifth of its opening segment — [amount] of the
     * travel the other way (the wind-up before the hit), then the move itself. Never winds
     * through zero on a one-sided travel (a scale from ~0 must not flip negative).
     */
    class Anticipate(
        val move: Anim,
        val amount: Float = 0.1f,
    ) : Anim {
        override fun at(t: Float): Float {
            val keys = (move as? Keys)?.keys ?: return move.at(t)
            if (keys.size < 2) return move.at(t)
            val a = keys[0]
            val b = keys[1]
            val span = b.t - a.t
            if (span <= 0f || t >= b.t) return move.at(t)
            val travel = b.v - a.v
            if (travel == 0f || t <= a.t) return move.at(t)
            val pre = span * PRE_SHARE
            val wind = windup(a.v, travel, amount)
            return if (t < a.t + pre) {
                val u = (t - a.t) / pre
                a.v - wind * Easing.ExpoOut.at(u)
            } else {
                val u = ((t - a.t - pre) / (span - pre)).coerceIn(0f, 1f)
                val from = a.v - wind
                from + (b.v - from) * b.ease.at(u)
            }
        }

        private fun windup(from: Float, travel: Float, amount: Float): Float {
            val raw = amount.coerceIn(0f, 1f) * travel
            val to = from - raw
            return if (to * from < 0f) from * HALF else raw
        }

        private companion object {
            const val PRE_SHARE = 0.2f
            const val HALF = 0.5f
        }
    }

    private class Sum(val a: Anim, val b: Anim) : Anim { override fun at(t: Float) = a.at(t) + b.at(t) }
    private class Product(val a: Anim, val b: Anim) : Anim { override fun at(t: Float) = a.at(t) * b.at(t) }

    /**
     * Smooth deterministic noise in -1..1 (layered value noise), scaled by [amplitude] and shaped
     * by [envelope] — for handheld drift, camera shake and organic wobble.
     */
    class Noise(val seed: Int, val frequency: Float, val amplitude: Anim) : Anim {
        override fun at(t: Float): Float {
            val a = amplitude.at(t)
            if (a == 0f) return 0f
            val x = t * frequency
            return a * (0.65f * smooth(x) + 0.35f * smooth(x * 2.13f + 17.3f))
        }

        private fun smooth(x: Float): Float {
            val i = floor(x)
            val f = x - i
            val u = f * f * (3f - 2f * f)
            return hash(i.toInt()) * (1f - u) + hash(i.toInt() + 1) * u
        }

        private fun hash(i: Int): Float {
            val s = sin((i * 127.1f + seed * 311.7f)) * 43758.547f
            return (s - floor(s)) * 2f - 1f
        }
    }

    companion object {
        val Zero: Anim = Const(0f)
        val One: Anim = Const(1f)

        fun keys(vararg keys: Key): Anim = keys(keys.toList())

        fun keys(keys: List<Key>): Anim {
            require(keys.isNotEmpty()) { "an animation needs at least one key" }
            return if (keys.size == 1) Const(keys[0].v) else Keys(keys.sortedBy { it.t })
        }

        /** Velocity-continuous keys: joints share a handle, so segments never pop in speed. */
        fun keysSmooth(vararg keys: Key): Anim = keysSmooth(keys.toList())

        fun keysSmooth(keys: List<Key>): Anim {
            require(keys.isNotEmpty()) { "an animation needs at least one key" }
            return if (keys.size == 1) Const(keys[0].v) else SmoothKeys(keys.sortedBy { it.t })
        }

        /** From [from] to [to] between [t0] and [t1]. */
        fun tween(from: Float, to: Float, t0: Float, t1: Float, ease: Easing = Easing.Standard): Anim =
            Keys(listOf(Key(t0, from, Easing.Hold), Key(t1, to, ease)))
    }
}

/** Builder for multi-segment curves: `anim(0f, at = 1f) { by(1f, 0.4f, Easing.Enter); hold(3f); by(0f, 0.3f, Easing.Exit) }`. */
class AnimBuilder(start: Float, at: Float) {
    private val keys = mutableListOf(Anim.Key(at, start, Easing.Hold))
    val time: Float get() = keys.last().t
    val value: Float get() = keys.last().v

    /** Animates to [value], arriving at absolute time [at]. */
    fun to(value: Float, at: Float, ease: Easing = Easing.Standard) {
        keys += Anim.Key(maxOf(at, time), value, ease)
    }

    /** Animates to [value] over [duration] seconds from the last key. */
    fun by(value: Float, duration: Float, ease: Easing = Easing.Standard) = to(value, time + duration, ease)

    /** Keeps the current value until absolute time [until]. */
    fun hold(until: Float) {
        if (until > time) keys += Anim.Key(until, value, Easing.Hold)
    }

    fun build(): Anim = Anim.keys(keys)
}

fun anim(start: Float, at: Float = 0f, block: AnimBuilder.() -> Unit): Anim = AnimBuilder(start, at).apply(block).build()

val Float.anim: Anim get() = Anim.Const(this)
val Int.anim: Anim get() = Anim.Const(toFloat())
