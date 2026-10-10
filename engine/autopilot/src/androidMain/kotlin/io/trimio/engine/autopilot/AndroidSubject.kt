package io.trimio.engine.autopilot

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.score.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The speaker's head on Android, found the same way as on desktop: a few small frames from the
 * platform's own decoder ([MediaMetadataRetriever], nearest key frames — fast, and a talking head
 * barely changes between them), the pure-Kotlin [SubjectFinder], and when the director can see,
 * its eyes on the same frames ([SubjectGrounding]). The result is [Autopilot.Request.subject].
 */
object AndroidSubject {

    /** The head in [video]; [eyes] (a model that sees) refines the box when given. */
    suspend fun find(context: Context, video: Uri, eyes: LanguageModel? = null): Subject? {
        val frames = withContext(Dispatchers.IO) { frames(context, video) }
        return locate(frames, eyes)
    }

    /** The head in the video file at [path]. */
    suspend fun find(path: String, eyes: LanguageModel? = null): Subject? {
        val frames = withContext(Dispatchers.IO) { frames(path) }
        return locate(frames, eyes)
    }

    private suspend fun locate(frames: List<RgbImage>, eyes: LanguageModel?): Subject? {
        val found = withContext(Dispatchers.Default) { SubjectFinder.locate(frames) }
        return eyes?.takeIf { it.canSee }?.let { SubjectGrounding(it).ground(frames, found) } ?: found?.subject
    }

    /** [count] RGB frames across [video], [width] px wide (blocking: call off the main thread). */
    fun frames(context: Context, video: Uri, count: Int = COUNT, width: Int = WIDTH): List<RgbImage> =
        retrieve(count, width) { setDataSource(context, video) }

    /** [count] RGB frames across the video at [path], [width] px wide (blocking). */
    fun frames(path: String, count: Int = COUNT, width: Int = WIDTH): List<RgbImage> =
        retrieve(count, width) { setDataSource(path) }

    private fun retrieve(count: Int, width: Int, open: MediaMetadataRetriever.() -> Unit): List<RgbImage> {
        val r = MediaMetadataRetriever()
        // An unreadable video has no frames; the finder then answers with its framing prior.
        return try {
            runCatching { r.open(); sample(r, count, width) }.getOrDefault(emptyList())
        } finally {
            r.release()
        }
    }

    private fun sample(r: MediaMetadataRetriever, count: Int, width: Int): List<RgbImage> {
        val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return emptyList()
        val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return emptyList()
        val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return emptyList()
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        // Frames come out upright; a portrait phone video is often stored landscape with a rotation.
        val (dw, dh) = if (rotation % 180 == 0) vw to vh else vh to vw
        val height = (width.toLong() * dh / dw).toInt() / 2 * 2
        return (1..count).mapNotNull { k ->
            val us = durationMs * 1000L * k / (count + 1)
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)?.let(::rgb)
        }
    }

    private fun rgb(bitmap: Bitmap): RgbImage {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val out = ByteArray(w * h * 3)
        for (i in argb.indices) {
            val c = argb[i]
            out[i * 3] = (c shr 16).toByte()
            out[i * 3 + 1] = (c shr 8).toByte()
            out[i * 3 + 2] = c.toByte()
        }
        return RgbImage(w, h, out)
    }

    private const val COUNT = 5
    private const val WIDTH = 288
}
