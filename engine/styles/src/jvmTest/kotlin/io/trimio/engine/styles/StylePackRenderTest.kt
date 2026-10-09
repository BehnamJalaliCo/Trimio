package io.trimio.engine.styles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.trimio.engine.render.DesktopRenderer
import io.trimio.engine.render.FrameContext
import io.trimio.engine.render.FrameRenderer
import io.trimio.engine.render.RenderFonts
import io.trimio.engine.render.SampleTimelines
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every bundled pack over footage and audio-only, at the same moments, into
 * build/style-previews for side-by-side review (and as the style gallery's stills).
 */
class StylePackRenderTest {

    private val out = File("build/style-previews").apply { mkdirs() }

    /** Stand-in for camera footage: a warm graded gradient with a soft subject shape. */
    private val fakeFootage: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Rect) -> Unit = { r ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6B4F3A), Color(0xFF2E3B4E)), r.top, r.bottom))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE8C4A0), Color(0x00E8C4A0)), Offset(r.center.x, r.height * 0.38f), r.width * 0.35f), r.width * 0.35f, Offset(r.center.x, r.height * 0.38f))
    }

    @Test
    fun everyPackRendersOverFootageAndAudioOnly(): Unit = runBlocking {
        val fonts = RenderFonts.vazirmatn()
        val timeline = SampleTimelines.cryptoSignal()
        for (pack in StylePackRepository().all()) {
            val desktop = DesktopRenderer(540, 960)
            val renderer = FrameRenderer(timeline.copy(styleId = pack.id), pack.spec, desktop.textMeasurer, fonts)
            for (t in listOf(1_900L, 2_950L, 5_700L)) {
                val audioOnly = desktop.png(desktop.draw { renderer.render(this, FrameContext(t)) })
                File(out, "${pack.id}-audio-$t.png").writeBytes(audioOnly)
                val withFootage = desktop.png(desktop.draw { renderer.render(this, FrameContext(t, footage = fakeFootage)) })
                File(out, "${pack.id}-video-$t.png").writeBytes(withFootage)
                // Minimal styles compress very well, so "not empty" is judged on pixels, not file size.
                assertTrue(hasContent(audioOnly) && hasContent(withFootage), "${pack.id} at $t ms looks empty")
            }
        }
        contactSheet(StylePackRepository().all().map { it.id })
    }

    private fun hasContent(png: ByteArray): Boolean {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(org.jetbrains.skia.Image.makeFromEncoded(png))
        val colours = HashSet<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) colours += bitmap.getColor(x, y)
        return colours.size > 6 // antialiased text alone gives a handful; a blank frame gives one
    }

    /** All styles side by side (audio-only, mid-sentence) for design review: build/style-previews/all-styles.png. */
    private fun contactSheet(ids: List<String>) {
        val cols = 7
        val w = 270
        val h = 480
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w * cols, h * ((ids.size + cols - 1) / cols))
        ids.forEachIndexed { i, id ->
            val image = org.jetbrains.skia.Image.makeFromEncoded(File(out, "$id-audio-2950.png").readBytes())
            surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeXYWH((i % cols * w).toFloat(), (i / cols * h).toFloat(), w.toFloat(), h.toFloat()))
        }
        File(out, "all-styles.png").writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)
    }
}
