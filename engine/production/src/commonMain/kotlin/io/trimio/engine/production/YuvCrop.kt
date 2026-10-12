package io.trimio.engine.production

/** One decoded YUV 4:2:0 picture: its three planes as bytes with their row and pixel strides. */
internal class YuvPlanes(
    val y: ByteArray, val yRow: Int, val yPixel: Int,
    val u: ByteArray, val uRow: Int, val uPixel: Int,
    val v: ByteArray, val vRow: Int, val vPixel: Int,
)

/**
 * YUV 4:2:0 → ARGB (BT.601 limited range, the phone cameras' default), centre-cropped to fill
 * [width]×[height], turned upright by the stream's rotation, nearest sample, in one pass. Where
 * each output column and row reads from is worked out once per picture, and [bands] runs the
 * rows in bands (in parallel on a phone's cores), so a 1080×1920 frame costs a few milliseconds
 * instead of a per-pixel object and two divisions.
 */
internal class YuvCrop(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)

    fun convert(
        p: YuvPlanes, sw: Int, sh: Int, rotation: Int,
        bands: (count: Int, band: (Int) -> Unit) -> Unit = { n, band -> for (i in 0 until n) band(i) },
    ): IntArray {
        val upright = rotation % HALF_TURN != 0
        val uw = if (upright) sh else sw
        val uh = if (upright) sw else sh
        val k = maxOf(width / uw.toFloat(), height / uh.toFloat())
        val ox = (uw - width / k) / 2f
        val oy = (uh - height / k) / 2f
        val ux = IntArray(width) { dx -> (ox + dx / k).toInt().coerceIn(0, uw - 1) }
        val uy = IntArray(height) { dy -> (oy + dy / k).toInt().coerceIn(0, uh - 1) }
        val n = BANDS.coerceAtMost(height)
        bands(n) { b ->
            for (dy in height * b / n until height * (b + 1) / n) row(p, dy, ux, uy[dy], sw, sh, rotation)
        }
        return pixels
    }

    private fun row(p: YuvPlanes, dy: Int, ux: IntArray, uy: Int, sw: Int, sh: Int, rotation: Int) {
        val base = dy * width
        for (dx in 0 until width) {
            val x = ux[dx]
            // Upright coordinates back to the stored (unrotated) picture.
            val sx: Int
            val sy: Int
            when (rotation) {
                QUARTER -> { sx = uy; sy = sh - 1 - x }
                HALF_TURN -> { sx = sw - 1 - x; sy = sh - 1 - uy }
                THREE_QUARTERS -> { sx = sw - 1 - uy; sy = x }
                else -> { sx = x; sy = uy }
            }
            val lum = p.y[sy * p.yRow + sx * p.yPixel].toInt() and BYTE
            val cy = sy / 2
            val cx = sx / 2
            val cb = (p.u[cy * p.uRow + cx * p.uPixel].toInt() and BYTE) - CHROMA_ZERO
            val cr = (p.v[cy * p.vRow + cx * p.vPixel].toInt() and BYTE) - CHROMA_ZERO
            pixels[base + dx] = argb(lum, cb, cr)
        }
    }

    companion object {
        private const val BANDS = 8
        private const val BYTE = 0xFF
        private const val CHROMA_ZERO = 128
        private const val QUARTER = 90
        private const val HALF_TURN = 180
        private const val THREE_QUARTERS = 270

        fun argb(lum: Int, cb: Int, cr: Int): Int {
            val c = (lum - 16).coerceAtLeast(0) * 1192
            val r = ((c + 1634 * cr) shr 10).coerceIn(0, 255)
            val g = ((c - 833 * cr - 400 * cb) shr 10).coerceIn(0, 255)
            val b = ((c + 2066 * cb) shr 10).coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
