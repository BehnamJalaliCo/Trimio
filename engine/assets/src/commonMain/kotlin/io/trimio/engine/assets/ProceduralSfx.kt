package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The sound-effect library, synthesised on demand. Names are the `sfx/<name>` ids style packs use
 * in their event map. Each effect is designed for short-form video: fast transient, no long tail
 * to muddy the voice, peak at -1 dBFS (the mixer applies the clip gain).
 */
class ProceduralSfx(private val rate: Int = 48_000) {

    data class Effect(val id: String, val nameFa: String, val nameEn: String)

    val catalog: List<Effect> = listOf(
        Effect("pop", "پاپ", "Pop"),
        Effect("click", "کلیک", "Click"),
        Effect("tick", "تیک", "Tick"),
        Effect("whoosh", "ووش", "Whoosh"),
        Effect("whoosh-soft", "ووش نرم", "Soft whoosh"),
        Effect("swoosh", "سووش کوتاه", "Swoosh"),
        Effect("impact", "ضربه", "Impact"),
        Effect("shimmer", "درخشش", "Shimmer"),
        Effect("riser", "اوج\u200Cگیری", "Riser"),
        Effect("ding", "دینگ", "Ding"),
        Effect("cash", "صدای پول", "Cash register"),
        Effect("glitch", "گلیچ", "Glitch"),
        Effect("rewind", "برگشت", "Rewind"),
        Effect("swell", "اوج معکوس", "Swell"),
        Effect("notify", "اعلان", "Notification"),
    )

    private val layered = LayeredSfx(rate)

    /**
     * [id] is a cue (`whoosh`) or a cue voiced by a style's palette (`whoosh@cinematic`, see
     * [SfxPalette]): the layered transient + body + tail version.
     */

    fun generate(id: String, seed: Int = 1): PcmAudio? {
        val s = Synth(rate)
        if ('@' in id) {
            val palette = SfxPalette.fromId(id.substringAfter('@')) ?: return null
            val raw = layered.render(id.substringBefore('@'), palette, seed) ?: return null
            return PcmAudio(Synth.normalize(fadeOut(raw, s)), rate)
        }
        val samples = when (id) {
            "pop" -> pop(s)
            "click" -> click(s, seed)
            "tick" -> tick(s)
            "whoosh" -> whoosh(s, seed, 520, 4_000f, 0.62f)
            "whoosh-soft" -> whoosh(s, seed, 680, 1_800f, 0.55f)
            "swoosh" -> whoosh(s, seed, 280, 5_000f, 0.45f)
            "impact" -> impact(s, seed)
            "shimmer" -> shimmer(s)
            "riser" -> riser(s, seed)
            "ding" -> ding(s, 1_318f, 900)
            "cash" -> cash(s, seed)
            "glitch" -> glitch(s, seed)
            "rewind" -> rewind(s, seed)
            "swell" -> layered.render("swell", SfxPalette.Soft, seed)!!
            "notify" -> layered.render("notify", SfxPalette.Luxe, seed)!!
            else -> return null
        }
        return PcmAudio(Synth.normalize(fadeOut(samples, s)), rate)
    }

