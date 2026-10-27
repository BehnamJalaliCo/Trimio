package io.trimio.engine.motion.export

import kotlin.math.pow

/**
 * The display side of an export. Frames render in linear light (the surfaces are made with
 * `ColorSpace.sRGBLinear`); only here, at the output, do they take the display curve, so the
 * encoder and every player see the codes the picture was graded as. The curve is sRGB (its
 * primaries and transfer sit on BT.709's), applied through a 65536-entry table because the
 * conversion runs once per frame in a tight loop over millions of channels.
 */
object ColorConvert {
    /** The display code 0..1 for a linear light value 0..65535. */
    fun display(linearU16: Int): Float = DISPLAY[linearU16 and 0xFFFF]

    /** The 16-bit display code for a linear light value 0..65535. */
    fun display16(linearU16: Int): Int = (DISPLAY[linearU16 and 0xFFFF] * 65535f + 0.5f).toInt()

    /**
     * In place: each pixel's RGB through the display curve (16-bit in, 16-bit out), alpha left
     * alone. The ProRes path keeps 16-bit precision all the way into the codec.
     */
    fun toDisplayRgba64(rgba: ByteArray) {
        var o = 0
        while (o + 7 < rgba.size) {
            put(rgba, o, display16(u16(rgba, o)))
            put(rgba, o + 2, display16(u16(rgba, o + 2)))
            put(rgba, o + 4, display16(u16(rgba, o + 4)))
            o += 8
        }
    }

    /** Little-endian 16-bit code at [i]. */
    internal fun u16(bytes: ByteArray, i: Int): Int =
        (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)

    /** Little-endian 16-bit code [v] at [i]. */
    internal fun put(bytes: ByteArray, i: Int, v: Int) {
        bytes[i] = v.toByte()
        bytes[i + 1] = (v shr 8).toByte()
    }

    private val DISPLAY = FloatArray(1 shl 16) { oetf(it / 65535f) }

    /** The sRGB piecewise curve; its toe keeps the dark ramps smooth. */
    private fun oetf(u: Float): Float =
        if (u <= KNEE) 12.92f * u else 1.055f * pow(u.toDouble(), 1.0 / 2.4).toFloat() - 0.055f

    private const val KNEE = 0.0031308f
}
