package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap

/**
 * An image of [size]² whose alpha is [alpha] (row by row, 0..1) and whose colour is white: a matte to draw a picture
 * through ([MediaSource.matte]).
 */
internal expect fun alphaImage(alpha: FloatArray, size: Int): ImageBitmap

/**
 * [inner]'s pictures with the person in them found ([MediaSource.matte]): each frame asked for is shrunk to a
 * [size]² grid and handed to [person] (a matting network: RGB bytes in, the person's alpha 0..1 out, or null when it
 * cannot run). The last few mattes are kept, since a frame is drawn more than once (motion blur, the text layer and the
 * footage behind it).
 */
class MattedMedia(
    private val inner: MediaSource,
    private val size: Int,
    private val person: (rgb: ByteArray, time: Float) -> FloatArray?,
    /** Frees the matting network with the media. */
    private val release: () -> Unit = {},
) : MediaSource, AutoCloseable {
    private val kept = LinkedHashMap<Pair<String, Float>, ImageBitmap?>()

    /** One frame's pixels, reused: a full frame read back every time would churn the heap. */
    private var pixels = IntArray(0)

    override fun frame(source: String, time: Float): ImageBitmap? = inner.frame(source, time)

    override fun matte(source: String, time: Float): ImageBitmap? {
        val key = source to time
        kept[key]?.let { return it }
        if (key in kept) return null
        val picture = inner.frame(source, time)
        // A picture that cannot be read back (a hardware bitmap) has no matte: the layer then draws nothing.
        val matte = picture?.let { runCatching { rgb(it) }.getOrNull() }?.let { person(it, time) }?.let { alphaImage(it, size) }
        kept[key] = matte
        if (kept.size > KEEP) kept.remove(kept.keys.first())
        return matte
    }

    override fun close() {
        (inner as? AutoCloseable)?.close()
        release()
    }

    /** [image] shrunk to [size]² RGB bytes (each cell the pixel at its centre: the network sees no finer detail). */
    private fun rgb(image: ImageBitmap): ByteArray {
        if (pixels.size != image.width * image.height) pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val out = ByteArray(size * size * CHANNELS)
        for (y in 0 until size) {
            val sy = ((y + HALF) * image.height / size).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until size) {
                val sx = ((x + HALF) * image.width / size).toInt().coerceIn(0, image.width - 1)
                val p = pixels[sy * image.width + sx]
                val o = (y * size + x) * CHANNELS
                out[o] = (p shr RED and BYTE).toByte()
                out[o + 1] = (p shr GREEN and BYTE).toByte()
                out[o + 2] = (p and BYTE).toByte()
            }
        }
        return out
    }

    private companion object {
        const val KEEP = 4
        const val CHANNELS = 3
        const val HALF = 0.5f
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
    }
}
