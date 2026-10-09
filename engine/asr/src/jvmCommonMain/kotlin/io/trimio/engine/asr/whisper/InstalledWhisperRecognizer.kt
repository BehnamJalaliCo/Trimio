package io.trimio.engine.asr.whisper

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionException
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer

/**
 * Resolves the installed speech model at the moment a job needs it (the user may download or
 * switch models at any time) and keeps that model loaded only while it stays the chosen one.
 */
class InstalledWhisperRecognizer(private val resolveModelPath: () -> String?) : SpeechRecognizer, AutoCloseable {
    private var current: Pair<String, WhisperCppRecognizer>? = null

    override suspend fun transcribe(
        audio: PcmAudio,
        options: RecognitionOptions,
        onProgress: (Float) -> Unit,
        onWord: suspend (index: Int, word: Word) -> Unit,
    ): Transcript {
        val path = resolveModelPath()
            ?: throw RecognitionException("No speech model installed. Download one in Settings → Models.")
        val recognizer = current?.takeIf { it.first == path }?.second ?: run {
            current?.second?.close()
            WhisperCppRecognizer(path).also { current = path to it }
        }
        return recognizer.transcribe(audio, options, onProgress, onWord)
    }

    override fun close() {
        current?.second?.close()
        current = null
    }
}
