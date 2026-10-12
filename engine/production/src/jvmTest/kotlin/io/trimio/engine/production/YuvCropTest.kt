package io.trimio.engine.production

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The phone's footage conversion ([YuvCrop]) gives exactly the picture of the per-pixel reference, at every rotation. */
class YuvCropTest {
    @Test
    fun matchesThePerPixelReferenceAtEveryRotation() {
        val rnd = Random(7)
        // A 4:2:0 picture with padded rows and interleaved chroma (NV12-like strides), as decoders hand them out.
        val sw = 64
        val sh = 36
        val yRow = 72
        val cRow = 72
        val y = ByteArray(yRow * sh) { rnd.nextInt().toByte() }
        val uv = ByteArray(cRow * sh / 2 + 1) { rnd.nextInt().toByte() }
        val p = YuvPlanes(y, yRow, 1, uv, cRow, 2, uv.copyOfRange(1, uv.size), cRow, 2)
        for (rotation in listOf(0, 90, 180, 270)) {
            for ((w, h) in listOf(48 to 80, 30 to 30, 100 to 40)) {
                val fast = YuvCrop(w, h).convert(p, sw, sh, rotation) { n, band -> (0 until n).toList().parallelStream().forEach { band(it) } }
                assertContentEquals(reference(p, sw, sh, rotation, w, h), fast, "rotation $rotation, $w×$h")
            }
        }
    }

    /** The conversion as it was written first: one pixel at a time. */
    private fun reference(p: YuvPlanes, sw: Int, sh: Int, rotation: Int, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        val upright = rotation % 180 != 0
        val uw = if (upright) sh else sw
        val uh = if (upright) sw else sh
        val k = maxOf(width / uw.toFloat(), height / uh.toFloat())
        val ox = (uw - width / k) / 2f
        val oy = (uh - height / k) / 2f
        for (dy in 0 until height) {
            for (dx in 0 until width) {
                val ux = (ox + dx / k).toInt().coerceIn(0, uw - 1)
                val uy = (oy + dy / k).toInt().coerceIn(0, uh - 1)
                val (sx, sy) = when (rotation) {
                    90 -> uy to (sh - 1 - ux)
                    180 -> (sw - 1 - ux) to (sh - 1 - uy)
                    270 -> (sw - 1 - uy) to ux
                    else -> ux to uy
                }
                val lum = p.y[sy * p.yRow + sx * p.yPixel].toInt() and 0xFF
                val cb = (p.u[(sy / 2) * p.uRow + (sx / 2) * p.uPixel].toInt() and 0xFF) - 128
                val cr = (p.v[(sy / 2) * p.vRow + (sx / 2) * p.vPixel].toInt() and 0xFF) - 128
                out[dy * width + dx] = YuvCrop.argb(lum, cb, cr)
            }
        }
        return out
    }
}
