package io.trimio.engine.production

/**
 * The numbers a master is encoded at on every platform: the phone's MediaCodec path and the
 * desktop's ffmpeg path master the same listening experience (48 kHz, full-width AAC) and the
 * same colour identity (BT.709 limited). Plain values on purpose: shared code cannot see
 * `android.media.MediaFormat`, and the key strings below are that API's constants.
 */
object EncodeParams {
    /** Desktop masters AAC 320 kbps; 256 keeps the phone's track in the same class. */
    const val AUDIO_BITRATE = 256_000

    /** Both paths master at 48 kHz (the desktop's ffmpeg args and the phone's AAC encoder). */
    const val AUDIO_RATE = 48_000

    // MediaFormat.KEY_COLOR_* (API 24+): what the muxer tags the master with.
    private const val KEY_COLOR_STANDARD = "color-standard"
    private const val KEY_COLOR_RANGE = "color-range"
    private const val KEY_COLOR_TRANSFER = "color-transfer"

    // MediaFormat.COLOR_STANDARD_BT709 / COLOR_RANGE_LIMITED / COLOR_TRANSFER_SMPTE_170M.
    const val COLOR_STANDARD_BT709 = 1
    const val COLOR_RANGE_LIMITED = 2
    const val COLOR_TRANSFER_SMPTE_170M = 3

    /**
     * The colour keys a video `MediaFormat` must carry. Without them the master is untagged and
     * players fall back to full-range BT.601: the same frames look washed out.
     */
    fun colourTags(): Map<String, Int> = mapOf(
        KEY_COLOR_STANDARD to COLOR_STANDARD_BT709,
        KEY_COLOR_RANGE to COLOR_RANGE_LIMITED,
        KEY_COLOR_TRANSFER to COLOR_TRANSFER_SMPTE_170M,
    )

    /**
     * Mono [samples] from [fromRate] to [toRate] (linear interpolation between neighbours: the AAC
     * encoder wants 48 kHz and a soundtrack may arrive at whatever rate a recorder used).
     */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until n) {
            val pos = i * step
            val a = pos.toInt().coerceAtMost(samples.size - 1)
            val b = (a + 1).coerceAtMost(samples.size - 1)
            val f = (pos - a).toFloat()
            out[i] = samples[a] * (1f - f) + samples[b] * f
        }
        return out
    }
}
