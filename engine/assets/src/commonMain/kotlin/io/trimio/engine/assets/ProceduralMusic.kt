package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Moods the Director can ask for (`music/<id>`), each a complete arrangement. */
enum class MusicMood(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val bpm: Int,
    /** MIDI note of the key's root (bass octave is derived from it). */
    val root: Int,
    val chords: List<ProceduralMusic.Chord>,
) {
    Uplifting("uplifting", "امیدبخش", "Uplifting", 118, 60, listOf(c(0), c(7), c(9, minor = true), c(5))),
    Energetic("energetic", "پرانرژی", "Energetic", 126, 57, listOf(c(0, minor = true), c(8), c(3), c(10))),
    Chill("chill", "آرام", "Chill", 84, 62, listOf(c(0, seventh = true), c(9, minor = true, seventh = true), c(2, minor = true, seventh = true), c(7, seventh = true))),
    Cinematic("cinematic", "سینمایی", "Cinematic", 92, 50, listOf(c(0, minor = true), c(8), c(3), c(10))),
    Corporate("corporate", "رسمی", "Corporate", 108, 64, listOf(c(0), c(9, minor = true), c(5), c(7))),
    Tense("tense", "پرتنش", "Tense", 100, 52, listOf(c(0, minor = true), c(1), c(0, minor = true), c(10)));

    companion object {
        fun fromId(id: String): MusicMood? = entries.firstOrNull { it.id == id }
    }
}

private fun c(root: Int, minor: Boolean = false, seventh: Boolean = false) = ProceduralMusic.Chord(root, minor, seventh)

/** A loopable music bed and the beat grid the Director syncs cuts and elements to. */
class MusicTrack(val mood: MusicMood, val audio: PcmAudio) {
    val bpm: Int get() = mood.bpm
    val beatMs: Double get() = 60_000.0 / bpm
    val loopMs: Long get() = audio.durationMs

    /** Beat times in [0, durationMs) as the bed loops from time zero. */
    fun beats(durationMs: Long): List<Long> {
        val n = (durationMs / beatMs).toInt()
        return (0..n).map { (it * beatMs).roundToInt().toLong() }.filter { it < durationMs }
    }

    /** Downbeats (first beat of each bar): strongest points for cuts and big entrances. */
    fun downbeats(durationMs: Long): List<Long> = beats(durationMs).filterIndexed { i, _ -> i % 4 == 0 }
}

/**
 * Generative, royalty-free music beds. Eight bars (four chords, two bars each) rendered from
 * synthesised drums, bass, pads and plucks, mixed with a send reverb and folded so the loop is
 * seamless. Deterministic per mood and seed; nothing is downloaded and nothing needs a licence.
 */
class ProceduralMusic(private val rate: Int = 48_000) {

    data class Chord(val root: Int, val minor: Boolean = false, val seventh: Boolean = false) {
        fun tones(): List<Int> = buildList {
            add(0); add(if (minor) 3 else 4); add(7)
            if (seventh) add(if (minor) 10 else 11)
        }.map { it + root }
    }

    fun render(mood: MusicMood, seed: Int = 1): MusicTrack {
        val s = Synth(rate)
        val beat = 60.0 / mood.bpm
        val bars = 8
        val length = (bars * 4 * beat * rate).roundToInt()
        val tail = rate * 2
        val dry = FloatArray(length + tail)
        val send = FloatArray(length + tail)
        val rnd = Random(seed * 31 + mood.ordinal)

        fun at(beats: Double) = (beats * beat * rate).roundToInt()
        val style = Arrangement.of(mood)

        for (bar in 0 until bars) {
            val chord = mood.chords[(bar / 2) % mood.chords.size]
            val b0 = bar * 4.0
            // Drums
            for (k in style.kicks) kick(dry, at(b0 + k), s)
            for (k in style.snares) snare(dry, send, at(b0 + k), s, rnd.nextInt())
            val hatStep = style.hatStep
            if (hatStep > 0) {
                var h = 0.0
                while (h < 4.0) {
                    val accent = if ((h * 2).roundToInt() % 2 == 1) 1f else 0.6f
                    hat(dry, at(b0 + h), s, rnd.nextInt(), accent * style.hatLevel, open = style.openHatOffbeat && (h % 1.0) == 0.5)
                    h += hatStep
                }
            }
            if (style.toms && bar % 2 == 0) { tom(dry, send, at(b0), s); tom(dry, send, at(b0 + 2.5), s) }

            // Bass
            val bassNote = mood.root - 24 + chord.root
            for ((start, len) in style.bassPattern) bass(dry, at(b0 + start), at(len), Synth.midiToHz(bassNote), s, style.bassBright)

            // Harmony
            if (style.pad && bar % 2 == 0) pad(dry, send, at(b0), at(8.0), chord.tones().map { Synth.midiToHz(mood.root + it) }, s)
            if (style.keys) for (k in listOf(0.0, 1.5)) keys(dry, send, at(b0 + k), at(1.4), chord.tones().map { Synth.midiToHz(mood.root + it) }, s)
            if (style.arp) {
                val tones = chord.tones().map { mood.root + 12 + it }
                for (step in 0 until 16) {
                    val note = tones[(step + bar) % tones.size] + if (step % 8 >= 6) 12 else 0
                    pluck(dry, send, at(b0 + step * 0.25), at(0.22), Synth.midiToHz(note), s, if (step % 4 == 0) 1f else 0.7f)
                }
            }
        }
        if (style.vinyl) vinyl(dry, rnd.nextInt())

        val verb = Synth.Reverb(rate, size = style.reverbSize, damp = 0.35f)
        for (i in dry.indices) dry[i] += verb.process(send[i]) * style.reverbMix
        // Fold the tail onto the start: the loop point is inaudible.
        val out = dry.copyOf(length)
        for (i in 0 until tail) out[i % length] += dry[length + i]
        return MusicTrack(mood, PcmAudio(Synth.normalize(out, -3f), rate))
    }

