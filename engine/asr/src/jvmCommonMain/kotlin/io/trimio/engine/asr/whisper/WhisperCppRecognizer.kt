package io.trimio.engine.asr.whisper

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionException
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.RecognizedToken
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.asr.SpeechRuntime
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
    /** On a GPU build the encoder and decoder run on the dedicated GPU (`-Dtrimio.whisper.gpu=off`: CPU). */
    private val useGpu: Boolean = System.getProperty("trimio.whisper.gpu") != "off",
) : SpeechRecognizer, AutoCloseable {

    private val mutex = Mutex()
    private var handle = 0L
    private var info: Map<String, List<String>> = emptyMap()

    @Volatile
    override var runtime: SpeechRuntime? = null
        private set

    override suspend fun transcribe(
        audio: PcmAudio,
        options: RecognitionOptions,
        onProgress: (Float) -> Unit,
        onWord: suspend (index: Int, word: Word) -> Unit,
    ): Transcript = mutex.withLock {
        require(audio.sampleRate == PcmAudio.WHISPER_RATE) { "Whisper needs 16 kHz audio, got ${audio.sampleRate}" }
        val started = System.nanoTime()
        val ctx = ensureLoaded()
        val beam = beam()
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
                    ctx, audio.samples, options.language?.code.orEmpty(), options.initialPrompt, options.threads, beam,
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

        val seconds = (System.nanoTime() - started) / NANOS
        runtime = runtimeOf(options.threads, beam, audio.samples.size / audio.sampleRate.toFloat(), seconds).also { System.err.println("trimio " + it.summary()) }
        val detected = Language.fromCode(WhisperNative.detectedLanguage(ctx)) ?: fallback
        // Re-assemble across segment boundaries so affixes split between segments still join.
        Transcript(detected, WordAssembler.assemble(tokens, detected)).also { onProgress(1f) }
    }

    private fun ensureLoaded(): Long {
        if (handle == 0L) {
            handle = WhisperNative.init(modelPath, useGpu, flashAttention = true)
            if (handle == 0L) throw RecognitionException("Could not load Whisper model at $modelPath")
            info = WhisperNative.runtimeInfo().lineSequence().filter { '=' in it }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=').trim() })
        }
        return handle
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.free(handle)
            handle = 0L
        }
    }

    /** The GPU whisper.cpp reported using ("using Vulkan0 backend"), by its description; null on the CPU. */
    private fun gpu(): String? {
        val name = info["log"].orEmpty().firstNotNullOfOrNull { USING.find(it)?.groupValues?.get(1) }?.takeUnless { it == "CPU" } ?: return null
        return info["device"].orEmpty().map { it.split('|') }.firstOrNull { it.getOrNull(1) == name }?.getOrNull(2) ?: name
    }

    private fun runtimeOf(threads: Int, beam: Int, audioSeconds: Float, seconds: Float) = SpeechRuntime(
        backend = WhisperNative.variant.ifEmpty { "cpu" }, gpu = gpu(), threads = threads, beam = beam,
        audioSeconds = audioSeconds, seconds = seconds, facts = info.flatMap { (k, v) -> v.map { k to it } },
    )

    /**
     * The beam: `-Dtrimio.whisper.beam` (1 is greedy), else 3. Greedy decodes about 2.5× faster on
     * a CPU (measured with the tiny model) but spells rare Persian words worse, and spelling is
     * what creators notice; phones get their speed from the turbo model instead.
     */
    private fun beam(): Int = System.getProperty("trimio.whisper.beam")?.toIntOrNull()?.coerceIn(1, MAX_BEAM) ?: BEAM

    companion object {
        private const val NANOS = 1e9f
        private const val BEAM = 3
        private const val MAX_BEAM = 8
        private val USING = Regex("using (\\S+) backend")

        /** CPU features whisper.cpp was built with (NEON, dotprod, ...), for diagnostics. */
        fun systemInfo(): String = WhisperNative.systemInfo()
    }
}
