package io.trimio.engine.motion.export

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Transform
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the exporter hands the encoder: 16-bit linear RGBA out of the frame, display codes after the output conversion. */
class ExportReadoutTest {
    private fun u16(bytes: ByteArray, pixel: Int, channel: Int): Int {
        val i = pixel * 8 + channel * 2
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun square(color: Color) = ShapeNode(
        ShapeSpec.Rect(Anim.Const(32f), Anim.Const(32f)), Fill.Solid(ColorAnim.of(color)),
        transform = Transform(anchorX = 0f, anchorY = 0f),
    )

    /** The sRGB/BT.709-family curve and its inverse as the standards write them (independent of the exporter). */
    private fun oetf(u: Double): Double = if (u <= 0.0031308) 12.92 * u else 1.055 * u.pow(1 / 2.4) - 0.055

    private fun inverseOetf(u: Double): Double = if (u <= 0.04045) u / 12.92 else ((u + 0.055) / 1.055).pow(2.4)

    @Test
    fun framesReadOutLinearAndReachTheEncoderInDisplayCodes() {
        val c = Composition(32, 32, 30, 1f, Color.Black, Group(listOf(square(Color(0xFF336699)))))
        MotionExporter.Frames(c, MediaSource.None).use { frames ->
            val px = frames.rgba64(0.5f)
            assertEquals(32 * 32 * 8, px.size)
            val centre = 16 * 32 + 16
            for ((ch, code) in listOf(0x33, 0x66, 0x99, 0xFF).withIndex()) {
                val linear = (65535.0 * inverseOetf(code / 255.0)).roundToInt()
                assertTrue(abs(u16(px, centre, ch) - linear) <= 300, "channel $ch linear: ${u16(px, centre, ch)} vs $linear (the surface composites in linear light)")
            }
            ColorConvert.toDisplayRgba64(px)
            for ((ch, code) in listOf(0x33, 0x66, 0x99, 0xFF).withIndex()) {
                assertTrue(abs(u16(px, centre, ch) - code * 257) <= 300, "channel $ch display: ${u16(px, centre, ch)} vs ${code * 257} (the encoder's codes)")
            }
        }
    }

    @Test
    fun translucentLayersMixInLinearLight() {
        val c = Composition(
            32, 32, 30, 1f, Color.Black,
            Group(listOf(square(Color(0xFF336699)), square(Color(1f, 1f, 1f, 0.5f)))),
        )
        MotionExporter.Frames(c, MediaSource.None).use { frames ->
            val px = frames.rgba64(0.5f)
            ColorConvert.toDisplayRgba64(px)
            val centre = 16 * 32 + 16
            for ((ch, code) in listOf(0x33, 0x66, 0x99).withIndex()) {
                val expected = (65535.0 * oetf(0.5 * (inverseOetf(code / 255.0) + 1.0))).roundToInt()
                assertTrue(abs(u16(px, centre, ch) - expected) <= 300, "channel $ch: ${u16(px, centre, ch)} vs $expected (50% white mixes in linear)")
            }
        }
    }

    @Test
    fun theGraphicsLayerComesOutWithStraightAlpha() {
        val c = Composition(32, 32, 30, 1f, Color.Transparent, Group(listOf(square(Color(1f, 0f, 0f, 0.5f))))).transparent()
        MotionExporter.Frames(c, MediaSource.None, transparent = true).use { frames ->
            val px = frames.rgba64(0.5f)
            val centre = 16 * 32 + 16
            assertTrue(u16(px, centre, 0) > 65000, "red un-premultiplied back to full: ${u16(px, centre, 0)}")
            assertTrue(abs(u16(px, centre, 3) - 32768) < 400, "half alpha: ${u16(px, centre, 3)}")
        }
    }
}
