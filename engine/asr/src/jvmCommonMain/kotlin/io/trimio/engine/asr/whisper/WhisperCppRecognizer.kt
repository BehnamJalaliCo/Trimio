package io.trimio.engine.asr.whisper

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionException
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.RecognizedToken
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.asr.SpeechOnly
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
    /** Silero VAD (ggml-silero-v6.2.0.bin) when installed: only speech is decoded, token times mapped back. */
    private val vadModelPath: String? = null,
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
        // Speech only (Silero VAD): long silences are cut before decoding; token times come back to the source timeline.
        // Any VAD failure falls back to the whole audio (exactly the path without the add-on) and is reported.
        val speech = vadModelPath?.let { runCatching { speechOnly(audio, it, options.threads) }.getOrNull() }
        val input = speech?.audio ?: audio
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
                    ctx, input.samples, options.language?.code.orEmpty(), options.initialPrompt, options.threads, beam,
                    object : WhisperCallback {
                        override fun onProgress(percent: Int) = onProgress(percent / 100f)

                        override fun onSegment(texts: Array<ByteArray>, startMs: LongArray, endMs: LongArray, probabilities: FloatArray) {
                            val segment = texts.indices.map {
                                val start = speech?.toSource(startMs[it], isStart = true) ?: startMs[it]
                                val end = speech?.toSource(endMs[it], isStart = false)?.coerceAtLeast(start) ?: endMs[it]
                                RecognizedToken(texts[it], start, end, probabilities[it])
                            }
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
        val vadFacts = speech?.let { listOf("vad" to "silero: ${it.speechSeconds.toInt()} s of speech in ${it.sourceSeconds.toInt()} s") }
            ?: vadModelPath?.let { listOf("vad" to "silero failed or found no speech: whole audio decoded") }.orEmpty()
        runtime = runtimeOf(options.threads, beam, audio.samples.size / audio.sampleRate.toFloat(), seconds, vadFacts).also { System.err.println("trimio " + it.summary()) }
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

    private fun runtimeOf(threads: Int, beam: Int, audioSeconds: Float, seconds: Float, extra: List<Pair<String, String>>) = SpeechRuntime(
        backend = WhisperNative.variant.ifEmpty { "cpu" }, gpu = gpu(), threads = threads, beam = beam,
        audioSeconds = audioSeconds, seconds = seconds, facts = info.flatMap { (k, v) -> v.map { k to it } } + extra,
    )

    /**
     * The speech of [audio] by Silero VAD, or null (decode everything) when the VAD model fails to
     * load or run, finds no speech, or finds almost nothing to cut. The VAD context lives only for
     * this call: it is under 1 MB and loads in milliseconds.
     */
    private fun speechOnly(audio: PcmAudio, model: String, threads: Int): SpeechOnly? {
        val vad = runCatching { WhisperNative.vadInit(model, threads) }.getOrDefault(0L).takeIf { it != 0L } ?: return null
        return try {
            val t = WhisperNative.vadSegments(vad, audio.samples, VAD_THRESHOLD, VAD_MIN_SPEECH_MS, VAD_MIN_SILENCE_MS, VAD_PAD_MS) ?: return null
            SpeechOnly.of(audio, (0 until t.size / 2).map { t[2 * it]..t[2 * it + 1] })
        } finally {
            WhisperNative.vadFree(vad)
        }
    }

    /**
     * The beam: `-Dtrimio.whisper.beam` (1 is greedy), else 3. Greedy decodes about 2.5× faster on
     * a CPU (measured with the tiny model) but spells rare Persian words worse, and spelling is
     * what creators notice; phones get their speed from the turbo model instead.
     */
    private fun beam(): Int = System.getProperty("trimio.whisper.beam")?.toIntOrNull()?.coerceIn(1, MAX_BEAM) ?: BEAM

    companion object {
        private const val NANOS = 1e9f
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SPEECH_MS = 250

        /** Pauses shorter than this stay in: cutting a breath gains nothing and can clip a word's tail. */
        private const val VAD_MIN_SILENCE_MS = 500
        private const val VAD_PAD_MS = 150
        private const val BEAM = 3
        private const val MAX_BEAM = 8
        private val USING = Regex("using (\\S+) backend")

        /** CPU features whisper.cpp was built with (NEON, dotprod, ...), for diagnostics. */
        fun systemInfo(): String = WhisperNative.systemInfo()
    }
}
