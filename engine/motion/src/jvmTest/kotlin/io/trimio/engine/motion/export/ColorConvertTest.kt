package io.trimio.engine.motion.export

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** The display curve: linear light in, the codes an encoder and player expect out. */
class ColorConvertTest {

    /** The sRGB/BT.709-family curve as the standard writes it (independent of the exporter's LUT). */
    private fun oetf(u: Double): Double = if (u <= 0.0031308) 12.92 * u else 1.055 * u.pow(1 / 2.4) - 0.055

    private fun u16(bytes: ByteArray, pixel: Int, channel: Int): Int {
        val i = pixel * 8 + channel * 2
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun putU16(bytes: ByteArray, pixel: Int, channel: Int, v: Int) {
        val i = pixel * 8 + channel * 2
        bytes[i] = v.toByte()
        bytes[i + 1] = (v shr 8).toByte()
    }

    @Test
    fun endpointsAndSamplesFollowTheStandardCurve() {
        assertTrue(abs(ColorConvert.display(0)) < 1e-6f, "linear 0 must come out display 0")
        assertTrue(abs(ColorConvert.display(65535) - 1f) < 1e-4f, "linear 1 must come out display 1")
        for (linear in listOf(1, 256, 4096, 20_000, 65_535)) {
            val expected = oetf(linear / 65535.0).toFloat()
            assertTrue(abs(ColorConvert.display(linear) - expected) < 1e-4f, "linear $linear: ${ColorConvert.display(linear)} vs $expected")
        }
    }

    @Test
    fun displayRgba64ChangesRgbAndLeavesAlphaAlone() {
        val px = ByteArray(16)
        putU16(px, 0, 0, 0x1234)
        putU16(px, 0, 1, 0x5678)
        putU16(px, 0, 2, 0x9ABC)
        putU16(px, 0, 3, 0x0FED)
        ColorConvert.toDisplayRgba64(px)
        for ((ch, linear) in listOf(0x1234, 0x5678, 0x9ABC).withIndex()) {
            val expected = (65535.0 * oetf(linear / 65535.0)).toInt()
            assertTrue(abs(u16(px, 0, ch) - expected) <= 2, "channel $ch: ${u16(px, 0, ch)} vs $expected")
        }
        assertTrue(u16(px, 0, 3) == 0x0FED, "alpha must pass through: ${u16(px, 0, 3)}")
        assertTrue(u16(px, 1, 0) == 0, "the next pixel must not be written: ${u16(px, 1, 0)}")
    }
}
