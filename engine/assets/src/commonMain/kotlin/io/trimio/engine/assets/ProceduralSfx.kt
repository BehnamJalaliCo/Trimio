package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.floor
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
    )

    fun generate(id: String, seed: Int = 1): PcmAudio? {
        val s = Synth(rate)
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
            else -> return null
        }
        return PcmAudio(Synth.normalize(fadeOut(samples, s)), rate)
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

    /** 5 ms fade so no effect ends on a click. */
    private fun fadeOut(buffer: FloatArray, s: Synth): FloatArray {
        val n = minOf(buffer.size, s.rate * 5 / 1000)
        for (k in 0 until n) buffer[buffer.size - 1 - k] *= k.toFloat() / n
        return buffer
    }
}
