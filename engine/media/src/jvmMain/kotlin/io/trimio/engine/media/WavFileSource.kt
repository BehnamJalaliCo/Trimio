package io.trimio.engine.media

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/** Desktop/test implementation over local WAV files (`file:///path.wav` or a plain path). */
class WavFileSource : MediaProbe, AudioDecoder {

    override suspend fun probe(uri: MediaUri): MediaInfo = withContext(Dispatchers.IO) {
        val header = WavCodec.readHeader(read(uri))
        MediaInfo(durationMs = header.durationMs, video = null, audio = header.trackInfo(), mimeType = "audio/wav")
    }

    override suspend fun decodeMono(uri: MediaUri, onProgress: (Float) -> Unit): PcmAudio = withContext(Dispatchers.IO) {
        WavCodec.decodeMono(read(uri)).also { onProgress(1f) }
    }

    private fun read(uri: MediaUri): ByteArray {
        val file = if (uri.value.startsWith("file:")) File(URI(uri.value)) else File(uri.value)
        if (!file.isFile) throw MediaFormatException("File not found: ${uri.value}")
        return file.readBytes()
    }
}
