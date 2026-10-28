package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.Arranger
import io.trimio.engine.assets.MusicArrangement
import io.trimio.engine.assets.MusicComposer
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.assets.ProceduralSfx
import io.trimio.engine.assets.SfxPalette
import io.trimio.engine.assets.SoundPresets
import io.trimio.engine.assets.packs.ContentPacks
import io.trimio.engine.audio.Ducking
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mastered soundtrack (research report, audio): the voice cut to the edit with short
 * equal-power crossfades, cleaned (high-pass) and levelled (gentle compression); a cue composed to
 * the edit ([MusicArrangement]: the drop on the payoff or the restart, a breakdown under the call to
 * action, the final hit on a downbeat) that ducks under words and breathes up in the gaps; the sound
 * cues — the whooshes and swishes derived from the moves they follow, the rest voiced by the style's
 * [SfxPalette] — staged under the voice; then the whole mix brought to -14 LUFS with peaks held
 * under -1 dBTP.
 */
class Soundtrack(private val rate: Int = 48_000) {

    data class Result(val audio: PcmAudio, val lufs: Double, val peakDb: Double)

    @Serializable
    data class Levels(
        /**
         * Music bed relative to the voice, under words and between them: a steady bed about 14 dB under the voice that
         * only breathes up 3 dB between phrases (the reference edit's bed sits ~10 dB under and never drops out; a bed
         * that sinks under every word and swells in every gap pumps).
         */
        val musicUnderDb: Float = -14f,
        val musicGapDb: Float = -11f,
        val targetLufs: Double = -14.0,
        /** True-peak ceiling, dBTP (4× oversampled, as a DAC reconstructs it). */
        val ceilingDb: Float = -1.0f,
    )

    fun master(
        voice: PcmAudio,
        edit: EditPlan,
        /** Words on the output clock: where the music ducks. */
        words: List<Word>,
        duration: Float,
        music: MusicMood?,
        musicSeed: Int,
        cues: List<Sfx>,
        levels: Levels = Levels(),
        /** The cue's arrangement; null: one fitted here (the drop on the restart, else the groove from the start). */
        arrangement: MusicArrangement? = null,
        /** How the cues sound; null: the palette that suits [music]. */
        palette: SfxPalette? = null,
        /** The motion behind any cue that has one; the rest read their move off their params ([Motion.of]). */
        motions: Map<Sfx, Motion> = emptyMap(),
    ): Result {
        require(voice.sampleRate == rate) { "voice must be at $rate Hz" }
        val n = (duration * rate).toInt()
        val mix = splice(voice, edit, n)
        highPass(mix, HPF_HZ)
        compress(mix)
        val voicePeak = mix.maxOfOrNull { abs(it) } ?: 0f
        if (music != null) {
            val arr = arrangement?.takeIf { it.mood == music } ?: Arranger.fit(
                music, edit.preludeLength.toDouble(), duration.toDouble(), drop = edit.preludeLength.toDouble().takeIf { it > 0.0 },
            )
            addMusic(mix, arr, musicSeed, words, n, levels, voiceRms(mix, words), enter = edit.preludeLength, fade = maxOf(FADE_OUT_S, edit.outro?.length ?: 0f))
        }
        addCues(mix, cues, voicePeak, palette ?: SoundPresets.paletteFor(music), words, motions)
        val out = PcmAudio(loudnessAndPeak(mix, levels), rate)
        val m = LoudnessMeter.measure(out)
        return Result(out, m.integratedLufs, m.samplePeakDb)
    }

    /**
     * The mix to the target loudness under the true-peak ceiling. Each pass limits the unlimited
     * mix at the current gain and measures the result, so the gain accounts for what the limiter
     * takes away (and the limiter never works on its own output).
     */
    private fun loudnessAndPeak(mix: FloatArray, levels: Levels): FloatArray {
        val limiter = TruePeakLimiter(rate, levels.ceilingDb)
        // Gain in dB, stepped by the secant through the last two passes: where the limiter is busy
        // (dense hits) a dB of gain buys less than a dB of loudness, and a plain step falls short.
        val maxDb = 20 * log10(MAX_GAIN.toDouble())
        var gainDb = 0.0
        var last: Pair<Double, Double>? = null
        var out = mix
        for (pass in 1..LOUDNESS_PASSES) {
            val gain = 10.0.pow(gainDb / 20.0).toFloat()
            out = FloatArray(mix.size) { mix[it] * gain }.also { limiter.limit(it) }
            val measured = LoudnessMeter.measure(PcmAudio(out, rate)).integratedLufs
            if (!measured.isFinite() || abs(measured - levels.targetLufs) < LUFS_TOLERANCE) break
            val slope = last?.let { (g, m) -> ((measured - m) / (gainDb - g)).takeIf { it.isFinite() } }?.coerceIn(MIN_SLOPE, 1.0) ?: 1.0
            last = gainDb to measured
            gainDb = (gainDb + (levels.targetLufs - measured) / slope).coerceIn(-maxDb, maxDb)
        }
        return out
    }

