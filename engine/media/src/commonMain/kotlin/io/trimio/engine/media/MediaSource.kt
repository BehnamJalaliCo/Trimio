package io.trimio.engine.media

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri

/** Reads container metadata without decoding. */
interface MediaProbe {
    suspend fun probe(uri: MediaUri): MediaInfo
}

/** Decodes the first audio track of a file. */
interface AudioDecoder {
    /**
     * Full track as mono float PCM at the track's native rate (channels averaged).
     * [onProgress] receives 0..1 by media time. Must honour coroutine cancellation.
     */
    suspend fun decodeMono(uri: MediaUri, onProgress: (Float) -> Unit = {}): PcmAudio
}

class MediaFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
