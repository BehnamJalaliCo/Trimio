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
    private val pixels = IntArray(width * height)

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

    /** YUV_420_888 → ARGB, centre-cropped to fill the frame, rotated upright, in one pass. */
    private fun convert(image: android.media.Image): ImageBitmap {
        val sw = image.width
        val sh = image.height
        val upright = rotation % 180 != 0
        val uw = if (upright) sh else sw
        val uh = if (upright) sw else sh
        val k = maxOf(width / uw.toFloat(), height / uh.toFloat())
        val ox = (uw - width / k) / 2f
        val oy = (uh - height / k) / 2f
        val (yp, up, vp) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val y = yp.buffer
        val u = up.buffer
        val v = vp.buffer
        for (dy in 0 until height) {
            for (dx in 0 until width) {
                val ux = (ox + dx / k).toInt().coerceIn(0, uw - 1)
                val uy = (oy + dy / k).toInt().coerceIn(0, uh - 1)
                // Upright coordinates back to the stored (unrotated) frame.
                val (sx, sy) = when (rotation) {
                    ROT_90 -> uy to (sh - 1 - ux)
                    ROT_180 -> (sw - 1 - ux) to (sh - 1 - uy)
                    ROT_270 -> (sw - 1 - uy) to ux
                    else -> ux to uy
                }
                val lum = y.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 0xFF
                val ci = (sy / 2) * up.rowStride + (sx / 2) * up.pixelStride
                val cb = (u.get(ci).toInt() and 0xFF) - CHROMA_ZERO
                val cr = (v.get((sy / 2) * vp.rowStride + (sx / 2) * vp.pixelStride).toInt() and 0xFF) - CHROMA_ZERO
                pixels[dy * width + dx] = argb(lum, cb, cr)
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return bitmap.asImageBitmap()
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
        const val ROT_90 = 90
        const val ROT_180 = 180
        const val ROT_270 = 270
        const val CHROMA_ZERO = 128

        /** BT.601 limited range, the phone cameras' default. */
        fun argb(lum: Int, cb: Int, cr: Int): Int {
            val c = (lum - 16).coerceAtLeast(0) * 1192
            val r = ((c + 1634 * cr) shr 10).coerceIn(0, 255)
            val g = ((c - 833 * cr - 400 * cb) shr 10).coerceIn(0, 255)
            val b = ((c + 2066 * cb) shr 10).coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** A path or a URI string as a [android.net.Uri] (plain paths become file URIs). */
internal fun mediaUri(media: String): android.net.Uri =
    if (media.startsWith("/")) android.net.Uri.fromFile(java.io.File(media)) else android.net.Uri.parse(media)
