package io.trimio.engine.asr

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word

data class RecognitionOptions(
    /** Spoken language; null auto-detects (Persian/English mixes are handled per word afterwards). */
    val language: Language? = null,
    /**
     * Text the decoder is conditioned on. A short, well-punctuated sentence in the target language
     * noticeably improves Persian punctuation and spelling; names and jargon here improve recall.
     */
    val initialPrompt: String? = null,
    val threads: Int = 4,
)

/**
 * Where speech recognition ran and how fast: the engine build ([backend]), the GPU it used (null
 * on the CPU), threads, beam, and the last transcription's audio length against its time.
 */
data class SpeechRuntime(
    val backend: String,
    val gpu: String?,
    val threads: Int,
    val beam: Int,
    val audioSeconds: Float = 0f,
    val seconds: Float = 0f,
    val facts: List<Pair<String, String>> = emptyList(),
) {
    /** Seconds of audio per second of work (above 1: faster than real time). */
    val realTimeFactor: Float get() = if (seconds <= 0f) 0f else audioSeconds / seconds

    /** "speech vulkan NVIDIA GeForce RTX 3050 Ti, 4 threads, beam 3; 66 s of audio in 12.3 s (5.4× real time)". */
    fun summary(): String = buildString {
        append("speech ").append(backend).append(' ').append(gpu ?: "cpu only")
        append(", ").append(threads).append(" threads, ").append(if (beam > 1) "beam $beam" else "greedy")
        if (seconds > 0f) append("; ").append(audioSeconds.toInt()).append(" s of audio in ").append(tenth(seconds)).append(" s (").append(tenth(realTimeFactor)).append("× real time)")
    }

    private fun tenth(v: Float) = (kotlin.math.round(v * TEN) / TEN).toString()

    private companion object {
        const val TEN = 10f
    }
}

/** One recognised token or word fragment with timing, before merging into words. */
class RecognizedToken(
    /** Raw UTF-8 bytes. BPE tokens can split a multi-byte Persian letter, so text is decoded per word. */
    val bytes: ByteArray,
    val startMs: Long,
    val endMs: Long,
    val probability: Float,
)

/** Speech-to-text with word timing. Implementations: whisper.cpp (Android/desktop), cloud later. */
interface SpeechRecognizer {
    /**
     * Transcribes 16 kHz mono audio. Words are streamed through [onWord] as soon as each decoder
     * segment completes, so the build screen fills in while recognition runs.
     */
    suspend fun transcribe(
        audio: PcmAudio,
        options: RecognitionOptions,
        onProgress: (Float) -> Unit = {},
        onWord: suspend (index: Int, word: Word) -> Unit = { _, _ -> },
    ): Transcript

    /** The last transcription on device: where it ran and how fast; null when not measured. */
    val runtime: SpeechRuntime? get() = null
}

class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)
