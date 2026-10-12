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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the exporter hands the encoder: 16-bit RGBA, straight alpha, and where its time went. */
class ExportReadoutTest {
    private fun u16(bytes: ByteArray, pixel: Int, channel: Int): Int {
        val i = pixel * 8 + channel * 2
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun square(color: Color) = ShapeNode(
        ShapeSpec.Rect(Anim.Const(32f), Anim.Const(32f)), Fill.Solid(ColorAnim.of(color)),
        transform = Transform(anchorX = 0f, anchorY = 0f),
    )

    @Test
    fun opaqueFramesAreSixteenBitColour() {
        val c = Composition(32, 32, 30, 1f, Color.Black, Group(listOf(square(Color(0xFF336699)))))
        MotionExporter.Frames(c, MediaSource.None).use { frames ->
            val px = frames.rgba64(0.5f)
            assertEquals(32 * 32 * 8, px.size)
            val centre = 16 * 32 + 16
            for ((ch, expected) in listOf(0x33, 0x66, 0x99, 0xFF).withIndex()) {
                assertTrue(abs(u16(px, centre, ch) - expected * 257) <= 300, "channel $ch: ${u16(px, centre, ch)} vs ${expected * 257}")
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

    @Test
    fun hardwareEncodersGetQualityTargetedClosedGops() {
        val nvenc = Encoders.args(listOf("-c:v", "libx264"), maxrateMbps = 20, gop = 60, encoder = Encoders.NVENC)
        assertEquals(Encoders.NVENC, nvenc[nvenc.indexOf("-c:v") + 1])
        assertEquals("20M", nvenc[nvenc.indexOf("-maxrate") + 1])
        assertEquals("60", nvenc[nvenc.indexOf("-g") + 1])
        // No hardware encoder: the software tuning as given (libx264 here; Media Foundation in the Windows app).
        val previous = System.getProperty("trimio.h264.encoder")
        System.clearProperty("trimio.h264.encoder")
        try {
            assertEquals(listOf("-c:v", "libx264"), Encoders.args(listOf("-c:v", "libx264"), 20, 60, encoder = null))
        } finally {
            previous?.let { System.setProperty("trimio.h264.encoder", it) }
        }
    }

    @Test
    fun theProfileSaysWhereTheTimeWent() {
        val p = RenderProfile().apply {
            frames.set(100)
            drawNs.set(8_000_000_000)
            drawCpuNs.set(7_000_000_000)
            readNs.set(400_000_000)
            wallNs = 5_000_000_000
            workers = 2
            canvas = "raster"
            encoder = "libx264"
            width = 1080
            height = 1920
        }
        assertEquals(20f, p.fps)
        assertTrue(p.summary().contains("draw 80.0 ms (cpu 70.0"), p.summary())
    }
}