    /**
     * The voice spliced to the cut list with equal-power fades ([CutPoints.FADE]) at every cut, so
     * no join clicks. Where two pieces meet on the output clock they crossfade, each running half a
     * fade into its handle (the source beyond its cut point, quiet: cuts sit in valleys), with
     * sin/cos gains whose powers sum to one. Where a piece meets silence (the cold open's ends, the
     * body after the restart) it fades within itself: the cut points were chosen so that the fade
     * covers only the quiet before an onset or after a tail.
     */
    internal fun splice(voice: PcmAudio, edit: EditPlan, n: Int): FloatArray {
        val out = FloatArray(n)
        val played = edit.played
        val fade = (CutPoints.FADE * rate).toInt().coerceAtLeast(2)
        for ((k, seg) in played.withIndex()) {
            val a = (seg.sourceStart * rate).roundToInt()
            val o = (seg.outStart * rate).roundToInt()
            val len = (seg.sourceEnd * rate).roundToInt() - a
            val from = if (k > 0 && joined(played[k - 1], seg)) -fade / 2 else 0
            val to = len + if (k < played.lastIndex && joined(seg, played[k + 1])) fade - fade / 2 else 0
            for (i in from until to) {
                val at = o + i
                if (at !in out.indices || a + i !in voice.samples.indices) continue
                val fadeIn = ((i - from + 0.5f) / fade).coerceIn(0f, 1f)
                val fadeOut = ((to - i - 0.5f) / fade).coerceIn(0f, 1f)
                out[at] += voice.samples[a + i] * sin(fadeIn * HALF_PI) * sin(fadeOut * HALF_PI)
            }
        }
        return out
    }

    /** Two pieces that meet on the output clock (a cut, not a gap). */
    private fun joined(a: EditPlan.Segment, b: EditPlan.Segment) = abs(b.outStart - a.outEnd) < JOIN_S

