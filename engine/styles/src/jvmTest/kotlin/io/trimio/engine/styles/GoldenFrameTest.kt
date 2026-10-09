package io.trimio.engine.styles

import io.trimio.engine.render.DesktopRenderer
import io.trimio.engine.render.FrameContext
import io.trimio.engine.render.FrameRenderer
import io.trimio.engine.render.RenderFonts
import io.trimio.engine.render.SampleTimelines
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pixel comparison of every style against reviewed golden frames, so an engine change that alters
 * how a style looks is caught in review. Small frames keep the goldens light (about 1 MB in total).
 * After an intentional change: `./gradlew :engine:styles:jvmTest -Pgolden.update` and review the diff.
 */
class GoldenFrameTest {
    private val goldens = File("src/jvmTest/resources/golden")
    private val update = System.getProperty("golden.update") == "true"
    private val failures = File("build/golden-failures")

    @Test
    fun everyStyleMatchesItsGolden(): Unit = runBlocking {
        val fonts = RenderFonts.vazirmatn()
        val timeline = SampleTimelines.cryptoSignal()
        val mismatches = mutableListOf<String>()
        for (pack in StylePackRepository().all()) {
            val desktop = DesktopRenderer(180, 320)
            val renderer = FrameRenderer(timeline.copy(styleId = pack.id), pack.spec, desktop.textMeasurer, fonts)
            val png = desktop.png(desktop.draw { renderer.render(this, FrameContext(2_950)) })
            val golden = File(goldens, "${pack.id}.png")
            if (update || !golden.exists()) {
                goldens.mkdirs()
                golden.writeBytes(png)
                continue
            }
            val diff = difference(golden.readBytes(), png)
            if (diff > TOLERANCE) {
                failures.mkdirs()
                File(failures, "${pack.id}-actual.png").writeBytes(png)
                mismatches += "${pack.id}: ${"%.2f".format(diff * 100)}% different"
            }
        }
        if (mismatches.isNotEmpty()) fail("Styles changed (actual frames in build/golden-failures):\n" + mismatches.joinToString("\n"))
        assertTrue(goldens.listFiles()!!.size >= 28)
    }

    /** Mean absolute channel difference, 0..1. Tolerates anti-aliasing and shader rounding noise. */
    private fun difference(a: ByteArray, b: ByteArray): Double {
        val x = Bitmap.makeFromImage(Image.makeFromEncoded(a))
        val y = Bitmap.makeFromImage(Image.makeFromEncoded(b))
        if (x.width != y.width || x.height != y.height) return 1.0
        var sum = 0L
        for (j in 0 until x.height) for (i in 0 until x.width) {
            val p = x.getColor(i, j)
            val q = y.getColor(i, j)
            for (shift in intArrayOf(0, 8, 16)) sum += abs((p shr shift and 0xFF) - (q shr shift and 0xFF))
        }
        return sum / (x.width * x.height * 3.0 * 255.0)
    }

    private companion object {
        const val TOLERANCE = 0.015
    }
}
