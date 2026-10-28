package io.trimio.engine.motion.export

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dither source an export sprinkles over its one 8-bit quantization. */
class TpdfTest {

    @Test
    fun staysInsideHalfAnLsb() {
        val t = Tpdf(seed = 1L)
        repeat(10_000) {
            val x = t.lsb()
            assertTrue(x >= -0.5f && x < 0.5f, "dither $x must sit inside ±0.5 LSB")
        }
    }

    @Test
    fun isZeroMeanAndTriangular() {
        val t = Tpdf(seed = 2L)
        val n = 40_000
        var sum = 0.0
        var sum2 = 0.0
        var inside = 0
        repeat(n) {
            val x = t.lsb().toDouble()
            sum += x
            sum2 += x * x
            if (abs(x) <= 0.25) inside++
        }
        val mean = sum / n
        assertTrue(abs(mean) < 0.01, "mean $mean must sit near 0")
        val central = inside / n.toDouble()
        assertTrue(abs(central - 0.75) < 0.02, "central mass $central must sit near 0.75")
        val variance = sum2 / n - mean * mean
        assertTrue(abs(variance - 0.25 / 6) < 0.004, "variance $variance must sit near ${0.25 / 6}")
    }

    @Test
    fun sameSeedSameSequence() {
        val a = Tpdf(seed = 42L)
        val b = Tpdf(seed = 42L)
        repeat(1_000) { assertEquals(a.lsb(), b.lsb(), "seed 42 must replay the same noise") }
    }
}
