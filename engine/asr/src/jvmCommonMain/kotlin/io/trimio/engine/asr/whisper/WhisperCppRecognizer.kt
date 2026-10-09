package io.trimio.engine.asr.whisper

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionException
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.RecognizedToken
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.asr.WordAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * whisper.cpp on device. The model is loaded lazily and kept until [close]; a whisper context is
 * single-threaded, so concurrent calls queue on a mutex. Cancelling the calling coroutine aborts
 * the native decoder at its next checkpoint.
 */
class WhisperCppRecognizer(
    private val modelPath: String,
    private val useGpu: Boolean = false,
) : SpeechRecognizer, AutoCloseable {

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun transcribe(
        audio: PcmAudio,
        options: RecognitionOptions,
        onProgress: (Float) -> Unit,
        onWord: suspend (index: Int, word: Word) -> Unit,
    ): Transcript = mutex.withLock {
        require(audio.sampleRate == PcmAudio.WHISPER_RATE) { "Whisper needs 16 kHz audio, got ${audio.sampleRate}" }
        val ctx = ensureLoaded()
        val fallback = options.language ?: Language.Persian
        val callerJob = coroutineContext.job
        val tokens = ArrayList<RecognizedToken>()
        val streamed = Channel<Word>(Channel.UNLIMITED)

        coroutineScope {
            launch {
                var index = 0
                for (word in streamed) onWord(index++, word)
            }
            val rc = withContext(Dispatchers.Default) {
                WhisperNative.transcribe(
                    ctx, audio.samples, options.language?.code.orEmpty(), options.initialPrompt, options.threads,
                    object : WhisperCallback {
                        override fun onProgress(percent: Int) = onProgress(percent / 100f)

                        override fun onSegment(texts: Array<ByteArray>, startMs: LongArray, endMs: LongArray, probabilities: FloatArray) {
                            val segment = texts.indices.map { RecognizedToken(texts[it], startMs[it], endMs[it], probabilities[it]) }
                            tokens += segment
                            WordAssembler.assemble(segment, fallback).forEach { streamed.trySend(it) }
                        }

                        override fun isCancelled(): Boolean = !callerJob.isActive
                    },
                )
            }
            streamed.close()
            when {
                rc == WhisperNative.ABORTED -> throw CancellationException("Transcription cancelled")
                rc != 0 -> throw RecognitionException("whisper.cpp failed with code $rc")
            }
        }

        val detected = Language.fromCode(WhisperNative.detectedLanguage(ctx)) ?: fallback
        // Re-assemble across segment boundaries so affixes split between segments still join.
        Transcript(detected, WordAssembler.assemble(tokens, detected)).also { onProgress(1f) }
    }

    private fun ensureLoaded(): Long {
        if (handle == 0L) {
            handle = WhisperNative.init(modelPath, useGpu, flashAttention = true)
            if (handle == 0L) throw RecognitionException("Could not load Whisper model at $modelPath")
        }
        return handle
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.free(handle)
            handle = 0L
        }
    }

    companion object {
        /** CPU features whisper.cpp was built with (NEON, dotprod, ...), for diagnostics. */
        fun systemInfo(): String = WhisperNative.systemInfo()
    }
}
