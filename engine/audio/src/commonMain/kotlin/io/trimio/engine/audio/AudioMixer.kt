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
 * Music under the voice: a sidechain from the voice's envelope, run with a look-ahead. The bed
 * starts down [LOOKAHEAD_MS] before each onset (the edit knows the words ahead of time), reaches
 * its floor as the word lands, holds [HOLD_MS] past the phrase (so it does not breathe in short
 * inter-word gaps) and returns over [RELEASE_MS]. The master
 * ([io.trimio.engine.autopilot.Soundtrack]) and the preview mix ([AudioMixer]) run this same shape.
 */
object Ducking {
    /** The fall begins this long before the voice's onset: fully down as the word lands. */
    const val LOOKAHEAD_MS = 150

    /** The bed stays down this long past the voice: short gaps between words do not lift it. */
    const val HOLD_MS = 250

    /** After the hold, the bed climbs back over this long. */
    const val RELEASE_MS = 600

    /**
     * The bed's gain across [speech] (the voice's envelope, 0..1 per sample at [rate]): [under]
     * where the voice is up, [gap] where it is quiet. Every run of speech ramps the bed down over
     * [LOOKAHEAD_MS] (the attack), holds [HOLD_MS] past its end and returns over [RELEASE_MS];
     * overlapping runs keep the bed at its lowest.
     */
    fun envelope(rate: Int, speech: FloatArray, under: Float, gap: Float): FloatArray {
        val look = samples(rate, LOOKAHEAD_MS)
        val hold = samples(rate, HOLD_MS)
        val release = samples(rate, RELEASE_MS)
        val out = FloatArray(speech.size) { gap }
        var i = 0
        while (i < speech.size) {
            if (speech[i] <= 0f) {
                i++
                continue
            }
            val from = i
            while (i < speech.size && speech[i] > 0f) i++
            val to = i
            val depth = (from until to).fold(0f) { m, k -> maxOf(m, speech[k]) }.coerceIn(0f, 1f)
            val floor = gap + (under - gap) * depth
            val attackFrom = maxOf(0, from - look)
            val holdTo = minOf(speech.size, to + hold)
            val releaseTo = minOf(speech.size, holdTo + release)
            for (k in attackFrom until from) out[k] = minOf(out[k], gap + (floor - gap) * (k - attackFrom + 1f) / look)
            for (k in from until holdTo) out[k] = minOf(out[k], floor)
            for (k in holdTo until releaseTo) out[k] = minOf(out[k], floor + (gap - floor) * (k - holdTo + 1f) / release)
        }
        return out
    }

    private fun samples(rate: Int, ms: Int) = (rate * ms / 1000).coerceAtLeast(1)
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

    /** The bed's gain under the speech ranges: [Ducking]'s look-ahead, hold and release around every word. */
    internal fun duckingEnvelope(length: Int, speech: List<TimeRange>): FloatArray {
        val said = FloatArray(length)
        for (r in speech) {
            for (i in index(r.startMs).coerceIn(0, length) until index(r.endMs).coerceIn(0, length)) said[i] = 1f
        }
        return Ducking.envelope(outputRate, said, dbToGain(duckDb), 1f)
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
