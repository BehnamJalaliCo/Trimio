package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.assets.ProceduralMusic
import io.trimio.engine.assets.ProceduralSfx
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan
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
 * equal-power crossfades, cleaned (high-pass) and levelled (gentle compression); music under it that
 * ducks 8 dB while words are spoken and breathes up in the gaps; the sound cues; then the whole
 * mix brought to -14 LUFS with peaks held under -1 dBTP.
 */
class Soundtrack(private val rate: Int = 48_000) {

    data class Result(val audio: PcmAudio, val lufs: Double, val peakDb: Double)

    data class Levels(
        /** Music bed relative to the voice, under words and between them. */
        val musicUnderDb: Float = -20f,
        val musicGapDb: Float = -12f,
        val targetLufs: Double = -14.0,
        val ceilingDb: Float = -1.2f,
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
    ): Result {
        require(voice.sampleRate == rate) { "voice must be at $rate Hz" }
        val n = (duration * rate).toInt()
        val mix = splice(voice, edit, n)
        highPass(mix, HPF_HZ)
        compress(mix)
        val voicePeak = mix.maxOfOrNull { abs(it) } ?: 0f
        if (music != null) addMusic(mix, music, musicSeed, words, n, levels, voiceRms(mix, words), enter = edit.preludeLength)
        addCues(mix, cues, voicePeak)
        // Gain to target, limit, and again: limiting peaks lowers loudness a little each pass.
        repeat(LOUDNESS_PASSES) {
            val measured = LoudnessMeter.measure(PcmAudio(mix, rate)).integratedLufs
            val gain = if (measured.isFinite()) 10.0.pow((levels.targetLufs - measured) / 20.0).toFloat().coerceIn(1f / MAX_GAIN, MAX_GAIN) else 1f
            for (i in mix.indices) mix[i] *= gain
            limit(mix, 10f.pow(levels.ceilingDb / 20f))
        }
        val out = PcmAudio(mix, rate)
        val m = LoudnessMeter.measure(out)
        return Result(out, m.integratedLufs, m.samplePeakDb)
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
     * Music ducked under words (40 ms attack, 400 ms release), out over the last 0.8 s. With a cold
     * open it [enter]s on the restart: the flash-forward plays dry, and the bed lands with the
     * rewind's hit (on its own grid, so beats snapped to it stay on it).
     */
    private fun addMusic(mix: FloatArray, mood: MusicMood, seed: Int, words: List<Word>, n: Int, levels: Levels, voiceRms: Float, enter: Float) {
        val bed = ProceduralMusic(rate).render(mood, seed).audio.samples
        if (bed.isEmpty()) return
        val bedRms = sqrt(bed.sumOf { (it * it).toDouble() } / bed.size).toFloat().coerceAtLeast(1e-4f)
        val spoken = BooleanArray(n)
        for (w in words) for (i in ((w.range.startMs - 60) * rate / 1000).toInt() until ((w.range.endMs + 120) * rate / 1000).toInt()) if (i in spoken.indices) spoken[i] = true
        val under = voiceRms / bedRms * db(levels.musicUnderDb)
        val gap = voiceRms / bedRms * db(levels.musicGapDb)
        val attack = exp(-1.0 / (rate * 0.04)).toFloat()
        val release = exp(-1.0 / (rate * 0.4)).toFloat()
        var gain = gap
        for (i in 0 until n) {
            val target = if (spoken[i]) under else gap
            val c = if (target < gain) attack else release
            gain = target + (gain - target) * c
            val tail = ((n - i) / (rate * 0.8f)).coerceIn(0f, 1f)
            val head = ((i - enter * rate) / (rate * 0.03f)).coerceIn(0f, 1f)
            mix[i] += bed[i % bed.size] * gain * tail * head
        }
    }

    private fun addCues(mix: FloatArray, cues: List<Sfx>, voicePeak: Float) {
        val sfx = ProceduralSfx(rate)
        val scale = voicePeak.coerceIn(0.05f, 1f)
        for ((i, cue) in cues.withIndex()) {
            val (id, g) = VOICES.getValue(cue.kind)
            val pcm = sfx.generate(id, seed = i + 1) ?: continue
            val start = (cue.at * rate).toInt()
            for (j in pcm.samples.indices) if (start + j in mix.indices) mix[start + j] += pcm.samples[j] * g * cue.gain * scale
        }
    }

    /** Look-ahead peak limiter: 5 ms look-ahead, 80 ms release, never above [ceiling]. */
    private fun limit(x: FloatArray, ceiling: Float) {
        val look = rate / 200
        val rel = exp(-1.0 / (rate * 0.08)).toFloat()
        val need = FloatArray(x.size) { i -> val v = abs(x[i]); if (v > ceiling) ceiling / v else 1f }
        // Running minimum over the look-ahead window, so gain is down before the peak arrives.
        val ahead = FloatArray(x.size)
        val dq = ArrayDeque<Int>()
        for (i in x.indices.reversed()) {
            while (dq.isNotEmpty() && dq.first() > i + look) dq.removeFirst()
            while (dq.isNotEmpty() && need[dq.last()] >= need[i]) dq.removeLast()
            dq.addLast(i)
            ahead[i] = need[dq.first()]
        }
        var g = 1f
        for (i in x.indices) {
            g = if (ahead[i] < g) ahead[i] else rel * g + (1 - rel) * ahead[i]
            x[i] = (x[i] * g).coerceIn(-ceiling, ceiling)
        }
    }

    private fun db(v: Float) = 10f.pow(v / 20f)

    private companion object {
        const val HPF_HZ = 90f
        const val RATIO = 2.5f
        const val MAX_GAIN = 16f
        const val LOUDNESS_PASSES = 3
        const val HALF_PI = 1.5707964f
        /** Pieces closer than this on the output clock are one join. */
        const val JOIN_S = 0.002f
        val VOICES = mapOf(
            SfxKind.Whoosh to ("whoosh" to 0.32f), SfxKind.Swish to ("swoosh" to 0.22f), SfxKind.Hit to ("impact" to 0.3f),
            SfxKind.Boom to ("impact" to 0.5f), SfxKind.Pop to ("pop" to 0.25f), SfxKind.Click to ("glitch" to 0.2f),
            SfxKind.Riser to ("riser" to 0.2f), SfxKind.Tick to ("tick" to 0.18f), SfxKind.Shimmer to ("shimmer" to 0.22f),
            SfxKind.Rewind to ("rewind" to 0.26f),
        )
    }
}