    // --- Instruments ------------------------------------------------------------------------------

    private fun write(dst: FloatArray, start: Int, n: Int, gen: (Int) -> Float) {
        for (i in 0 until n) {
            val j = start + i
            if (j >= dst.size) return
            dst[j] += gen(i)
        }
    }

    private fun kick(dst: FloatArray, start: Int, s: Synth) {
        val osc = Synth.Osc(rate)
        write(dst, start, rate * 300 / 1000) { i ->
            val t = s.seconds(i)
            Synth.softClip(osc.sine(45f + 85f * s.decay(t, 40f)) * s.decay(t, 280f) * 1.2f, 1.5f) * 0.9f
        }
    }

    private fun snare(dst: FloatArray, send: FloatArray, start: Int, s: Synth, seed: Int) {
        val noise = Synth.Noise(seed)
        val bp = Synth.Svf(rate)
        val osc = Synth.Osc(rate)
        write(dst, start, rate * 220 / 1000) { i ->
            val t = s.seconds(i)
            val v = bp.process(noise.white(), 1_800f, 0.9f).band * s.decay(t, 160f) * 0.9f + osc.sine(190f) * s.decay(t, 60f) * 0.35f
            if (start + i < send.size) send[start + i] += v * 0.4f
            v * 0.55f
        }
    }

    private fun hat(dst: FloatArray, start: Int, s: Synth, seed: Int, level: Float, open: Boolean) {
        val noise = Synth.Noise(seed)
        val hp = Synth.Svf(rate)
        val decay = if (open) 160f else 35f
        write(dst, start, rate * (if (open) 220 else 60) / 1000) { i ->
            hp.process(noise.white(), 8_000f).high * s.decay(s.seconds(i), decay) * 0.22f * level
        }
    }

    private fun tom(dst: FloatArray, send: FloatArray, start: Int, s: Synth) {
        val osc = Synth.Osc(rate)
        write(dst, start, rate * 700 / 1000) { i ->
            val t = s.seconds(i)
            val v = osc.sine(62f + 40f * s.decay(t, 120f)) * s.decay(t, 600f) * 0.8f
            if (start + i < send.size) send[start + i] += v * 0.5f
            v
        }
    }

    private fun bass(dst: FloatArray, start: Int, n: Int, freq: Float, s: Synth, bright: Boolean) {
        val a = Synth.Osc(rate)
        val sub = Synth.Osc(rate)
        val lp = Synth.Svf(rate)
        write(dst, start, n) { i ->
            val t = s.seconds(i)
            val env = s.ar(t, 4f, n * 1000f / rate * 1.4f) * (if (i > n - rate / 200) (n - i).toFloat() / (rate / 200) else 1f)
            val tone = if (bright) lp.process(a.saw(freq), 300f + 900f * s.decay(t, 90f)).low else 0f
            (tone * 0.5f + sub.sine(freq) * 0.6f) * env * 0.5f
        }
    }

    private fun pad(dst: FloatArray, send: FloatArray, start: Int, n: Int, freqs: List<Float>, s: Synth) {
        val oscs = freqs.flatMap { listOf(it * 0.996f, it * 1.004f) }.map { it to Synth.Osc(rate) }
        val lp = Synth.Svf(rate)
        write(dst, start, n) { i ->
            val x = i.toFloat() / n
            val env = minOf(1f, x * 6f) * minOf(1f, (1f - x) * 5f)
            var v = 0f
            for ((f, o) in oscs) v += o.saw(f)
            v = lp.process(v / oscs.size, 1_100f + 400f * sin(2 * PI * x).toFloat()).low * env * 0.32f
            if (start + i < send.size) send[start + i] += v * 0.6f
            v
        }
    }

