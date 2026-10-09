package io.trimio.engine.render

import io.trimio.core.model.asset.IconCatalog
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.Anchor
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.Timeline
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every icon and the market ticker, settled, on one sheet for design review. */
class ElementGalleryTest {

    @Test
    fun everyCatalogIconDraws(): Unit = runBlocking {
        val fonts = RenderFonts.vazirmatn()
        val cell = 270
        val items = IconCatalog.ids.map { "icon/$it" to emptyMap<String, String>() } +
            listOf(
                "ticker/btc" to mapOf("symbol" to "BTC", "change" to "5.2"),
                "ticker/gold" to mapOf("symbol" to "GOLD", "change" to "-1.4", "digits" to "fa"),
            )
        val cols = 4
        val rows = (items.size + cols - 1) / cols
        val sheet = Surface.makeRasterN32Premul(cell * cols, cell * rows)
        sheet.canvas.clear(0xFF101018.toInt())
        items.forEachIndexed { i, (asset, params) ->
            val desktop = DesktopRenderer(cell, cell)
            val timeline = Timeline(
                styleId = "liquid-glass", seed = 1, canvas = CanvasSpec(), durationMs = 4_000,
                clips = listOf(ElementClip(TimeRange(0, 4_000), assetId = asset, preset = "pop", anchor = Anchor.Center, params = params)),
            )
            val style = SampleTimelines.previewStyle.copy(
                background = SampleTimelines.previewStyle.background.copy(preset = "solid"),
                audioOnly = SampleTimelines.previewStyle.audioOnly.copy(visualizer = "none"),
            )
            val renderer = FrameRenderer(timeline, style, desktop.textMeasurer, fonts, RenderOptions(shaderScale = 0.5f))
            val png = desktop.png(desktop.draw { renderer.render(this, FrameContext(1_500)) })
            sheet.canvas.drawImage(Image.makeFromEncoded(png), ((i % cols) * cell).toFloat(), ((i / cols) * cell).toFloat())
        }
        val bytes = sheet.makeImageSnapshot().encodeToData()!!.bytes
        File("build/render-previews").mkdirs()
        File("build/render-previews/elements.png").writeBytes(bytes)
        assertTrue(bytes.size > 50_000)
    }
}
