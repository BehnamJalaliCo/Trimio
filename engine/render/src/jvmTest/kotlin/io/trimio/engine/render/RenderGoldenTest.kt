package io.trimio.engine.render

import io.trimio.core.model.style.BoxStyle
import io.trimio.core.model.style.CaptionMode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders real frames of the sample edit with the production renderer. Frames land in
 * build/render-previews for review; with ffmpeg present, a full MP4 is encoded too.
 */
class RenderGoldenTest {

    private val out = File("build/render-previews").apply { mkdirs() }
    private val timeline = SampleTimelines.cryptoSignal()
    private val fonts = runBlocking { RenderFonts.vazirmatn() }

    private fun renderer(style: io.trimio.core.model.style.StyleSpec, scale: Int = 2, options: RenderOptions = RenderOptions()): Pair<DesktopRenderer, FrameRenderer> {
        val desktop = DesktopRenderer(1080 / scale, 1920 / scale)
        return desktop to FrameRenderer(timeline, style, desktop.textMeasurer, fonts, options)
    }

    @Test
    fun keyFramesRenderWithContent() {
        val (desktop, renderer) = renderer(SampleTimelines.previewStyle)
        for (t in listOf(500L, 1_900L, 2_900L, 4_700L, 5_700L, 7_000L)) {
            val image = desktop.draw { renderer.render(this, FrameContext(t)) }
            val png = desktop.png(image)
            File(out, "liquid-glass-${t}ms.png").writeBytes(png)
            assertTrue(png.size > 40_000, "frame at $t ms looks empty")
        }
    }

    @Test
    fun captionModesAndBoxesRender() {
        val variants = mapOf(
            "karaoke-pill" to SampleTimelines.previewStyle.copy(captions = SampleTimelines.previewStyle.captions.copy(mode = CaptionMode.Karaoke, box = BoxStyle.Pill)),
            "single-word-slam" to SampleTimelines.previewStyle.copy(captions = SampleTimelines.previewStyle.captions.copy(mode = CaptionMode.SingleWord, entry = "slam", box = BoxStyle.None)),
            "brutal-phrase" to SampleTimelines.previewStyle.copy(
                captions = SampleTimelines.previewStyle.captions.copy(mode = CaptionMode.Phrase, box = BoxStyle.Brutal, boxColor = "#FFE14D"),
                palette = SampleTimelines.previewStyle.palette.copy(text = "#000000"),
                elements = SampleTimelines.previewStyle.elements.copy(card = "brutal"),
                background = SampleTimelines.previewStyle.background.copy(preset = "grid"),
            ),
        )
        for ((name, style) in variants) {
            val (desktop, renderer) = renderer(style)
            val png = desktop.png(desktop.draw { renderer.render(this, FrameContext(2_950)) })
            File(out, "$name.png").writeBytes(png)
            assertTrue(png.size > 30_000, "$name looks empty")
        }
    }

    @Test
    fun fullSampleEncodesToMp4() {
        if (!FfmpegVideoWriter.isAvailable()) return println("ffmpeg not installed; skipping video")
        // Key frames above are full (GPU-equivalent) quality; the video uses the CPU-friendly path.
        val (desktop, renderer) = renderer(SampleTimelines.previewStyle, scale = 2, options = RenderOptions(shaderScale = 0.25f))
        val fps = 30
        val file = File(out, "liquid-glass-sample.mp4")
        FfmpegVideoWriter(file, desktop.width, desktop.height, fps).use { writer ->
            var frame = 0
            while (frame * 1000L / fps < timeline.durationMs) {
                val t = frame * 1000L / fps
                writer.write(desktop.bgra(desktop.draw { renderer.render(this, FrameContext(t)) }))
                frame++
            }
        }
        assertTrue(file.length() > 100_000, "video too small: ${file.length()}")
    }
}
