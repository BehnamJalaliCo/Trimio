package io.trimio.engine.media

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.AudioTrackInfo
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Desktop probe and decoder over the system ffprobe/ffmpeg, for the desktop dev tool and
 * end-to-end tests with real video files. The Android app uses MediaExtractor/MediaCodec instead.
 */
class FfmpegMediaSource : MediaProbe, AudioDecoder {

    override suspend fun probe(uri: MediaUri): MediaInfo = withContext(Dispatchers.IO) {
        val out = run("ffprobe", "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path(uri)).decodeToString()
        val root = Json.parseToJsonElement(out).jsonObject
        val streams = root["streams"]?.jsonArray.orEmpty().map { it.jsonObject }
        val video = streams.firstOrNull { it.str("codec_type") == "video" }
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" }
        val duration = root["format"]?.jsonObject?.str("duration")?.toDoubleOrNull() ?: 0.0
        MediaInfo(
            durationMs = (duration * 1000).toLong(),
            video = video?.let {
                val rate = it.str("avg_frame_rate")?.split('/')?.let { (n, d) -> n.toFloat() / d.toFloat().coerceAtLeast(1f) } ?: 30f
                val rotation = it["side_data_list"]?.jsonArray?.firstNotNullOfOrNull { sd -> sd.jsonObject["rotation"]?.jsonPrimitive?.intOrNull } ?: 0
                VideoFormat(it.int("width"), it.int("height"), rate, ((rotation % 360) + 360) % 360, it.str("color_transfer") in setOf("smpte2084", "arib-std-b67"))
            },
            audio = audio?.let { AudioTrackInfo(it.str("sample_rate")?.toIntOrNull() ?: 0, it.int("channels"), it.str("codec_name")) },
            mimeType = null,
        )
    }

    override suspend fun decodeMono(uri: MediaUri, onProgress: (Float) -> Unit): PcmAudio = withContext(Dispatchers.IO) {
        val rate = 48_000
        val bytes = run("ffmpeg", "-v", "error", "-i", path(uri), "-vn", "-ac", "1", "-ar", "$rate", "-f", "f32le", "-")
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(floats.remaining()).also { floats.get(it) }
        onProgress(1f)
        PcmAudio(samples, rate)
    }

    private fun path(uri: MediaUri) = uri.value.let { if (it.startsWith("file:")) File(URI(it)).absolutePath else it }

    private fun run(vararg cmd: String): ByteArray {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        if (p.waitFor() != 0) throw MediaFormatException("${cmd.first()} failed for ${cmd.last()}")
        return out
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
}