    /** Electric-piano-like chord: sine plus a soft second harmonic with tremolo. */
    private fun keys(dst: FloatArray, send: FloatArray, start: Int, n: Int, freqs: List<Float>, s: Synth) {
        val oscs = freqs.map { Triple(it, Synth.Osc(rate), Synth.Osc(rate)) }
        write(dst, start, n) { i ->
            val t = s.seconds(i)
            var v = 0f
            for ((f, a, b) in oscs) v += a.sine(f) + 0.25f * b.sine(f * 2f)
            v = v / oscs.size * s.ar(t, 6f, 900f) * (0.85f + 0.15f * sin(2 * PI * 5 * t).toFloat()) * 0.45f
            if (start + i < send.size) send[start + i] += v * 0.5f
            v
        }
    }

    private fun pluck(dst: FloatArray, send: FloatArray, start: Int, n: Int, freq: Float, s: Synth, level: Float) {
        val osc = Synth.Osc(rate)
        val lp = Synth.Svf(rate)
        write(dst, start, n) { i ->
            val t = s.seconds(i)
            val v = lp.process(osc.square(freq), 600f + 3_000f * s.decay(t, 70f)).low * s.decay(t, 180f) * 0.18f * level
            if (start + i < send.size) send[start + i] += v * 0.7f
            v
        }
    }

    /** Lo-fi record crackle. */
    private fun vinyl(dst: FloatArray, seed: Int) {
        val rnd = Random(seed)
        val noise = Synth.Noise(seed)
        for (i in dst.indices) {
            dst[i] += noise.pink() * 0.012f
            if (rnd.nextFloat() < 0.0004f) dst[i] += (rnd.nextFloat() - 0.5f) * 0.25f
        }
    }

    /** Per-mood instrumentation and patterns (beats within a 4/4 bar). */
    private data class Arrangement(
        val kicks: List<Double>,
        val snares: List<Double>,
        val hatStep: Double,
        val hatLevel: Float = 1f,
        val openHatOffbeat: Boolean = false,
        val bassPattern: List<Pair<Double, Double>>,
        val bassBright: Boolean = true,
        val pad: Boolean = false,
        val keys: Boolean = false,
        val arp: Boolean = false,
        val toms: Boolean = false,
        val vinyl: Boolean = false,
        val reverbSize: Float = 0.78f,
        val reverbMix: Float = 0.35f,
    ) {
        companion object {
            private val eighths = (0 until 8).map { it * 0.5 to 0.45 }

            fun of(mood: MusicMood) = when (mood) {
                MusicMood.Uplifting -> Arrangement(listOf(0.0, 1.0, 2.0, 3.0), listOf(1.0, 3.0), 0.5, bassPattern = eighths, pad = true, arp = true)
                MusicMood.Energetic -> Arrangement(
                    listOf(0.0, 1.0, 2.0, 3.0), listOf(1.0, 3.0), 0.25, hatLevel = 0.8f, openHatOffbeat = true,
                    bassPattern = (0 until 4).map { it + 0.5 to 0.45 }, arp = true, reverbMix = 0.25f,
                )
                MusicMood.Chill -> Arrangement(
                    listOf(0.0, 2.5), listOf(1.0, 3.0), 0.5, hatLevel = 0.6f,
                    bassPattern = listOf(0.0 to 1.5, 2.5 to 1.4), bassBright = false, keys = true, vinyl = true, reverbMix = 0.3f,
                )
                MusicMood.Cinematic -> Arrangement(
                    emptyList(), emptyList(), 0.0, bassPattern = listOf(0.0 to 4.0), bassBright = false, pad = true, toms = true,
                    reverbSize = 0.88f, reverbMix = 0.55f,
                )
                MusicMood.Corporate -> Arrangement(listOf(0.0, 2.0), listOf(1.0, 3.0), 0.5, hatLevel = 0.7f, bassPattern = listOf(0.0 to 0.9, 1.5 to 0.4, 2.0 to 0.9, 3.5 to 0.4), keys = true, arp = true)
                MusicMood.Tense -> Arrangement(
                    listOf(0.0, 2.0), listOf(3.0), 0.25, hatLevel = 0.5f,
                    bassPattern = (0 until 16).map { it * 0.25 to 0.2 }, pad = true, toms = true, reverbMix = 0.4f,
                )
            }
        }
    }
}
