package io.trimio.engine.illustration.style

import kotlin.math.floor

/**
 * Deterministic value noise from a seed, the same on every platform: the paper grain, the pencil's
 * pressure and the wobble of a hand-drawn line all come from it, so one film's pictures share one hand.
 */
class Noise(seed: Long) {
    private val s1 = (seed xor (seed ushr SHIFT)).toInt() or 1
    private val s2 = (seed * GOLDEN).toInt()

    /** White noise 0..1 for a lattice point. */
    fun hash(x: Int, y: Int): Float {
        var h = x * P1 + y * P2 + s1
        h = (h xor (h ushr 13)) * P3 + s2
        h = h xor (h ushr 16)
        return (h and MASK).toFloat() / MASK
    }

    /** Smooth value noise 0..1 at ([x], [y]) in lattice units. */
    fun smooth(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val ux = fx * fx * (3 - 2 * fx)
        val uy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi)
        val b = hash(xi + 1, yi)
        val c = hash(xi, yi + 1)
        val d = hash(xi + 1, yi + 1)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    /** Two octaves of [smooth], 0..1. */
    fun fbm(x: Float, y: Float): Float = smooth(x, y) * FIRST + smooth(x * 2.03f + OFFSET, y * 2.03f - OFFSET) * SECOND

    private companion object {
        const val SHIFT = 32
        const val GOLDEN = -7046029254386353131L
        const val P1 = 374761393
        const val P2 = 668265263
        const val P3 = 1274126177
        const val MASK = 0xFFFFFF
        const val FIRST = 0.65f
        const val SECOND = 0.35f
        const val OFFSET = 17.3f
    }
}