    /**
     * A move's sound, derived from the motion curve itself: the buffer is exactly the move's length
     * ([durationMs] clamped to [MIN_MOVE_MS]..[MAX_MOVE_MS]), the band-pass sweep and the level
     * follow [speed] (0..1) and the swell peaks at [MOTION_PEAK] of the buffer — the caller slides
     * that sample onto the landing or the cut. [variant] (taken modulo [MOTION_VARIANTS]) picks one
     * of the takes and [seed] adds a small pitch (±[JITTER_SEMITONES]) and gain (±[JITTER_DB])
     * jitter, so the third whoosh of an edit never sounds like the first. [palette] colours the
     * take like every other cue. Ids: `whoosh`, `swoosh`.
     */
    fun generateMotion(
        id: String,
        seed: Int,
        durationMs: Int,
        speed: Float,
        variant: Int = 0,
        palette: SfxPalette? = null,
    ): PcmAudio? {
        val s = Synth(rate)
        val sp = speed.coerceIn(0f, 1f)
        val ms = durationMs.coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
        val swoosh = when (id) {
            "whoosh" -> false
            "swoosh" -> true
            else -> return null
        }
        val rnd = kotlin.random.Random(seed)
        val pitch = 2f.pow((rnd.nextFloat() * 2f - 1f) * JITTER_SEMITONES / 12f)
        val jitterDb = (rnd.nextFloat() * 2f - 1f) * JITTER_DB
        val v = palette?.v
        val quick = (sp * if (swoosh) SWISH_BRIGHT else 1f).coerceAtMost(1f)
        val voice = pitch * (v?.pitch ?: 1f) * if (swoosh) SWISH_PITCH else 1f
        val samples = motionWhoosh(ms, quick, variant.mod(MOTION_VARIANTS), voice, v)
        // A faster move is hotter: the level follows the speed (the clip gain comes from the mixer).
        val peakDb = PEAK_DB - (1f - quick) * SPEED_RANGE_DB + jitterDb
        return PcmAudio(Synth.normalize(fadeOut(samples, s), peakDb), rate)
    }

    /** Bubble pop: sine with a fast downward pitch sweep and a tiny click on top. */
    private fun pop(s: Synth): FloatArray {
        val out = s.buffer(140)
        val osc = Synth.Osc(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            val f = 300f + 650f * s.decay(t, 45f)
            out[i] = osc.sine(f) * s.decay(t, 110f) + (if (i < rate / 1000) 0.3f else 0f)
        }
        return out
    }

    private fun click(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(45)
        val noise = Synth.Noise(seed)
        val osc = Synth.Osc(rate)
        val hp = Synth.Svf(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            out[i] = hp.process(noise.white(), 3_000f).high * s.decay(t, 6f) + osc.sine(2_100f) * 0.6f * s.decay(t, 25f)
        }
        return out
    }

    private fun tick(s: Synth): FloatArray {
        val out = s.buffer(30)
        val osc = Synth.Osc(rate)
        for (i in out.indices) out[i] = osc.sine(3_600f) * s.decay(s.seconds(i), 18f)
        return out
    }

    /** Air past the microphone: pink noise through a resonant band-pass that sweeps up then down. */
    private fun whoosh(s: Synth, seed: Int, ms: Int, top: Float, peak: Float): FloatArray {
        val out = s.buffer(ms)
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        for (i in out.indices) {
            val e = s.swell(i, out.size, peak)
            val cutoff = 250f + (top - 250f) * e * e
            out[i] = bp.process(noise.pink(), cutoff, 1.6f).band * e
        }
        return out
    }

    /**
     * Air past the lens, shaped by the move: a resonant band-pass sweeping up to a height set by
     * [speed] and back down over a low 'push' whose weight is the move's mass. The swell peaks at
     * [MOTION_PEAK] so the caller lands that instant on the cut; each [take] voices the sweep its
     * own way (tight and bright, deep, glassy).
     */
    private fun motionWhoosh(ms: Int, speed: Float, take: Int, pitch: Float, v: SfxPalette.Voicing?): FloatArray {
        val s = Synth(rate)
        val out = s.buffer(ms)
        val noise = Synth.Noise(take + 1)
        val bp = Synth.Svf(rate)
        val lp = Synth.Svf(rate)
        val bright = (v?.bright ?: 1f) * TAKE_BRIGHT[take]
        val weight = (v?.weight ?: 1f) * TAKE_WEIGHT[take]
        for (i in out.indices) {
            val e = s.swell(i, out.size, MOTION_PEAK)
            val top = (LOW_CUT + (HIGH_CUT - LOW_CUT) * speed * speed) * bright * pitch
            val cutoff = LOW_CUT + (top - LOW_CUT) * e * e
            val n = noise.pink()
            val air = bp.process(n, cutoff, TAKE_Q[take]).band * e
            val push = lp.process(n, LOW_CUT + PUSH_LIFT * e).low * e * weight
            out[i] = (air + push) * (BASE_LEVEL + SPEED_LEVEL * speed)
        }
        return out
    }

