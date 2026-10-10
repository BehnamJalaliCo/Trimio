package io.trimio.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Picture
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.trimio.core.model.input.InputSource
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Exports with Media3 Transformer:
 *  - video sequence: the kept source segments (clipped media items, original audio removed), or for
 *    audio-only input a black image held for the whole edit;
 *  - audio sequence: the mixed soundtrack as a WAV;
 *  - composition effects: centre-crop to the canvas, camera punch-in, then the [FrameRenderer]
 *    overlay. The overlay is recorded into a [Picture], rasterised by the GPU (so AGSL shaders work)
 *    and handed to Media3 per frame.
 * HDR sources are tone-mapped to SDR so overlay colours match the preview.
 */
@OptIn(UnstableApi::class)
class AndroidVideoExporter(
    private val context: Context,
    private val fonts: FontFamily,
) : VideoExporter {

    override suspend fun export(request: ExportRequest, onFrame: suspend (Int, Int) -> Unit): String = coroutineScope {
        val w = request.timeline.canvas.widthPx
        val h = request.timeline.canvas.heightPx
        val total = request.frameCount
        val frames = Channel<Int>(Channel.CONFLATED)
        val overlay = RendererOverlay(request, w, h, fonts, context) { frames.trySend(it) }

        val composition = withContext(Dispatchers.IO) { buildComposition(request, overlay, w, h) }
        val progressJob = launch {
            for (index in frames) onFrame(index.coerceAtMost(total - 1), total)
        }
        try {
            withContext(Dispatchers.Main) { runTransformer(request, composition) }
        } finally {
            frames.close()
            progressJob.cancel()
        }
        request.outputPath
    }

    private fun buildComposition(request: ExportRequest, overlay: RendererOverlay, w: Int, h: Int): Composition {
        val fps = request.fps
        val video = when (val input = request.input) {
            is InputSource.Video -> request.editMap.kept.map { seg ->
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(input.uri.value))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(seg.startMs).setEndPositionMs(seg.endMs).build(),
                    )
                    .build()
                EditedMediaItem.Builder(item).setRemoveAudio(true).build()
            }
            is InputSource.AudioOnly -> {
                // A black still the size of the canvas; the overlay paints the entire picture on top.
                val black = File(context.cacheDir, "trimio-canvas-${w}x$h.png")
                if (!black.exists()) {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
                        .also { bmp -> black.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                val item = MediaItem.Builder().setUri(Uri.fromFile(black)).setImageDurationMs(request.timeline.durationMs).build()
                listOf(EditedMediaItem.Builder(item).setFrameRate(fps).build())
            }
        }

        val wav = File(context.cacheDir, "trimio-mix-${System.nanoTime()}.wav").apply { writeBytes(WavCodec.encodePcm16(request.soundtrack)) }
        val audio = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()

        val camera = MatrixTransformation { presentationTimeUs ->
            val zoom = overlay.renderer.cameraZoom(presentationTimeUs / 1000)
            Matrix().apply { postScale(zoom, zoom) } // NDC: scaling about the origin is scaling about the centre
        }
        val effects = Effects(
            emptyList(),
            listOf(
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                camera,
                OverlayEffect(listOf(overlay)),
            ),
        )
        return Composition.Builder(
            EditedMediaItemSequence.Builder(video).build(),
            EditedMediaItemSequence.Builder(audio).build(),
        )
            .setEffects(effects)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private suspend fun runTransformer(request: ExportRequest, composition: Composition) = suspendCancellableCoroutine { cont ->
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(if (request.codec == VideoCodec.HEVC) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(request.bitrate ?: request.defaultBitrate()).setiFrameIntervalSeconds(1f).build(),
                    )
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()
        cont.invokeOnCancellation { transformer.cancel() }
        transformer.start(composition, request.outputPath)
    }

    /**
     * Media3 overlay backed by the shared renderer. Called on Media3's GL thread once per output frame.
     */
    private class RendererOverlay(
        request: ExportRequest,
        private val w: Int,
        private val h: Int,
        fonts: FontFamily,
        context: Context,
        private val onFrame: (Int) -> Unit,
    ) : BitmapOverlay() {
        private val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        val renderer = FrameRenderer(request.timeline, request.style, measurer, fonts)
        private val features = request.features
        private val external = request.input is InputSource.Video
        private val fps = request.fps
        private val drawScope = CanvasDrawScope()
        private var current: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = presentationTimeUs / 1000
            val picture = Picture()
            val canvas: AndroidCanvas = picture.beginRecording(w, h)
            drawScope.draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(canvas), Size(w.toFloat(), h.toFloat())) {
                renderer.render(this, FrameContext(timeMs = t, audio = features, externalFootage = external))
            }
            picture.endRecording()
            // Rasterise on the GPU (hardware bitmap, so runtime shaders render), then copy to a
            // software bitmap Media3 can upload as a texture.
            val hardware = Bitmap.createBitmap(picture, w, h, Bitmap.Config.HARDWARE)
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            current?.recycle()
            current = software
            onFrame((t * fps / 1000).toInt())
            return software
        }

        override fun release() {
            super.release()
            current?.recycle()
            current = null
        }
    }
}
