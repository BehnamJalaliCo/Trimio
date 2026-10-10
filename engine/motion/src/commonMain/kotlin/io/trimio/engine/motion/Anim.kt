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

    data class Key(val t: Float, val v: Float, val ease: Easing = Easing.Standard)

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
