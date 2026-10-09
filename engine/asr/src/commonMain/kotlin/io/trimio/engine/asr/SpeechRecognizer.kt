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
}

class RecognitionException(message: String, cause: Throwable? = null) : Exception(message, cause)
