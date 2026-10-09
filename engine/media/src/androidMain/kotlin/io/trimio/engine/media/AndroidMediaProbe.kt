package io.trimio.engine.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.trimio.core.model.input.AudioTrackInfo
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Container metadata through MediaExtractor, with MediaMetadataRetriever for rotation and frame rate. */
class AndroidMediaProbe(private val context: Context) : MediaProbe {

    override suspend fun probe(uri: MediaUri): MediaInfo = withContext(Dispatchers.IO) {
        val androidUri = Uri.parse(uri.value)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, androidUri, null)
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video == null && mime.startsWith("video/")) video = format
                if (audio == null && mime.startsWith("audio/")) audio = format
            }
            val durationUs = listOfNotNull(video, audio).maxOfOrNull { it.longOr(MediaFormat.KEY_DURATION, 0L) } ?: 0L

            MediaInfo(
                durationMs = durationUs / 1000,
                video = video?.let { videoFormat(it, androidUri) },
                audio = audio?.let {
                    AudioTrackInfo(
                        sampleRate = it.intOr(MediaFormat.KEY_SAMPLE_RATE, 0),
                        channels = it.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0),
                        codec = it.getString(MediaFormat.KEY_MIME),
                    )
                },
                mimeType = context.contentResolver.getType(androidUri),
            )
        } catch (e: Exception) {
            throw MediaFormatException("Cannot read ${uri.value}: ${e.message}", e)
        } finally {
            extractor.release()
        }
    }

    private fun videoFormat(format: MediaFormat, uri: Uri): VideoFormat {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?: format.intOr(MediaFormat.KEY_ROTATION, 0)
            val frameRate = format.numberOr(MediaFormat.KEY_FRAME_RATE)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                ?: 30f
            val transfer = format.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
            return VideoFormat(
                width = format.intOr(MediaFormat.KEY_WIDTH, 0),
                height = format.intOr(MediaFormat.KEY_HEIGHT, 0),
                frameRate = frameRate,
                rotationDegrees = rotation,
                isHdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG,
            )
        } finally {
            retriever.release()
        }
    }
}

internal fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

internal fun MediaFormat.longOr(key: String, default: Long): Long = if (containsKey(key)) getLong(key) else default

/** Frame rate is stored as int or float depending on the muxer. */
private fun MediaFormat.numberOr(key: String): Float? = if (!containsKey(key)) null else getNumber(key)?.toFloat()
