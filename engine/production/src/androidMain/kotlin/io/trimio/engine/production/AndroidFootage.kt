package io.trimio.engine.production

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.trimio.engine.motion.MediaSource

/**
 * A video as a [MediaSource]: frames decoded in order by [MediaCodec] (YUV), cropped to fill
 * [width]×[height] (centre crop, upright) and converted to an [ImageBitmap]. Export asks for
 * times in order, so the decoder only runs forward; a jump back or far ahead seeks to the previous
 * key frame. Answers [id] only, or every id when [id] is null (the speaker's footage).
 */
class AndroidFootage(
    private val context: Context,
    private val uri: String,
    private val width: Int,
    private val height: Int,
    private val id: String? = null,
) : MediaSource, AutoCloseable {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var rotation = 0
    private var inputDone = false
    private var outputDone = false
    private var lastUs = -1L
    private var wantedUs = 0L
    private var last: ImageBitmap? = null
    private val info = MediaCodec.BufferInfo()

    override fun frame(source: String, time: Float): ImageBitmap? {
        if (id != null && source != id) return null
        return runCatching { decode((time * US).toLong()) }.getOrNull()
    }

    @Synchronized
    private fun decode(targetUs: Long): ImageBitmap? {
        open()
        wantedUs = targetUs
        val frameUs = FRAME_US
        // Back in time, or far ahead: seek to the key frame before it.
        if (targetUs < lastUs - frameUs || targetUs > lastUs + SEEK_AHEAD_US) seek(targetUs)
        while (!outputDone && (lastUs < 0 || lastUs + frameUs / 2 < targetUs)) {
            if (!step()) break
        }
        return last
    }

    private fun open() {
        if (codec != null) return
        val ex = MediaExtractor().apply { setDataSource(context, mediaUri(uri), null) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        c.configure(format, null, null, 0)
        c.start()
        extractor = ex
        codec = c
    }

    private fun seek(us: Long) {
        val ex = extractor ?: return
        ex.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
        inputDone = false
        outputDone = false
        lastUs = -1L
    }

    /** Feeds one sample and takes at most one frame; false when nothing can move any more. */
    private fun step(): Boolean {
        val c = codec ?: return false
        val ex = extractor ?: return false
        if (!inputDone) {
            val i = c.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                if (n < 0) {
                    c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                    ex.advance()
                }
            }
        }
        val o = c.dequeueOutputBuffer(info, TIMEOUT_US)
        if (o >= 0) {
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            // Frames on the way from a key frame to the wanted time are decoded but not converted.
            if (info.size > 0 && (info.presentationTimeUs + FRAME_US / 2 >= wantedUs || outputDone)) {
                c.getOutputImage(o)?.use { image -> last = convert(image) }
                lastUs = info.presentationTimeUs
            } else if (info.size > 0) {
                lastUs = info.presentationTimeUs
            }
            c.releaseOutputBuffer(o, false)
        }
        return !(inputDone && outputDone)
    }

    /** YUV_420_888 → ARGB, centre-cropped to fill the frame, rotated upright, in one pass ([YuvCrop], rows in parallel). */
    private fun convert(image: android.media.Image): ImageBitmap {
        val (yp, up, vp) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val planes = YuvPlanes(
            copy(yp.buffer, 0), yp.rowStride, yp.pixelStride,
            copy(up.buffer, 1), up.rowStride, up.pixelStride,
            copy(vp.buffer, 2), vp.rowStride, vp.pixelStride,
        )
        crop.convert(planes, image.width, image.height, rotation) { n, band -> java.util.stream.IntStream.range(0, n).parallel().forEach { band(it) } }
        val bitmap = Bitmap.createBitmap(crop.pixels, width, height, Bitmap.Config.ARGB_8888)
        return bitmap.asImageBitmap()
    }

    private val crop = YuvCrop(width, height)
    private val planeBytes = arrayOfNulls<ByteArray>(3)

    /** A plane's bytes in a reused array (absolute indices, as the plane's strides address them). */
    private fun copy(buffer: java.nio.ByteBuffer, k: Int): ByteArray {
        val n = buffer.limit()
        val bytes = planeBytes[k]?.takeIf { it.size >= n } ?: ByteArray(n).also { planeBytes[k] = it }
        buffer.duplicate().apply { position(0) }.get(bytes, 0, n)
        return bytes
    }

    override fun close() {
        runCatching { codec?.stop() }
        codec?.release()
        extractor?.release()
        codec = null
        extractor = null
    }

    private companion object {
        const val US = 1_000_000f
        const val FRAME_US = 33_333L
        const val SEEK_AHEAD_US = 2_000_000L
        const val TIMEOUT_US = 10_000L
    }
}

/** A path or a URI string as a [android.net.Uri] (plain paths become file URIs). */
internal fun mediaUri(media: String): android.net.Uri =
    if (media.startsWith("/")) android.net.Uri.fromFile(java.io.File(media)) else android.net.Uri.parse(media)
