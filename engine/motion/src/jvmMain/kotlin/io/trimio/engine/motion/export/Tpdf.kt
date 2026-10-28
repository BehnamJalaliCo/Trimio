package io.trimio.engine.motion.export

/**
 * Triangular dither (the sum of two independent uniforms) over exactly ±0.5 LSB of the 8-bit step
 * the final RGB→YUV420 quantization takes. ±0.5 decorrelates the rounding error from the picture
 * without the extra noise power a ±1 LSB dither would add; triangular keeps the noise floor flat.
 * One instance per export worker: this is a stateful stream, not a shared global.
 */
class Tpdf(seed: Long = DEFAULT_SEED) {
    private var state = if (seed == 0L) DEFAULT_SEED else seed

    /** The next offset in LSB, inside [-0.5, 0.5). */
    fun lsb(): Float = uniform() + uniform()

    private fun uniform(): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 40) and 0xFFFF) / 65536f * SPAN - SPAN / 2
    }

    private companion object {
        /** xorshift needs a non-zero state. */
        const val DEFAULT_SEED = 6364136223846793005L

        /** Each uniform covers half an LSB; the pair covers one, triangularly. */
        const val SPAN = 0.5f
    }
}
