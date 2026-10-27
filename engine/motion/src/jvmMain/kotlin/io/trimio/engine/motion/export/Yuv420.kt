package io.trimio.engine.motion.export

import kotlin.math.roundToInt

/**
 * The one place an export's frame becomes 8-bit: display curve (by way of [ColorConvert]), the
 * BT.709 matrix, limited range and a [Tpdf] dither right at the rounding — the only quantization
 * in the pipeline, so the only place banding could appear and the only place the noise goes.
 * Chroma drops to 4:2:0 by a 2×2 average; the picture is already at its final size, so only the
 * chroma plane is reduced here.
 * One instance per export worker: it holds the frame's chroma scratch.
 */
class Yuv420(private val width: Int, private val height: Int) {
    init {
        require(width % 2 == 0 && height % 2 == 0) { "4:2:0 needs even sides, got ${width}x$height" }
    }

    private val cb = FloatArray(width * height / 4)
    private val cr = FloatArray(width * height / 4)

    /**
     * Linear 16-bit straight RGBA ([rgba], little-endian) into planar 8-bit 4:2:0 BT.709 limited
     * range ([out], I420: luma, Cb, Cr; at least `width*height*3/2` bytes). [dither] is sprinkled
     * on the rounding; null converts clean (tests and diagnostics). Alpha is ignored: the video
     * path draws over opaque black.
     */
    fun convert(rgba: ByteArray, dither: Tpdf?, out: ByteArray) {
        val w = width
        val h = height
        val cw = w / 2
        check(out.size >= w * h * 3 / 2) { "yuv420p frame needs ${w * h * 3 / 2} bytes, got ${out.size}" }
        cb.fill(0f)
        cr.fill(0f)
        var o = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val r = ColorConvert.display(ColorConvert.u16(rgba, o))
                val g = ColorConvert.display(ColorConvert.u16(rgba, o + 2))
                val b = ColorConvert.display(ColorConvert.u16(rgba, o + 4))
                o += 8
                val yv = KR * r + KG * g + KB * b
                out[row + x] = quantize(Y_ZERO + Y_SPAN * yv, dither)
                val ci = (y / 2) * cw + (x / 2)
                cb[ci] += (b - yv) / CB_DEN
                cr[ci] += (r - yv) / CR_DEN
            }
        }
        val luma = w * h
        val chroma = cw * (h / 2)
        for (i in 0 until chroma) {
            out[luma + i] = quantize(C_MID + C_SPAN * (cb[i] * 0.25f), dither)
            out[luma + chroma + i] = quantize(C_MID + C_SPAN * (cr[i] * 0.25f), dither)
        }
    }

    private fun quantize(value: Float, dither: Tpdf?): Int {
        val v = if (dither == null) value else value + dither.lsb()
        return v.roundToInt().coerceIn(0, 255)
    }

    private companion object {
        // BT.709 luma weights and the Cb/Cr denominators 2(1−Kb), 2(1−Kr).
        const val KR = 0.2126f
        const val KG = 0.7152f
        const val KB = 0.0722f
        const val CB_DEN = 1.8556f
        const val CR_DEN = 1.5748f

        // Limited ("tv") range in 8 bits.
        const val Y_ZERO = 16f
        const val Y_SPAN = 219f
        const val C_MID = 128f
        const val C_SPAN = 224f
    }
}