    /** Cinematic hit: pitched-down sub, noise transient and a saturated low boom. */
    private fun impact(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(900)
        val sub = Synth.Osc(rate)
        val noise = Synth.Noise(seed)
        val lp = Synth.Svf(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            val body = sub.sine(38f + 70f * s.decay(t, 60f)) * s.decay(t, 650f)
            val crack = noise.white() * s.decay(t, 22f) * 0.8f
            val boom = lp.process(noise.white(), 180f + 900f * s.decay(t, 80f)).low * s.decay(t, 400f) * 1.6f
            out[i] = Synth.softClip(body * 1.1f + crack + boom, 1.8f)
        }
        return out
    }

    /** Glassy major-seventh sparkle with tremolo, through a short bright reverb. */
    private fun shimmer(s: Synth): FloatArray {
        val out = s.buffer(1_100)
        val notes = listOf(84, 88, 91, 95, 96).map(Synth::midiToHz)
        val oscs = notes.map { Synth.Osc(rate) }
        val verb = Synth.Reverb(rate, size = 0.82f, damp = 0.15f)
        for (i in out.indices) {
            val t = s.seconds(i)
            var v = 0f
            notes.forEachIndexed { k, f ->
                // Notes arrive one after another like a quick harp strum.
                val start = k * 0.035f
                if (t >= start) v += oscs[k].sine(f * (1f + 0.002f * k)) * s.ar(t - start, 4f, 260f) * 0.25f
            }
            v *= 0.8f + 0.2f * sin(2 * PI * 9 * t).toFloat()
            out[i] = v + verb.process(v) * 0.6f
        }
        return out
    }

    /** Tension build: noise and a detuned saw pair sweeping up, ending abruptly. */
    private fun riser(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(1_400)
        val noise = Synth.Noise(seed)
        val a = Synth.Osc(rate)
        val b = Synth.Osc(rate)
        val hp = Synth.Svf(rate)
        val lp = Synth.Svf(rate)
        for (i in out.indices) {
            val x = i.toFloat() / out.size
            val f = 110f * (1f + 7f * x * x)
            val tone = (a.saw(f) + b.saw(f * 1.012f)) * 0.3f
            val air = hp.process(noise.white(), 400f + 6_000f * x).high * 0.5f
            out[i] = lp.process(tone + air, 800f + 9_000f * x).low * x * x
        }
        return out
    }

    /** FM bell: inharmonic partials that ring like a notification. */
    private fun ding(s: Synth, freq: Float, ms: Int): FloatArray {
        val out = s.buffer(ms)
        val car = Synth.Osc(rate)
        val mod = Synth.Osc(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            val index = 2.2f * s.decay(t, 300f)
            out[i] = car.sine(freq + mod.sine(freq * 3.5f) * freq * index) * s.decay(t, ms * 0.9f)
        }
        return out
    }

    /** "Cha-ching": two bell strikes and a burst of jingling coins. */
    private fun cash(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(900)
        val first = ding(s, 2_093f, 500)
        val second = ding(s, 2_637f, 650)
        val offset = rate * 90 / 1000
        for (i in first.indices) out[i] += first[i] * 0.6f
        for (i in second.indices) if (i + offset < out.size) out[i + offset] += second[i] * 0.7f
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val rnd = kotlin.random.Random(seed)
        var grain = 0f
        for (i in out.indices) {
            if (rnd.nextFloat() < 0.0025f) grain = 1f
            grain *= 0.993f
            val t = s.seconds(i)
            out[i] += bp.process(noise.white(), 6_500f, 4f).band * grain * s.decay(t, 600f) * 0.9f
        }
        return out
    }

