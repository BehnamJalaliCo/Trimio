package io.trimio.engine.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.trimio.core.model.input.InputSource
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.File
import java.net.URI

/**
 * Desktop exporter (development tool and integration tests): decodes footage with ffmpeg segment by
 * segment along the edit's kept ranges, composites with the production [FrameRenderer] on Skia, and
 * encodes H.264 + AAC with ffmpeg.
 */
class DesktopVideoExporter(private val fonts: androidx.compose.ui.text.font.FontFamily) : VideoExporter {

    override suspend fun export(request: ExportRequest, onFrame: suspend (Int, Int) -> Unit): String = withContext(Dispatchers.IO) {
        val w = request.timeline.canvas.widthPx
        val h = request.timeline.canvas.heightPx
        val desktop = DesktopRenderer(w, h)
        val renderer = FrameRenderer(request.timeline, request.style, desktop.textMeasurer, fonts, RenderOptions(shaderScale = 0.25f))
        val wav = File.createTempFile("trimio-mix", ".wav").apply { writeBytes(WavCodec.encodePcm16(request.soundtrack)); deleteOnExit() }
        val output = File(request.outputPath).apply { parentFile?.mkdirs() }
        val footage = (request.input as? InputSource.Video)?.let { FootageFrames(it, request, w, h) }

        try {
            FfmpegVideoWriter(output, w, h, request.fps, wav).use { writer ->
                val total = request.frameCount
                for (i in 0 until total) {
                    val t = i * 1000L / request.fps
                    val frame = footage?.next()
                    val image = desktop.draw {
                        renderer.render(
                            this,
                            FrameContext(
                                timeMs = t,
                                audio = request.features,
                                footage = frame?.let { bmp -> { dst -> drawImage(bmp, dstOffset = IntOffset.Zero, dstSize = IntSize(dst.width.toInt(), dst.height.toInt())) } },
                            ),
                        )
                    }
                    writer.write(desktop.bgra(image))
                    onFrame(i, total)
                }
            }
        } finally {
            footage?.close()
        }
        output.absolutePath
    }

    /**
     * Streams source frames already scaled and centre-cropped to the canvas, one ffmpeg process per
     * kept segment, so cuts never need random seeking.
     */
    private class FootageFrames(input: InputSource.Video, private val request: ExportRequest, private val w: Int, private val h: Int) : AutoCloseable {
        private val path = input.uri.value.let { if (it.startsWith("file:")) File(URI(it)).absolutePath else it }
        private val segments = ArrayDeque(request.editMap.kept)
        private var process: Process? = null
        private var stream: DataInputStream? = null
        private val buffer = ByteArray(w * h * 4)
        private var last: ImageBitmap? = null

        fun next(): ImageBitmap? {
            while (true) {
                val s = stream ?: openNext() ?: return last
                try {
                    s.readFully(buffer)
                    val image = Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), buffer, w * 4)
                    return image.toComposeImageBitmap().also { last = it }
                } catch (_: java.io.EOFException) {
                    closeCurrent()
                }
            }
        }

        private fun openNext(): DataInputStream? {
            val seg = segments.removeFirstOrNull() ?: return null
            val p = ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-ss", "%.3f".format(seg.startMs / 1000.0), "-t", "%.3f".format(seg.durationMs / 1000.0),
                "-i", path, "-an", "-vf", "scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,fps=${request.fps}",
                "-f", "rawvideo", "-pix_fmt", "bgra", "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = p
            return DataInputStream(p.inputStream.buffered(1 shl 20)).also { stream = it }
        }

        private fun closeCurrent() {
            stream?.close()
            process?.destroy()
            stream = null
            process = null
        }

        override fun close() = closeCurrent()
    }
}
