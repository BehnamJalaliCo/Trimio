package io.trimio.engine.motion.export

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The final conversion: display curve, BT.709 limited range, 4:2:0, dither at the rounding. */
class Yuv420Test {

    private fun linearFromSrgb(code: Int): Int {
        val u = code / 255.0
        val l = if (u <= 0.04045) u / 12.92 else pow((u + 0.055) / 1.055, 2.4)
        return (65535.0 * l).roundToInt()
    }

    private fun yOf(r: Double, g: Double, b: Double) = 0.2126 * r + 0.7152 * g + 0.0722 * b

    private fun cbOf(r: Double, g: Double, b: Double) = (b - yOf(r, g, b)) / 1.8556

    private fun crOf(r: Double, g: Double, b: Double) = (r - yOf(r, g, b)) / 1.5748

    private fun putU16(bytes: ByteArray, pixel: Int, channel: Int, v: Int) {
        val i = pixel * 8 + channel * 2
        bytes[i] = v.toByte()
        bytes[i + 1] = (v shr 8).toByte()
    }

    /** A frame of [width]×[height] pixels, each at one linear RGB triplet (u16 codes). */
    private fun frame(width: Int, height: Int, pixels: List<Triple<Int, Int, Int>>): ByteArray {
        val px = ByteArray(width * height * 8)
        for ((i, rgb) in pixels.withIndex()) {
            putU16(px, i, 0, rgb.first)
            putU16(px, i, 1, rgb.second)
            putU16(px, i, 2, rgb.third)
            putU16(px, i, 3, 0xFFFF)
        }
        return px
    }

    private fun flat(width: Int, height: Int, r: Int, g: Int, b: Int) =
        frame(width, height, List(width * height) { Triple(r, g, b) })

    private fun yuv(width: Int, height: Int) = Yuv420(width, height)

    private fun byte(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    @Test
    fun blackStaysAtTheLimitedRangeFloor() {
        val out = ByteArray(2 * 2 * 3 / 2)
        yuv(2, 2).convert(flat(2, 2, 0, 0, 0), null, out)
        for (i in 0 until 4) assertTrue(byte(out, i) == 16, "luma $i: ${byte(out, i)} must be 16 for black")
        assertTrue(byte(out, 4) == 128 && byte(out, 5) == 128, "chroma ${byte(out, 4)}/${byte(out, 5)} must be neutral 128")
    }

    @Test
    fun whiteStaysAtTheLimitedRangeCeiling() {
        val out = ByteArray(2 * 2 * 3 / 2)
        yuv(2, 2).convert(flat(2, 2, 0xFFFF, 0xFFFF, 0xFFFF), null, out)
        for (i in 0 until 4) assertTrue(abs(byte(out, i) - 235) <= 1, "luma $i: ${byte(out, i)} must sit at 235")
        assertTrue(abs(byte(out, 4) - 128) <= 1 && abs(byte(out, 5) - 128) <= 1, "chroma must stay neutral")
    }

    @Test
    fun greyCodesSitOnTheBt709LimitedRamp() {
        for (code in listOf(0, 51, 128, 200, 255)) {
            val l = linearFromSrgb(code)
            val out = ByteArray(2 * 2 * 3 / 2)
            yuv(2, 2).convert(flat(2, 2, l, l, l), null, out)
            val expected = (16 + 219 * (code / 255.0)).roundToInt()
            assertTrue(abs(byte(out, 0) - expected) <= 1, "grey $code: ${byte(out, 0)} vs $expected")
        }
    }

    @Test
    fun blueMovesCbUpAndCrDown() {
        val out = ByteArray(2 * 2 * 3 / 2)
        yuv(2, 2).convert(flat(2, 2, 0, 0, 0xFFFF), null, out)
        val yv = yOf(0.0, 0.0, 1.0)
        assertTrue(abs(byte(out, 0) - (16 + 219 * yv).roundToInt()) <= 1, "luma: ${byte(out, 0)} vs ${(16 + 219 * yv).roundToInt()}")
        assertTrue(abs(byte(out, 4) - (128 + 224 * cbOf(0.0, 0.0, 1.0)).roundToInt()) <= 1, "cb: ${byte(out, 4)}")
        assertTrue(abs(byte(out, 5) - (128 + 224 * crOf(0.0, 0.0, 1.0)).roundToInt()) <= 1, "cr: ${byte(out, 5)}")
    }

    @Test
    fun chromaAveragesEachTwoByTwoBlock() {
        val px = frame(
            2, 2,
            listOf(
                Triple(linearFromSrgb(255), 0, 0),
                Triple(0, linearFromSrgb(255), 0),
                Triple(0, 0, linearFromSrgb(255)),
                Triple(0xFFFF, 0xFFFF, 0xFFFF),
            ),
        )
        val out = ByteArray(2 * 2 * 3 / 2)
        yuv(2, 2).convert(px, null, out)
        val avgCb = (cbOf(1.0, 0.0, 0.0) + cbOf(0.0, 1.0, 0.0) + cbOf(0.0, 0.0, 1.0) + cbOf(1.0, 1.0, 1.0)) / 4
        val avgCr = (crOf(1.0, 0.0, 0.0) + crOf(0.0, 1.0, 0.0) + crOf(0.0, 0.0, 1.0) + crOf(1.0, 1.0, 1.0)) / 4
        assertTrue(abs(byte(out, 4) - (128 + 224 * avgCb).roundToInt()) <= 1, "block cb: ${byte(out, 4)} vs ${(128 + 224 * avgCb).roundToInt()}")
        assertTrue(abs(byte(out, 5) - (128 + 224 * avgCr).roundToInt()) <= 1, "block cr: ${byte(out, 5)} vs ${(128 + 224 * avgCr).roundToInt()}")
    }

    @Test
    fun ditherStaysWithinHalfAnLsbOfTheCleanPicture() {
        val clean = ByteArray(8 * 8 * 3 / 2)
        val noisy = ByteArray(8 * 8 * 3 / 2)
        val px = flat(8, 8, linearFromSrgb(128), linearFromSrgb(128), linearFromSrgb(128))
        yuv(8, 8).convert(px, null, clean)
        yuv(8, 8).convert(px, Tpdf(seed = 7L), noisy)
        for (i in clean.indices) {
            assertTrue(abs(byte(noisy, i) - byte(clean, i)) <= 1, "sample $i: ${byte(noisy, i)} vs ${byte(clean, i)} must differ by at most 1 LSB")
        }
    }

    @Test
    fun rejectsOddSides() {
        assertFailsWith<IllegalArgumentException>("4:2:0 cannot have odd sides") { Yuv420(3, 2) }
    }
}