    /** Digital stutter: bit-crushed square bursts with random pitch jumps. */
    private fun glitch(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(320)
        val osc = Synth.Osc(rate)
        val rnd = kotlin.random.Random(seed)
        val slice = rate * 20 / 1000
        var freq = 400f
        var gate = 1f
        for (i in out.indices) {
            if (i % slice == 0) {
                freq = 150f + rnd.nextFloat() * 1_800f
                gate = if (rnd.nextFloat() < 0.75f) 1f else 0f
            }
            val v = osc.square(freq) * gate
            out[i] = floor(v * 4f) / 4f * 0.7f
        }
        return out
    }

    /**
     * An editor's rewind into a restart: a reverse swell — air through a band-pass opening up and a
     * faint tape whine gliding up as if spooling back — that peaks exactly [REWIND_PEAK_MS] in (the
     * cut) and drops away in a short, darkening tail. One continuous filter path, so the peak has
     * no seam.
     */
    private fun rewind(s: Synth, seed: Int): FloatArray {
        val out = s.buffer(REWIND_PEAK_MS + REWIND_TAIL_MS)
        val peak = rate * REWIND_PEAK_MS / 1000
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val osc = Synth.Osc(rate)
        for (i in out.indices) {
            val rising = i < peak
            val x = if (rising) i.toFloat() / peak else 1f
            val fall = if (rising) 1f else s.decay(s.seconds(i - peak), 110f)
            // Slow then fast in, quick out: the shape of a reversed cymbal.
            val env = if (rising) x * x * x else s.decay(s.seconds(i - peak), 70f)
            val cutoff = (350f + 5_400f * x * x) * fall + 250f * (1f - fall)
            val air = bp.process(noise.pink(), cutoff, 1.3f).band
            val whine = osc.sine((200f + 1_100f * x * x) * (0.35f + 0.65f * fall)) * 0.12f
            out[i] = (air + whine) * env
        }
        return out
    }

    /** 5 ms fade so no effect ends on a click. */
    private fun fadeOut(buffer: FloatArray, s: Synth): FloatArray {
        val n = minOf(buffer.size, s.rate * 5 / 1000)
        for (k in 0 until n) buffer[buffer.size - 1 - k] *= k.toFloat() / n
        return buffer
    }

    companion object {
        /** Where the rewind peaks (the cut it leads into), ms from its start. */
        const val REWIND_PEAK_MS = 360
        private const val REWIND_TAIL_MS = 150

        /** Distinct takes of a move's sound; repeats cycle through them (the research asks for 3–5). */
        const val MOTION_VARIANTS = 4
        /** Where in the buffer the swell peaks: the instant the caller lands on the cut. */
        const val MOTION_PEAK = 0.78f
        /** A move shorter than this is a flick, not a whoosh; past the longest the air runs out. */
        const val MIN_MOVE_MS = 90
        const val MAX_MOVE_MS = 1_200
        /** ±1–2 semitone and ±1–2 dB of variation per take: two moves never sound cloned. */
        const val JITTER_SEMITONES = 1.5f
        const val JITTER_DB = 1.5f
        /** A faster move is this much louder at the top of its speed, in dB. */
        const val SPEED_RANGE_DB = 10f
        private const val PEAK_DB = -2.5f
        private const val LOW_CUT = 250f
        private const val HIGH_CUT = 5_200f
        private const val PUSH_LIFT = 220f
        private const val BASE_LEVEL = 0.35f
        private const val SPEED_LEVEL = 0.65f
        private const val SWISH_BRIGHT = 1.25f
        private const val SWISH_PITCH = 1.15f
        private val TAKE_BRIGHT = floatArrayOf(1f, 1.3f, 0.72f, 1.1f)
        private val TAKE_WEIGHT = floatArrayOf(1f, 0.55f, 1.5f, 0.7f)
        private val TAKE_Q = floatArrayOf(1.5f, 2.4f, 1.1f, 3.2f)
    }
}