    /** Second-order Butterworth high-pass: rumble and plosive thumps out, voice untouched. */
    private fun highPass(x: FloatArray, hz: Float) {
        val w = 2.0 * PI * hz / rate
        val q = 0.7071
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        val a0 = 1 + alpha
        val b0 = ((1 + c) / 2 / a0).toFloat()
        val b1 = (-(1 + c) / a0).toFloat()
        val a1 = (-2 * c / a0).toFloat()
        val a2 = ((1 - alpha) / a0).toFloat()
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val y = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = y
            x[i] = y
        }
    }

    /** 2.5:1 above the speech level with 10 ms attack and 150 ms release: even, present speech. */
    private fun compress(x: FloatArray) {
        val loud = x.filter { abs(it) > 1e-3f }
        if (loud.isEmpty()) return
        val rms = sqrt(loud.sumOf { (it * it).toDouble() } / loud.size).toFloat()
        val threshold = rms * 1.4f
        val att = exp(-1.0 / (rate * 0.010)).toFloat()
        val rel = exp(-1.0 / (rate * 0.150)).toFloat()
        var env = 0f
        for (i in x.indices) {
            val v = abs(x[i])
            env = if (v > env) att * env + (1 - att) * v else rel * env + (1 - rel) * v
            if (env > threshold) {
                val overDb = 20f * log10(env / threshold)
                x[i] *= 10f.pow(-(overDb - overDb / RATIO) / 20f)
            }
        }
    }

    private fun voiceRms(x: FloatArray, words: List<Word>): Float {
        var sum = 0.0
        var count = 0
        for (w in words) for (i in (w.range.startMs * rate / 1000).toInt() until (w.range.endMs * rate / 1000).toInt()) if (i in x.indices) { sum += x[i] * x[i]; count++ }
        return if (count == 0) 0.1f else sqrt(sum / count).toFloat()
    }

    /**
     * Music ducked under words — [Ducking]'s look-ahead shape (down before each onset, held past
     * the phrase, back over a breath), keyed by the voice's own envelope — out over the last [fade]
     * seconds (0.8 s; an end hold's whole length: the bed carries the held frame, then lets go).
     * With a cold open it [enter]s on the restart: the flash-forward plays dry, and the cue drops
     * with the rewind's hit. The cue is composed on the output clock, so beats snapped to its grid
     * stay on it. Its level is set from its body (start to final hit), so the quiet intro and
     * breakdown stay quieter than the drop: the arrangement's dynamics survive the mix.
     */
    private fun addMusic(mix: FloatArray, arr: MusicArrangement, seed: Int, words: List<Word>, n: Int, levels: Levels, voiceRms: Float, enter: Float, fade: Float) {
        val bed = MusicComposer(rate).compose(arr, seed).samples
        if (bed.isEmpty()) return
        val body = (arr.start * rate).toInt().coerceIn(0, bed.size - 1) until (arr.ending * rate).toInt().coerceIn(1, bed.size)
        val bodyN = maxOf(1, body.last - body.first + 1)
        val bedRms = sqrt(body.sumOf { (bed[it] * bed[it]).toDouble() } / bodyN).toFloat().coerceAtLeast(1e-4f)
        val under = voiceRms / bedRms * db(levels.musicUnderDb)
        val gap = voiceRms / bedRms * db(levels.musicGapDb)
        val duck = Ducking.envelope(rate, voiceEnvelope(mix, words, voiceRms), under, gap)
        for (i in 0 until n) {
            val tail = sin(((n - i) / (rate * fade)).coerceIn(0f, 1f) * HALF_PI)
            val head = ((i - enter * rate) / (rate * 0.03f)).coerceIn(0f, 1f)
            if (i < bed.size) mix[i] += bed[i] * duck[i] * tail * head
        }
    }

    /**
     * The voice's envelope — the ducking's sidechain: a follower of the spliced voice where words
     * are said (a breath is not speech), at the word's level against the voice's RMS.
     */
    private fun voiceEnvelope(x: FloatArray, words: List<Word>, norm: Float): FloatArray {
        val env = FloatArray(x.size)
        val said = BooleanArray(x.size)
        for (w in words) {
            val a = (w.range.startMs * rate / 1000).toInt().coerceIn(0, x.size)
            val b = (w.range.endMs * rate / 1000).toInt().coerceIn(a, x.size)
            for (i in a until b) said[i] = true
        }
        val smooth = exp(-1.0 / (rate * ENV_FOLLOW_S)).toFloat()
        var e = 0f
        for (i in x.indices) {
            val v = abs(x[i])
            e = if (said[i]) v + (e - v) * smooth else 0f
            env[i] = (e / norm).coerceIn(0f, 1f)
        }
        return env
    }

    /**
     * The cues, gain-staged under the voice: each kind's level is relative to the voice's peak,
     * a decoration that lands on a word comes down [UNDER_WORD_DB] (an accent — hit, boom, rewind —
     * keeps its weight), and never more than [MAX_CUES_PER_2S] sound in any two seconds.
     *
     * A cue that rides a [Motion] (whoosh, swish) is synthesised from the move itself: the sound is
     * the move's length, its level and filter sweep follow the move's speed, and its swell's peak
     * sits exactly on [Sfx.at] (the landing or the cut). The take cycles through
     * [ProceduralSfx.MOTION_VARIANTS] with a small pitch and gain jitter, so the third whoosh of an
     * edit never sounds like the first. Other cues keep their palette voice and start on their time
     * (a transient belongs on the frame).
     */
    internal fun addCues(
        mix: FloatArray,
        cues: List<Sfx>,
        voicePeak: Float,
        palette: SfxPalette,
        words: List<Word>,
        motions: Map<Sfx, Motion> = emptyMap(),
    ) {
        val sfx = ProceduralSfx(rate)
        val scale = voicePeak.coerceIn(0.05f, 1f)
        val played = mutableListOf<Float>()
        val sorted = cues.sortedBy { it.at }
        val kept = sorted.withIndex().filter { (_, cue) -> (played.count { cue.at - it < 2f } < MAX_CUES_PER_2S).also { ok -> if (ok) played += cue.at } }
        for ((i, cue) in kept) {
            val (base, g) = VOICES.getValue(cue.kind)
            val spoken = cue.kind !in ACCENTS && words.any { w -> cue.at * 1000 >= w.range.startMs && cue.at * 1000 < w.range.endMs }
            val stage = g * cue.gain * scale * (if (spoken) db(UNDER_WORD_DB) else 1f)
            val moving = cue.kind in MOVING
            val pcm = if (moving) {
                val m = motions[cue] ?: Motion.of(cue)
                sfx.generateMotion(base, seed = i + 1, durationMs = (m.moveS * MS_PER_S).toInt(), speed = m.speed, variant = i, palette = palette)
            } else {
                val id = if (base in SfxPalette.CUES) "$base@${palette.id}" else base
                ContentPacks.sfx(base, palette.id, i + 1, rate) ?: sfx.generate(id, seed = i + 1) ?: sfx.generate(base, seed = i + 1)
            }
            if (pcm == null) continue
            val at = (cue.at * rate).toInt()
            // A motion sound's swell peaks on the landing; a transient starts on it.
            val start = if (moving) at - (pcm.size * ProceduralSfx.MOTION_PEAK).toInt() else at
            for (j in pcm.samples.indices) if (start + j in mix.indices) mix[start + j] += pcm.samples[j] * stage
        }
    }

    private fun db(v: Float) = 10f.pow(v / 20f)

    private companion object {
        const val HPF_HZ = 90f
        const val RATIO = 2.5f
        const val MAX_GAIN = 16f
        const val LOUDNESS_PASSES = 6
        const val LUFS_TOLERANCE = 0.05
        /** The least loudness a dB of gain is taken to buy (a limiter flattening every hit). */
        const val MIN_SLOPE = 0.25
        const val FADE_OUT_S = 0.8f
        const val HALF_PI = 1.5707964f
        /** Pieces closer than this on the output clock are one join. */
        const val JOIN_S = 0.002f
        const val UNDER_WORD_DB = -3f
        const val MAX_CUES_PER_2S = 4
        /** A word's window widened for the follower's lag. */
        const val ENV_FOLLOW_S = 0.02f
        const val MS_PER_S = 1_000f
        val ACCENTS = setOf(SfxKind.Hit, SfxKind.Boom, SfxKind.Rewind)
        /** Cues that ride a move (their sound is derived from its curve, see [Motion]). */
        val MOVING = setOf(SfxKind.Whoosh, SfxKind.Swish)
        /** Each kind's cue (voiced by the palette when it is one of [SfxPalette.CUES]) and its level against the voice's peak. */
        val VOICES = mapOf(
            SfxKind.Whoosh to ("whoosh" to 0.32f), SfxKind.Swish to ("swoosh" to 0.22f), SfxKind.Hit to ("impact" to 0.3f),
            SfxKind.Boom to ("impact" to 0.5f), SfxKind.Pop to ("pop" to 0.25f), SfxKind.Click to ("glitch" to 0.2f),
            SfxKind.Riser to ("riser" to 0.2f), SfxKind.Tick to ("tick" to 0.18f), SfxKind.Shimmer to ("shimmer" to 0.22f),
            SfxKind.Rewind to ("rewind" to 0.26f),
        )
    }
}

