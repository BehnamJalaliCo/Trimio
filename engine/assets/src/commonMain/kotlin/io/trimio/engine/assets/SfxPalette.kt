package io.trimio.engine.assets

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * A style's sound palette: the same cue vocabulary (whoosh, impact, riser, UI tick/pop, swell,
 * glitch, cash, notification) voiced for the style's mood. Every hit is built in layers the way a
 * sound designer stacks samples — a transient (the click that cuts through the voice), a body (the
 * pitched weight) and a tail (filtered air through a room) — and each palette sets the layers'
 * pitch, length, brightness and room. Procedural: nothing to license.
 */
enum class SfxPalette(val id: String, internal val v: Voicing) {
    /** Tight, dry and bright: short-form energy (bold, brutal, sport, street). */
    Punchy("punchy", Voicing(pitch = 1.1f, length = 0.8f, bright = 1.2f, room = 0.15f, weight = 1f)),
    /** Deep sub bodies and long rooms: trailers and luxury reveals. */
    Cinematic("cinematic", Voicing(pitch = 0.75f, length = 1.5f, bright = 0.8f, room = 0.55f, weight = 1.3f)),
    /** Rounded transients, little sub: calm, lifestyle, organic. */
    Soft("soft", Voicing(pitch = 1f, length = 0.9f, bright = 0.6f, room = 0.3f, weight = 0.6f)),
    /** Bit-crushed transients and tonal blips: tech, glitch, cyber. */
    Digital("digital", Voicing(pitch = 1.3f, length = 0.7f, bright = 1.3f, room = 0.1f, weight = 0.8f, crush = true)),
    /** Airy and glassy: premium, elegant, minimal. */
    Luxe("luxe", Voicing(pitch = 1.2f, length = 1.2f, bright = 1f, room = 0.45f, weight = 0.7f)),
    /** Bouncy pitch and pops: playful, kids, clay. */
    Playful("playful", Voicing(pitch = 1.35f, length = 0.85f, bright = 1.1f, room = 0.2f, weight = 0.7f, bounce = true));

    /** How a palette voices the layers. */
    internal data class Voicing(
        val pitch: Float,
        val length: Float,
        val bright: Float,
        val room: Float,
        val weight: Float,
        val crush: Boolean = false,
        val bounce: Boolean = false,
    )

    companion object {
        /** Cue ids every palette voices (`sfx/<cue>@<palette>`). */
        val CUES = listOf("whoosh", "impact", "riser", "tick", "pop", "swell", "glitch", "cash", "notify")

        fun fromId(id: String): SfxPalette? = entries.firstOrNull { it.id == id }
    }
}

/** The layered renderer behind [SfxPalette]. Output is raw (normalised by [ProceduralSfx]). */
internal class LayeredSfx(private val rate: Int) {
    private val s = Synth(rate)

    fun render(cue: String, p: SfxPalette, seed: Int): FloatArray? {
        val v = p.v
        return when (cue) {
            "impact" -> hit(v, seed)
            "whoosh" -> whoosh(v, seed)
            "riser" -> riser(v, seed)
            "tick" -> tick(v, seed)
            "pop" -> pop(v)
            "swell" -> swell(v, seed)
            "glitch" -> glitch(v, seed)
            "cash" -> cash(v, seed)
            "notify" -> notify(v)
            else -> null
        }?.let { if (v.crush) crush(it) else it }
    }

    /** Transient (high-passed noise click) + body (pitch-dropping sine, saturated) + tail (low-passed noise in a room). */
    private fun hit(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer((700 * v.length).toInt())
        val noise = Synth.Noise(seed)
        val hp = Synth.Svf(rate)
        val lp = Synth.Svf(rate)
        val sub = Synth.Osc(rate)
        val verb = Synth.Reverb(rate, size = 0.7f + v.room * 0.25f, damp = 0.4f)
        for (i in out.indices) {
            val t = s.seconds(i)
            val transient = hp.process(noise.white(), 2_500f * v.bright).high * s.decay(t, 9f) * 0.9f
            val body = Synth.softClip(sub.sine((42f + 110f * s.decay(t, 50f)) * v.pitch) * s.ar(t, 0.8f, 380f * v.length) * 1.3f * v.weight, 1.8f)
            val tail = lp.process(noise.white(), 300f + 2_500f * s.decay(t, 120f) * v.bright).low * s.decay(t, 500f * v.length) * 0.5f
            val dry = transient + body + tail
            out[i] = dry + verb.process(dry) * v.room
        }
        return out
    }

    /** Air past the lens: a band-pass sweeping up and down, a low 'push' layer under it, a short room. */
    private fun whoosh(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer((520 * v.length).toInt())
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val lp = Synth.Svf(rate)
        val verb = Synth.Reverb(rate, size = 0.6f, damp = 0.5f)
        for (i in out.indices) {
            val e = s.swell(i, out.size, 0.6f)
            val n = noise.pink()
            val air = bp.process(n, 300f + 4_200f * v.bright * e * e, 1.5f).band * e
            val push = lp.process(n, 160f + 200f * e).low * e * 0.8f * v.weight
            val dry = air + push
            out[i] = dry + verb.process(dry) * v.room * 0.6f
        }
        return out
    }

