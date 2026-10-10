package io.trimio.engine.autopilot

/**
 * Deterministic randomness for creative choices: the same seed always gives the same edit, a new
 * seed a different one. SplitMix64, so nearby seeds still give unrelated streams.
 */
class Rng(seed: Long) {
    private var state = seed * -0x61c8864680b583ebL + 0x2545F4914F6CDD1DL

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun next(): Float = ((nextLong() ushr 40).toFloat() / (1L shl 24).toFloat())

    fun chance(p: Float): Boolean = next() < p

    fun range(a: Float, b: Float): Float = a + (b - a) * next()

    fun <T> pick(options: List<T>): T = options[(next() * options.size).toInt().coerceIn(0, options.lastIndex)]

    /** Picks by weight; weights ≤ 0 are never picked unless all are. */
    fun <T> weighted(options: List<Pair<T, Float>>): T {
        val live = options.filter { it.second > 0f }.ifEmpty { return options.first().first }
        var r = next() * live.sumOf { it.second.toDouble() }.toFloat()
        for ((v, w) in live) {
            r -= w
            if (r <= 0f) return v
        }
        return live.last().first
    }

    /** A child stream for one decision area, so adding a choice elsewhere does not reshuffle this one. */
    fun fork(salt: Int): Rng = Rng(nextLong() xor (salt.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
}