/**
 * The move behind a sound cue: [moveS] is how long it travels (the whoosh's length) and [speed]
 * its peak speed, 0..1 (the whoosh's level and filter sweep follow it). The caller places the
 * sound so its swell's peak sits on the cue's landing — the whoosh is heard as the move arriving.
 */
data class Motion(val moveS: Float, val speed: Float) {
    companion object {
        /**
         * The move a cue implies when nothing describes one: swishes ride short, quick gestures and
         * whooshes longer, heavier ones. Recipe-side motion reaches
         * [Soundtrack.master] through its cue's params.
         */
        fun of(cue: Sfx): Motion = when (cue.kind) {
            SfxKind.Swish -> Motion(MOVE_SWISH_S, SPEED_SWISH)
            else -> Motion(MOVE_WHOOSH_S, SPEED_WHOOSH)
        }

        private const val MOVE_WHOOSH_S = 0.42f
        private const val MOVE_SWISH_S = 0.24f
        private const val SPEED_WHOOSH = 0.65f
        private const val SPEED_SWISH = 0.8f
    }
}

/**
 * A look-ahead true-peak limiter. Peaks are detected 4× oversampled (Hann-windowed sinc, the way a
 * DAC reconstructs between samples, as BS.1770 meters do), so the ceiling holds in dBTP, not only
 * on samples. The gain needed is held across a [LOOK_S] look-ahead and smoothed by a moving
 * average of the same length — a ramp that is fully down by the peak, never a step — then
 * recovers with a [RELEASE_S] release. No clipping: a pass that still finds an overshoot (a
 * reconstruction of the new, gentler waveform) runs again.
 */
