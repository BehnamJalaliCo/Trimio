package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/** Loads sound assets (SFX, music) by id. Phase 6 provides the procedural and downloaded library. */
fun interface AudioAssetSource {
    suspend fun load(assetId: String): PcmAudio?
}

/**
 * Final soundtrack of an edit at [outputRate]:
 *  1. voice: source audio with the timeline's cuts applied, joined with short equal-power crossfades
 *     (a hard cut in the middle of a waveform clicks), normalised for delivery;
 *  2. music: looped under the edit, ducked automatically while someone speaks;
 *  3. sound effects at their clip times;
 *  4. a soft-knee peak limiter at [ceilingDb].
 */
class AudioMixer(
    private val outputRate: Int = 48_000,
    private val resampler: Resampler = Resampler(),
    private val normalizer: LoudnessNormalizer = LoudnessNormalizer(),
    private val crossfadeMs: Int = 8,
    private val duckDb: Float = -12f,
    private val ceilingDb: Float = -1f,
) {
    suspend fun mix(
        timeline: Timeline,
        sourceAudio: PcmAudio?,
        sourceDurationMs: Long,
        speechOutput: List<TimeRange>,
        assets: AudioAssetSource,
    ): PcmAudio {
        val length = (timeline.durationMs * outputRate / 1000).toInt()
        val out = FloatArray(length)

        sourceAudio?.let { src ->
            val voice = normalizer.normalize(resampler.resample(src, outputRate)).audio
            spliceInto(out, voice, EditMap.of(timeline, sourceDurationMs))
        }

        val duck = duckingEnvelope(length, speechOutput)
        for (music in timeline.clipsOf<MusicClip>()) {
            val pcm = assets.load(music.assetId)?.let { resampler.resample(it, outputRate) } ?: continue
            val gain = dbToGain(music.gainDb)
            val start = index(music.range.startMs)
            val end = index(music.range.endMs).coerceAtMost(length)
            // Short fade-in, long musical fade-out at the end of the edit.
            val fadeIn = (outputRate * MUSIC_FADE_IN_MS / 1000).coerceAtMost((end - start) / 2).coerceAtLeast(1)
            val fadeOut = (outputRate * MUSIC_FADE_OUT_MS / 1000).coerceAtMost((end - start) / 2).coerceAtLeast(1)
            for (i in start until end) {
                val sample = pcm.samples[(i - start) % pcm.size]
                val edge = minOf(1f, (i - start).toFloat() / fadeIn, (end - 1 - i).toFloat() / fadeOut)
                out[i] += sample * gain * edge * (if (music.duckUnderSpeech) duck[i] else 1f)
            }
        }

        for (sfx in timeline.clipsOf<SfxClip>()) {
            val pcm = assets.load(sfx.assetId)?.let { resampler.resample(it, outputRate) } ?: continue
            val gain = dbToGain(sfx.gainDb)
            val start = index(sfx.range.startMs)
            for (k in 0 until pcm.size) {
                val i = start + k
                if (i >= length) break
                out[i] += pcm.samples[k] * gain
            }
        }

        limit(out)
        return PcmAudio(out, outputRate)
    }

    /** Copies kept source segments back to back, crossfading each join. */
    private fun spliceInto(out: FloatArray, voice: PcmAudio, edit: EditMap) {
        val fade = outputRate * crossfadeMs / 1000
        var cursor = 0
        edit.kept.forEachIndexed { n, seg ->
            val from = index(seg.startMs)
            val to = index(seg.endMs).coerceAtMost(voice.size)
            for (k in from until to) {
                val i = cursor + (k - from)
                if (i >= out.size) break
                var g = 1f
                // Equal-power fades at both edges of every join (not at the very start/end).
                if (n > 0 && k - from < fade) g *= equalPower((k - from).toFloat() / fade)
                if (n < edit.kept.lastIndex && to - k <= fade) g *= equalPower((to - k).toFloat() / fade)
                out[i] += voice.samples[k] * g
            }
            cursor += to - from
        }
    }

    /** 1.0 normally, [duckDb] under speech, with 150 ms attack and 400 ms release. */
    internal fun duckingEnvelope(length: Int, speech: List<TimeRange>): FloatArray {
        val target = FloatArray(length) { 1f }
        val low = dbToGain(duckDb)
        for (r in speech) for (i in index(r.startMs).coerceIn(0, length) until index(r.endMs).coerceIn(0, length)) target[i] = low
        val attack = coefficient(150)
        val release = coefficient(400)
        var level = 1f
        // Look-ahead: walking backwards in time, drop instantly to the duck level and recover slowly,
        // so the forward signal starts ramping down `attack` before speech begins.
        val ahead = FloatArray(length)
        for (i in length - 1 downTo 0) {
            level = if (target[i] < level) target[i] else level + (target[i] - level) * (1 - attack)
            ahead[i] = level
        }
        level = 1f
        for (i in 0 until length) {
            val goal = minOf(target[i], ahead[i])
            level = if (goal < level) goal else level + (goal - level) * (1 - release)
            target[i] = level
        }
        return target
    }

    /** Soft knee above -6 dB below the ceiling; never exceeds the ceiling. */
    private fun limit(x: FloatArray) {
        val ceiling = dbToGain(ceilingDb)
        val knee = ceiling * 0.5f
        for (i in x.indices) {
            val v = x[i]
            val a = abs(v)
            if (a > knee) {
                val over = (a - knee) / (ceiling - knee)
                x[i] = (knee + (ceiling - knee) * tanh(over)) * if (v < 0) -1 else 1
            }
        }
    }

    private fun coefficient(ms: Int) = exp(-1.0 / (outputRate * ms / 1000.0)).toFloat()
    private fun equalPower(p: Float) = kotlin.math.sin(p.coerceIn(0f, 1f) * kotlin.math.PI / 2).toFloat()
    private fun index(ms: Long) = (ms * outputRate / 1000).toInt()
    private fun dbToGain(db: Float) = 10.0.pow(db / 20.0).toFloat()

    private companion object {
        const val MUSIC_FADE_IN_MS = 300
        const val MUSIC_FADE_OUT_MS = 1_500
    }
}
