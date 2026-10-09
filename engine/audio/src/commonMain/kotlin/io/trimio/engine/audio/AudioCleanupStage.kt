package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Numerals
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.media.AudioDecoder
import kotlin.math.roundToInt

/**
 * Decode → 16 kHz mono → loudness normalisation → speech detection → prosody features.
 * Publishes [StandardArtifacts.CleanAudio] and [StandardArtifacts.AudioFeatures] and streams
 * a scanning waveform to the build screen while it works.
 *
 * Videos without an audio track pass through untouched; later speech stages see no CleanAudio.
 */
class AudioCleanupStage(
    private val decoder: AudioDecoder,
    private val resampler: Resampler = Resampler(),
    private val normalizer: LoudnessNormalizer = LoudnessNormalizer(),
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
    private val prosody: ProsodyAnalyzer = ProsodyAnalyzer(),
) : PipelineStage {
    override val id = StageId.AudioCleanup

    override suspend fun run(context: StageContext) {
        val info = context.artifacts[StandardArtifacts.MediaInfo]
        if (info != null && !info.hasAudio) {
            context.progress(1f)
            return
        }

        val decoded = decoder.decodeMono(context.job.input.uri) { context.progress(it * DECODE_SHARE) }
        val mono16k = resampler.resample(decoded, PcmAudio.WHISPER_RATE)
        context.progress(0.5f)

        val normalized = normalizer.normalize(mono16k)
        context.progress(0.6f)
        streamWaveform(context, normalized.audio, from = 0.6f, to = 0.75f)

        val speech = vad.detect(normalized.audio)
        context.progress(0.8f)

        val features = prosody.features(
            audio = normalized.audio,
            speech = speech,
            integratedLufs = normalized.measuredLufs.toFloat(),
            appliedGainDb = normalized.appliedGainDb.toFloat(),
        )
        context.artifacts[StandardArtifacts.CleanAudio] = normalized.audio
        context.artifacts[StandardArtifacts.AudioFeatures] = features

        val lufs = if (normalized.measuredLufs.isInfinite()) "—" else normalized.measuredLufs.roundToInt().toString()
        val gain = normalized.appliedGainDb.roundToInt().let { if (it >= 0) "+$it" else "$it" }
        context.emit(
            LiveSignal.Note(
                Numerals.toPersian("بلندی صدا $lufs LUFS، اصلاح $gain dB، ${speech.size} بخش گفتار"),
                "Loudness $lufs LUFS, gain $gain dB, ${speech.size} speech regions",
            ),
        )
        context.progress(1f)
    }

    /** Sweeps across the recording in windows so the UI shows the audio being "scanned". */
    private suspend fun streamWaveform(context: StageContext, audio: PcmAudio, from: Float, to: Float) {
        val windows = WAVEFORM_FRAMES
        val window = (audio.size / windows).coerceAtLeast(1)
        for (w in 0 until windows) {
            val start = w * window
            context.emit(LiveSignal.Waveform(Waveform.bars(audio, WAVEFORM_BARS, start, start + window * 4)))
            context.progress(from + (to - from) * (w + 1) / windows)
        }
    }

    private companion object {
        const val DECODE_SHARE = 0.4f
        const val WAVEFORM_FRAMES = 24
        const val WAVEFORM_BARS = 48
    }
}