internal class TruePeakLimiter(private val rate: Int, ceilingDbtp: Float) {
    private val ceiling = 10f.pow((ceilingDbtp - MARGIN_DB) / 20f)
    private val look = (rate * LOOK_S).roundToInt().coerceAtLeast(1)
    private val release = exp(-1.0 / (rate * RELEASE_S)).toFloat()

    fun limit(x: FloatArray) {
        repeat(PASSES) { if (!pass(x)) return }
    }

    /** One limiting pass; false when nothing was over. */
    private fun pass(x: FloatArray): Boolean {
        // An overshoot between two samples is both samples' to bring down.
        val gaps = FloatArray(x.size) { between(x, it) }
        val need = FloatArray(x.size) { 1f }
        var over = false
        for (i in x.indices) {
            val p = maxOf(abs(x[i]), gaps[i], if (i > 0) gaps[i - 1] else 0f)
            if (p > ceiling) { need[i] = ceiling / p; over = true }
        }
        if (!over) return false
        // The gain each sample must be under: the minimum need across the look-ahead...
        val held = FloatArray(x.size)
        val dq = ArrayDeque<Int>()
        for (i in x.indices.reversed()) {
            while (dq.isNotEmpty() && dq.first() > i + look) dq.removeFirst()
            while (dq.isNotEmpty() && need[dq.last()] >= need[i]) dq.removeLast()
            dq.addLast(i)
            held[i] = need[dq.first()]
        }
        // ...released slowly (never above the held need)...
        var r = 1f
        for (i in held.indices) {
            r = if (held[i] < r) held[i] else held[i] + (r - held[i]) * release
            held[i] = r
        }
        // ...and averaged over the look-ahead: every sample in a peak's window is at most its need.
        var sum = 0.0
        for (i in x.indices) {
            sum += held[i]
            if (i > look) sum -= held[i - look - 1]
            x[i] *= (sum / (minOf(i, look) + 1)).toFloat()
        }
        return true
    }

    /** The highest point of the band-limited reconstruction between samples [i] and [i] + 1. */
    private fun between(x: FloatArray, i: Int): Float {
        if (i < TAPS - 1 || i + TAPS >= x.size || maxOf(abs(x[i]), abs(x[i + 1])) < ceiling * DETECT) return 0f
        var best = 0f
        for (c in KERNEL) {
            var sum = 0f
            for (j in c.indices) sum += x[i - TAPS + 1 + j] * c[j]
            best = maxOf(best, abs(sum))
        }
        return best
    }

    private companion object {
        const val LOOK_S = 0.0015f
        const val RELEASE_S = 0.08f
        const val MARGIN_DB = 0.05f
        const val PASSES = 3
        /** Only near the ceiling can a reconstruction exceed it (overshoots stay under 6 dB). */
        const val DETECT = 0.5f
        const val TAPS = 8
        const val OVERSAMPLE = 4

        /** Interpolation taps for the points 1/4, 2/4 and 3/4 of the way to the next sample. */
        val KERNEL: List<FloatArray> = (1 until OVERSAMPLE).map { k ->
            val frac = k.toFloat() / OVERSAMPLE
            FloatArray(2 * TAPS) { idx ->
                val d = (idx - TAPS + 1) - frac
                val w = 0.5f + 0.5f * cos(PI.toFloat() * d / TAPS)
                val sinc = if (abs(d) < 1e-6f) 1f else sin(PI.toFloat() * d) / (PI.toFloat() * d)
                sinc * w
            }
        }
    }
}