    private fun riser(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer((1_300 * v.length).toInt())
        val noise = Synth.Noise(seed)
        val hp = Synth.Svf(rate)
        val a = Synth.Osc(rate)
        val b = Synth.Osc(rate)
        for (i in out.indices) {
            val x = i.toFloat() / out.size
            val f = 110f * v.pitch * (1f + 6f * x * x)
            val tone = (a.saw(f) + b.saw(f * 1.012f)) * 0.25f
            val air = hp.process(noise.white(), 400f + 7_000f * x * v.bright).high * 0.5f
            out[i] = (tone + air) * x * x * (0.92f + 0.08f * sin(2 * PI * 12 * x * x).toFloat())
        }
        return out
    }

    /** UI tick: a tonal blip with a noise edge. */
    private fun tick(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer(45)
        val osc = Synth.Osc(rate)
        val noise = Synth.Noise(seed)
        val hp = Synth.Svf(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            out[i] = osc.sine(2_600f * v.pitch) * s.decay(t, 22f) + hp.process(noise.white(), 5_000f).high * s.decay(t, 3f) * 0.4f * v.bright
        }
        return out
    }

    /** UI pop: a bubble with a fast downward pitch and a soft transient. */
    private fun pop(v: SfxPalette.Voicing): FloatArray {
        val out = s.buffer(150)
        val osc = Synth.Osc(rate)
        for (i in out.indices) {
            val t = s.seconds(i)
            val bounce = if (v.bounce) 1f + 0.25f * s.decay(t, 30f) * sin(2 * PI * 40 * t).toFloat() else 1f
            out[i] = osc.sine((320f + 700f * s.decay(t, 40f)) * v.pitch * bounce) * s.ar(t, 1f, 110f)
        }
        return out
    }

    /** Reverse swell into a moment: noise and a chord fading in, cut at the peak. */
    private fun swell(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer((1_000 * v.length).toInt())
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val notes = listOf(60, 67, 72, 76).map { Synth.midiToHz(it) * v.pitch }
        val oscs = notes.map { Synth.Osc(rate) }
        for (i in out.indices) {
            val x = i.toFloat() / out.size
            var chord = 0f
            for (k in notes.indices) chord += oscs[k].saw(notes[k])
            val air = bp.process(noise.pink(), 500f + 5_000f * x * v.bright, 0.9f).band
            val env = x * x * x
            out[i] = (chord / notes.size * 0.35f + air) * env * (if (i > out.size - rate / 100) (out.size - i) / (rate / 100f) else 1f)
        }
        return out
    }

    private fun glitch(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer((300 * v.length).toInt())
        val osc = Synth.Osc(rate)
        val rnd = kotlin.random.Random(seed)
        val slice = rate * 18 / 1000
        var freq = 400f
        var gate = 1f
        for (i in out.indices) {
            if (i % slice == 0) {
                freq = (150f + rnd.nextFloat() * 1_800f) * v.pitch
                gate = if (rnd.nextFloat() < 0.75f) 1f else 0f
            }
            out[i] = kotlin.math.floor(osc.square(freq) * gate * 4f) / 4f * 0.7f
        }
        return out
    }

    /** Cha-ching: two bell strikes (transient + bell body) and coins (a grain tail). */
    private fun cash(v: SfxPalette.Voicing, seed: Int): FloatArray {
        val out = s.buffer(850)
        val strikes = listOf(0 to 2_093f, rate * 85 / 1000 to 2_637f)
        for ((offset, f) in strikes) {
            val car = Synth.Osc(rate)
            val mod = Synth.Osc(rate)
            for (i in 0 until out.size - offset) {
                val t = s.seconds(i)
                out[i + offset] += car.sine(f * v.pitch + mod.sine(f * 3.5f) * f * 2f * s.decay(t, 250f)) * s.decay(t, 520f) * 0.6f
            }
        }
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val rnd = kotlin.random.Random(seed)
        var grain = 0f
        for (i in out.indices) {
            if (rnd.nextFloat() < 0.0025f) grain = 1f
            grain *= 0.993f
            out[i] += bp.process(noise.white(), 6_500f, 4f).band * grain * s.decay(s.seconds(i), 600f) * 0.9f
        }
        return out
    }

    /** Notification: a rising two-note chime (a fifth), bell-like, with a little room. */
    private fun notify(v: SfxPalette.Voicing): FloatArray {
        val out = s.buffer((700 * max(0.8f, v.length)).toInt())
        val notes = listOf(0 to Synth.midiToHz(79) * v.pitch / 1.2f, rate * 110 / 1000 to Synth.midiToHz(86) * v.pitch / 1.2f)
        val verb = Synth.Reverb(rate, size = 0.6f, damp = 0.3f)
        for ((offset, f) in notes) {
            val car = Synth.Osc(rate)
            val mod = Synth.Osc(rate)
            for (i in 0 until out.size - offset) {
                val t = s.seconds(i)
                out[i + offset] += car.sine(f + mod.sine(f * 2f) * f * 0.8f * s.decay(t, 120f)) * s.ar(t, 1.5f, 420f) * 0.5f
            }
        }
        for (i in out.indices) out[i] += verb.process(out[i]) * v.room
        return out
    }

    /** 8-bit-ish quantisation of the waveform: the digital palette's edge. */
    private fun crush(x: FloatArray): FloatArray {
        val peak = x.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-6f) ?: return x
        for (i in x.indices) x[i] = kotlin.math.round(x[i] / peak * CRUSH_STEPS) / CRUSH_STEPS * peak
        return x
    }

    private companion object {
        const val CRUSH_STEPS = 24f
    }
}
