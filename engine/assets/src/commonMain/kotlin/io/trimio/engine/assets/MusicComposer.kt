package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The procedural composer: a produced-sounding cue rendered from stems — drums (layered kick,
 * clap, metallic hats, crash), bass, pads (detuned supersaw with voice-led chords), keys, plucked
 * arpeggios with a tempo-synced echo — side-chained to the kick, on a sample-accurate [BeatGrid]
 * and arranged in sections ([MusicArrangement]): intro, verse, a build with a snare roll, riser and
 * reverse cymbal, the drop with a crash and a sub hit, a breakdown under the call to action and a
 * final chord on a downbeat. Everything is code and a seed: no recording is licensed or shipped,
 * so the output is free to use commercially and identical on every platform.
 */
class MusicComposer(private val rate: Int = 48_000) {

    /** The full cue for [arrangement], silent before its start, [arrangement].end seconds long. */
    fun compose(arrangement: MusicArrangement, seed: Int = 1): PcmAudio {
        val n = (arrangement.end * rate).roundToInt().coerceAtLeast(1)
        return PcmAudio(Session(arrangement, seed, n, loop = false).render(), rate)
    }

    /**
     * A seamless [bars]-bar loop of the mood's main groove (the `music/<mood>` asset): the tail
     * of the last bar folds onto the first, so the loop point is inaudible.
     */
    fun loop(mood: MusicMood, seed: Int = 1, bars: Int = 8): PcmAudio {
        val grid = BeatGrid(mood.bpm.toDouble())
        val length = bars * grid.bar
        val arrangement = MusicArrangement(mood, grid, 0.0, length, listOf(Section(SectionKind.Drop, 0.0, length)))
        val n = (length * rate).roundToInt()
        return PcmAudio(Session(arrangement, seed, n, loop = true).render(), rate)
    }

    // ---------------------------------------------------------------------------------- session

    private inner class Session(val arr: MusicArrangement, seed: Int, val n: Int, val loop: Boolean) {
        val s = Synth(rate)
        val groove = Groove.of(arr.mood)
        val grid = arr.grid
        val tail = rate * 3
        val drums = FloatArray(n + tail)
        val music = FloatArray(n + tail)
        val verbSend = FloatArray(n + tail)
        val echoSend = FloatArray(n + tail)
        val kicks = mutableListOf<Int>()
        val rnd = Random(seed * 7919 + arr.mood.ordinal)

        fun render(): FloatArray {
            val firstBar = grid.downbeatAtOrBefore(arr.start)
            for (bar in grid.downbeats(firstBar, arr.end)) bar(bar)
            arr.sections.filter { it.kind == SectionKind.Build }.forEach(::build)
            if (!loop) accents()
            return mixdown()
        }

        private fun section(t: Double): SectionKind? = if (t < arr.start - 1e-6 || t >= arr.end) null else arr.sectionAt(t)?.kind

        private fun at(t: Double) = (t * rate).roundToInt()

        /** The chord of the bar at [t]: bars count from the anchor, so the drop always opens on the tonic. */
        private fun chord(t: Double, slower: Boolean = false): ProceduralMusic.Chord {
            val barIndex = floor((t - grid.offset) / grid.bar + 1e-6).toInt()
            val per = groove.chordBars * (if (slower) 2 else 1)
            return arr.mood.chords[floor(barIndex.toDouble() / per).toInt().mod(arr.mood.chords.size)]
        }

        /** Chord tones voiced in one octave window above the mood's root: smooth voice leading for free. */
        private fun voicing(c: ProceduralMusic.Chord, low: Int): List<Float> =
            c.tones().map { tone -> var m = arr.mood.root + tone; while (m < low) m += 12; while (m >= low + 12) m -= 12; m }.sorted().map(Synth::midiToHz)

        private fun bar(t0: Double) {
            val q = grid.period / 4
            for (step in 0 until 16) {
                val swing = if (step % 2 == 1) groove.swing * q else 0.0
                val t = t0 + step * q + swing
                val kind = section(t) ?: continue
                drumStep(kind, step, t)
                bassStep(kind, step, t)
                harmonyStep(kind, step, t, t0)
            }
        }

        // ------------------------------------------------------------------------------ drums

        private fun drumStep(kind: SectionKind, step: Int, t: Double) {
            val full = kind == SectionKind.Drop
            val groovy = full || kind == SectionKind.Verse || kind == SectionKind.Build
            if (!groovy) return
            val v = 0.92f + rnd.nextFloat() * 0.08f
            if (kind == SectionKind.Build) {
                // The build keeps a four-on-the-floor pulse, then leaves the last beat before the drop empty.
                val pulse = step % 4 == 0 && groove.kick.isNotEmpty()
                if (pulse && t < arr.drop.orEnd() - grid.period * 0.99) kick(at(t), v * 0.9f)
            } else {
                if (step in groove.kick) kick(at(t), v)
                if (step in groove.snare && (full || groove.verseSnare)) clap(at(t), v)
            }
            hats(kind, step, t)
            if (groove.toms && full && step in TOM_STEPS) tom(at(t), if (step == 0) 1f else 0.7f)
        }

        private fun hats(kind: SectionKind, step: Int, t: Double) {
            val every = groove.hatStep
            if (every == 0 || step % every != 0) return
            val accent = if (step % 4 == 2) 1f else if (step % 2 == 0) 0.75f else 0.55f
            val level = groove.hatLevel * accent * (if (kind == SectionKind.Drop) 1f else 0.7f) * (0.9f + rnd.nextFloat() * 0.2f)
            val open = groove.openHat && step % 4 == 2 && kind == SectionKind.Drop
            hat(at(t), level, open)
        }

        private fun kick(start: Int, v: Float) {
            kicks += start
            val osc = Synth.Osc(rate)
            val click = Synth.Noise(start)
            val bp = Synth.Svf(rate)
            val low = groove.kickLow
            write(drums, start, rate * 420 / 1000) { i ->
                val t = s.seconds(i)
                val body = osc.sine(low + 115f * s.decay(t, 45f)) * s.ar(t, 0.6f, groove.kickDecay)
                // The beater: band-limited (no sample-to-sample step that would read as a splice click), 10 ms.
                val snap = bp.process(click.white(), 3_000f, 0.8f).band * s.ar(t, 1f, 10f) * 0.45f
                Synth.softClip((body * 1.15f + snap) * v, 1.6f) * 0.95f
            }
        }

        /** Three quick noise bursts (the first on the grid) and a tail through a band-pass, with a pitched body: a layered clap/snare. */
        private fun clap(start: Int, v: Float) {
            val noise = Synth.Noise(start + 11)
            val bp = Synth.Svf(rate)
            val body = Synth.Osc(rate)
            val burst = rate / 120
            write(drums, start, rate * 340 / 1000) { i ->
                val k = i - 2 * burst
                val t = s.seconds(max(0, k))
                // The first burst is the loudest: the clap is heard (and tracked) on the grid.
                val env = if (k < 0) (if ((i / (burst / 2)) % 2 == 0) (if (i < burst) 1f else 0.6f) else 0.12f) else 0.7f * s.decay(t, 210f)
                val x = bp.process(noise.white(), 1_350f, 1.1f).band * env * 1.1f + body.sine(185f) * s.decay(s.seconds(i), 60f) * 0.35f
                val y = x * v * 0.62f
                verbSend.addAt(start + i, y * 0.35f)
                y
            }
        }

        /** 808-style metal: six detuned squares, high-passed, with a noise sheen. */
        private fun hat(start: Int, level: Float, open: Boolean) {
            val oscs = HAT_RATIOS.map { Synth.Osc(rate) }
            val noise = Synth.Noise(start + 3)
            val hp = Synth.Svf(rate)
            val decay = if (open) 240f else 42f
            write(drums, start, rate * (if (open) 320 else 90) / 1000) { i ->
                var metal = 0f
                for (k in oscs.indices) metal += oscs[k].square(HAT_BASE * HAT_RATIOS[k])
                val x = hp.process(metal / oscs.size * 0.6f + noise.white() * 0.5f, 7_500f).high
                x * s.decay(s.seconds(i), decay) * 0.2f * level
            }
        }

        private fun crash(start: Int, level: Float = 1f, reverse: Boolean = false) {
            val len = rate * 1_600 / 1000
            val buf = FloatArray(len)
            val oscs = HAT_RATIOS.map { Synth.Osc(rate) }
            val noise = Synth.Noise(start + 5)
            val hp = Synth.Svf(rate)
            for (i in 0 until len) {
                var metal = 0f
                for (k in oscs.indices) metal += oscs[k].square(HAT_BASE * 0.71f * HAT_RATIOS[k])
                buf[i] = hp.process(metal / oscs.size * 0.4f + noise.white() * 0.7f, 5_000f).high * s.ar(s.seconds(i), 2f, 1_400f) * 0.32f * level
            }
            if (reverse) buf.reverse()
            val from = if (reverse) start - len else start
            for (i in buf.indices) { drums.addAt(from + i, buf[i]); verbSend.addAt(from + i, buf[i] * 0.25f) }
        }

        private fun tom(start: Int, v: Float) {
            val osc = Synth.Osc(rate)
            val noise = Synth.Noise(start)
            val bp = Synth.Svf(rate)
            write(drums, start, rate * 800 / 1000) { i ->
                val t = s.seconds(i)
                val skin = bp.process(noise.white(), 1_500f, 0.8f).band * s.ar(t, 1f, 20f) * 0.3f
                val x = (osc.sine(58f + 45f * s.decay(t, 110f)) * s.decay(t, 650f) + skin) * 0.75f * v
                verbSend.addAt(start + i, x * 0.5f)
                x
            }
        }

        // ------------------------------------------------------------------------------- bass

        private fun bassStep(kind: SectionKind, step: Int, t: Double) {
            val q = grid.period / 4
            val root = arr.mood.root - 24 + chord(t).root
            when (kind) {
                SectionKind.Drop, SectionKind.Verse -> groove.bass.firstOrNull { it.first == step }?.let { (_, len) -> bass(at(t), at(len * q * 0.95), Synth.midiToHz(root), 1f) }
                SectionKind.Build -> if (step % 4 == 0 && t < arr.drop.orEnd() - grid.period * 2) bass(at(t), at(grid.period * 0.9), Synth.midiToHz(root), 0.8f)
                SectionKind.Breakdown -> if (step == 0) bass(at(t), at(grid.bar * 0.98), Synth.midiToHz(root), 0.35f, sub = true)
                else -> Unit
            }
        }

        private fun Double?.orEnd() = this ?: arr.end

        private fun bass(start: Int, len: Int, freq: Float, v: Float, sub: Boolean = false) {
            val a = Synth.Osc(rate)
            val b = Synth.Osc(rate)
            val lp = Synth.Svf(rate)
            val bright = groove.bassBright && !sub
            val release = rate / 150
            write(music, start, len) { i ->
                val t = s.seconds(i)
                val gate = min(1f, i / (rate * 0.003f)) * (if (i > len - release) (len - i).toFloat() / release else 1f)
                val tone = if (bright) lp.process(a.saw(freq), 220f + 1_100f * s.decay(t, 120f), 1.2f).low else 0f
                (tone * 0.55f + b.sine(freq) * 0.75f) * gate * 0.55f * v
            }
        }

        // ---------------------------------------------------------------------------- harmony

        private fun harmonyStep(kind: SectionKind, step: Int, t: Double, t0: Double) {
            if (kind == SectionKind.Outro) return
            val slow = kind == SectionKind.Breakdown || kind == SectionKind.Intro
            val c = chord(t0, slower = slow)
            val per = groove.chordBars * (if (slow) 2 else 1)
            val chordStart = step == 0 && ((t0 - grid.offset) / grid.bar).roundToInt().mod(per) == 0
            if (Part.Pad in groove.parts && chordStart) {
                val level = when (kind) { SectionKind.Drop -> 0.8f; SectionKind.Breakdown -> 0.75f; SectionKind.Intro -> 0.85f; else -> 0.65f }
                val until = arr.sectionAt(t)?.to ?: arr.end
                pad(at(t), at(min(per * grid.bar, until - t)), voicing(c, arr.mood.root + 3), level, bright = kind == SectionKind.Drop, slow = slow)
            }
            if (Part.Keys in groove.parts && step in KEY_STEPS) keysStep(kind, step, t, c)
            if (Part.Arp in groove.parts && kind in ARP_SECTIONS) arpStep(kind, step, t, t0, c)
            if (Part.Stabs in groove.parts && kind == SectionKind.Drop && step in STAB_STEPS) stab(at(t), at(grid.period * 0.35), voicing(c, arr.mood.root + 7))
        }

        private fun keysStep(kind: SectionKind, step: Int, t: Double, c: ProceduralMusic.Chord) {
            if (kind == SectionKind.Build) return
            val quiet = kind == SectionKind.Breakdown || kind == SectionKind.Intro
            // The breakdown leaves air: one chord a bar.
            if (kind == SectionKind.Breakdown && step != 0) return
            keys(at(t), at(grid.period * 1.4), voicing(c, arr.mood.root + 5), if (quiet) 0.7f else 1f)
        }

        private fun arpStep(kind: SectionKind, step: Int, t: Double, t0: Double, c: ProceduralMusic.Chord) {
            val tones = voicing(c, arr.mood.root + 12)
            val bar = ((t0 - grid.offset) / grid.bar).roundToInt()
            val note = tones[ARP[(step + bar).mod(ARP.size)] % tones.size] * (if (step % 8 >= 6) 2f else 1f)
            val level = when (kind) { SectionKind.Drop -> 1f; SectionKind.Verse -> 0.7f; else -> 0.45f }
            pluck(at(t), at(grid.period * 0.22), note, level * (if (step % 4 == 0) 1f else 0.72f), dark = kind == SectionKind.Intro)
        }

        /** Five detuned saws per note, low-passed with a slow swell: the supersaw pad. */
        /** [slow]: a long swell (intro, breakdown); otherwise the chord speaks on the downbeat and lets go before the next. */
        private fun pad(start: Int, len: Int, freqs: List<Float>, level: Float, bright: Boolean, slow: Boolean = false) {
            if (len <= 0) return
            val voices = freqs.flatMap { f -> DETUNE.map { d -> f * d } }
            // Free-running phases: the voices never start in step (no 'zap' on the attack).
            val oscs = voices.map { Synth.Osc(rate, phase = rnd.nextDouble()) }
            val lp = Synth.Svf(rate)
            val attack = min(len / 3, if (slow) rate * 3 / 10 else rate / 25)
            val release = min(len / 3, if (slow) rate * 4 / 10 else rate / 8)
            write(music, start, len + release) { i ->
                val env = min(1f, i.toFloat() / attack) * (if (i > len) max(0f, 1f - (i - len).toFloat() / release) else 1f)
                var v = 0f
                for (k in oscs.indices) v += oscs[k].saw(voices[k])
                val cut = (if (bright) 2_400f else 1_300f) + 500f * sin(2 * PI * i / (rate * 4.0)).toFloat()
                val y = lp.process(v / oscs.size, cut).low * env * 0.42f * level
                verbSend.addAt(start + i, y * 0.5f)
                y
            }
        }

        /** Electric piano: a sine carrier with a decaying 1:1 modulator (the tine's bark), and tremolo. */
        private fun keys(start: Int, len: Int, freqs: List<Float>, level: Float) {
            val car = freqs.map { Synth.Osc(rate) }
            val mod = freqs.map { Synth.Osc(rate) }
            write(music, start, len) { i ->
                val t = s.seconds(i)
                val index = 1.6f * s.decay(t, 380f)
                var v = 0f
                for (k in freqs.indices) v += car[k].sine(freqs[k] + mod[k].sine(freqs[k]) * freqs[k] * index)
                val y = v / freqs.size * s.ar(t, 3f, 1_100f) * (0.88f + 0.12f * sin(2 * PI * 4.5 * t).toFloat()) * 0.42f * level
                verbSend.addAt(start + i, y * 0.4f)
                y
            }
        }

        private fun pluck(start: Int, len: Int, freq: Float, level: Float, dark: Boolean) {
            val a = Synth.Osc(rate)
            val b = Synth.Osc(rate)
            val lp = Synth.Svf(rate)
            val top = if (dark) 1_200f else 4_200f
            write(music, start, len) { i ->
                val t = s.seconds(i)
                val y = lp.process(a.saw(freq) * 0.6f + b.square(freq * 1.003f) * 0.4f, 500f + top * s.decay(t, 90f)).low * s.decay(t, 200f) * 0.2f * level
                echoSend.addAt(start + i, y * 0.6f)
                verbSend.addAt(start + i, y * 0.2f)
                y
            }
        }

        private fun stab(start: Int, len: Int, freqs: List<Float>) {
            val voices = freqs.flatMap { f -> listOf(f * 0.997f, f, f * 1.003f) }
            val oscs = voices.map { Synth.Osc(rate) }
            val lp = Synth.Svf(rate)
            write(music, start, len) { i ->
                val t = s.seconds(i)
                var v = 0f
                for (k in oscs.indices) v += oscs[k].saw(voices[k])
                val y = lp.process(v / oscs.size, 900f + 3_500f * s.decay(t, 60f)).low * s.decay(t, 160f) * 0.3f
                verbSend.addAt(start + i, y * 0.3f)
                y
            }
        }

        // ------------------------------------------------------------------------ transitions

        /** The build: a snare roll that doubles its rate toward the drop, a riser, a reverse cymbal, a beat of air. */
        private fun build(sec: Section) {
            val beats = grid.beats(sec.from, sec.to)
            for ((k, b) in beats.withIndex()) {
                val left = beats.size - k
                val per = when { left <= 1 -> 0; left <= 2 -> 4; left <= beats.size / 2 -> 2; else -> 1 }
                for (j in 0 until per) {
                    val t = b + j * grid.period / per
                    clap(at(t), (0.35f + 0.65f * (t - sec.from).toFloat() / sec.length.toFloat()) * 0.8f)
                }
            }
            riser(at(sec.from), at(sec.length))
            crash(at(sec.to), 0.8f, reverse = true)
        }

        private fun riser(start: Int, len: Int) {
            val noise = Synth.Noise(start)
            val hp = Synth.Svf(rate)
            val a = Synth.Osc(rate)
            write(music, start, len) { i ->
                val x = i.toFloat() / len
                val air = hp.process(noise.white(), 300f + 7_000f * x * x).high * 0.5f
                val tone = a.saw(arr.mood.root.let(Synth::midiToHz) * (1f + 3f * x * x)) * 0.12f
                val y = (air + tone) * x * x * 0.45f
                verbSend.addAt(start + i, y * 0.3f)
                y
            }
        }

        /** The one-shot moments: crash and sub hit on the drop and every eight bars of it, a downlifter into the breakdown, the final chord. */
        private fun accents() {
            arr.sections.filter { it.kind == SectionKind.Drop }.forEach { d ->
                grid.downbeats(d.from, d.to).filterIndexed { k, _ -> k % 8 == 0 }.forEachIndexed { k, t ->
                    crash(at(t), if (k == 0) 1f else 0.6f)
                    if (k == 0 && d.from > arr.start + 1e-3) subHit(at(t), 1f)
                }
            }
            arr.sections.filter { it.kind == SectionKind.Breakdown }.forEach { downlifter(at(it.from)) }
            val outro = arr.sections.lastOrNull { it.kind == SectionKind.Outro } ?: return
            val end = at(arr.end)
            val t = at(outro.from)
            if (end - t < rate / 10) return
            crash(t, 0.9f)
            if (groove.kick.isNotEmpty()) kick(t, 1f) else subHit(t, 0.8f)
            val tonic = arr.mood.chords.first()
            pad(t, (end - t) / 2, voicing(tonic, arr.mood.root + 3), 1.1f, bright = true)
            if (Part.Keys in groove.parts) keys(t, end - t, voicing(tonic, arr.mood.root + 5), 1f)
            bass(t, (end - t) * 2 / 3, Synth.midiToHz(arr.mood.root - 24 + tonic.root), 0.9f, sub = true)
        }

        private fun subHit(start: Int, v: Float) {
            val osc = Synth.Osc(rate)
            write(drums, start, rate * 1_400 / 1000) { i ->
                val t = s.seconds(i)
                Synth.softClip(osc.sine(32f + 50f * s.decay(t, 140f)) * s.ar(t, 2f, 1_200f) * 1.2f, 1.4f) * 0.7f * v
            }
        }

        private fun downlifter(start: Int) {
            val noise = Synth.Noise(start)
            val lp = Synth.Svf(rate)
            val len = at(grid.bar)
            write(music, start, len) { i ->
                val x = i.toFloat() / len
                val y = lp.process(noise.white(), 6_000f * (1f - x) * (1f - x) + 200f).low * (1f - x) * 0.18f
                verbSend.addAt(start + i, y * 0.5f)
                y
            }
        }

        // ---------------------------------------------------------------------------- mixdown

        private fun mixdown(): FloatArray {
            pump()
            val verb = Synth.Reverb(rate, size = groove.reverbSize, damp = 0.35f)
            val delay = at(grid.period * 0.75)
            for (i in delay until echoSend.size) echoSend[i] += echoSend[i - delay] * 0.38f
            val echoLp = Synth.Svf(rate)
            val busLp = Synth.Svf(rate)
            val out = FloatArray(n + tail)
            val noise = Synth.Noise(17)
            for (i in out.indices) {
                val wet = verb.process(verbSend[i]) * groove.reverbMix + echoLp.process(echoSend[i], 3_000f).low * 0.3f
                val vinyl = if (groove.vinyl && i >= at(arr.start)) noise.pink() * 0.01f else 0f
                // A gentle bus low-pass tames the top of the full mix; drums stay on top.
                out[i] = drums[i] + busLp.process(music[i] + wet, 14_000f).low + vinyl
            }
            for (i in out.indices) out[i] = Synth.softClip(out[i] * 0.9f, 1.2f)
            val result = out.copyOf(n)
            if (loop) for (i in 0 until tail) result[i % n] += out[n + i] else fadeTail(result)
            if (!loop) silenceBefore(result, at(arr.start))
            return Synth.normalize(result, -3f)
        }

        /** Side-chain: the music bus ducks under every kick and breathes back within the beat. */
        private fun pump() {
            if (groove.pump <= 0f || kicks.isEmpty()) return
            val release = (grid.period * 0.45).toFloat()
            val gain = FloatArray(music.size) { 1f }
            for (k in kicks) {
                val len = min(music.size - k, at(grid.period))
                for (i in 0 until len) {
                    val t = i.toFloat() / rate
                    val duck = 1f - groove.pump * exp(-t / (release * 0.35f)) * min(1f, i / (rate * 0.002f) + 0.2f)
                    gain[k + i] = min(gain[k + i], duck)
                }
            }
            for (i in music.indices) { music[i] *= gain[i]; echoSend[i] *= gain[i] }
        }

        /** A 20 ms fade into the last frame: no cue ends on a click. */
        private fun fadeTail(x: FloatArray) {
            val f = min(x.size, rate / 50)
            for (k in 0 until f) x[x.size - 1 - k] *= k.toFloat() / f
        }

        private fun silenceBefore(x: FloatArray, start: Int) {
            for (i in 0 until min(start, x.size)) x[i] = 0f
            val f = min(rate / 200, x.size - start)
            for (k in 0 until f) if (start + k in x.indices) x[start + k] *= k.toFloat() / f
        }

        /** Adds a generated voice from [start]; the first 0.7 ms ramps in, so no hit starts on a step. */
        private fun write(dst: FloatArray, start: Int, len: Int, gen: (Int) -> Float) {
            val ramp = rate * 7 / 10_000
            for (i in 0 until len) {
                val j = start + i
                if (j >= dst.size) return
                val v = gen(i) * (if (i < ramp) i.toFloat() / ramp else 1f)
                if (j >= 0) dst[j] += v
            }
        }

        private fun FloatArray.addAt(i: Int, v: Float) { if (i in indices) this[i] += v }
    }

    // --------------------------------------------------------------------------------- grooves

    private enum class Part { Pad, Keys, Arp, Stabs }

    /** Per-mood drum programming, bass line and instrumentation (16th steps within a 4/4 bar). */
    private data class Groove(
        val kick: Set<Int>,
        val snare: Set<Int>,
        val hatStep: Int,
        val hatLevel: Float = 1f,
        val openHat: Boolean = false,
        val swing: Double = 0.0,
        val bass: List<Pair<Int, Int>>,
        val bassBright: Boolean = true,
        val parts: Set<Part>,
        val chordBars: Int = 1,
        val pump: Float = 0.4f,
        val toms: Boolean = false,
        val vinyl: Boolean = false,
        val verseSnare: Boolean = true,
        val kickLow: Float = 46f,
        val kickDecay: Float = 330f,
        val reverbSize: Float = 0.8f,
        val reverbMix: Float = 0.32f,
    ) {
        companion object {
            private val FOUR = setOf(0, 4, 8, 12)
            private val BACKBEAT = setOf(4, 12)
            private val OFFBEAT_8THS = listOf(2, 6, 10, 14).map { it to 2 }
            private val ROLLING_8THS = (0 until 8).map { it * 2 to 2 }

            fun of(mood: MusicMood) = when (mood) {
                MusicMood.Uplifting -> Groove(FOUR, BACKBEAT, 2, 0.8f, bass = ROLLING_8THS, parts = setOf(Part.Pad, Part.Arp), pump = 0.45f)
                MusicMood.Energetic -> Groove(
                    FOUR, BACKBEAT, 1, 0.75f, openHat = true, bass = OFFBEAT_8THS, parts = setOf(Part.Pad, Part.Arp, Part.Stabs),
                    pump = 0.6f, kickLow = 48f, kickDecay = 300f, reverbMix = 0.26f,
                )
                MusicMood.Chill -> Groove(
                    setOf(0, 7, 10), BACKBEAT, 2, 0.55f, swing = 0.16, bass = listOf(0 to 6, 7 to 3, 10 to 6), bassBright = false,
                    parts = setOf(Part.Keys, Part.Pad), chordBars = 2, pump = 0.12f, vinyl = true, kickLow = 50f, reverbMix = 0.3f,
                )
                // Hybrid trailer drums: a deep hit on one and three, a snare on four, toms between.
                MusicMood.Cinematic -> Groove(
                    setOf(0, 8), setOf(12), 0, bass = (0 until 8).map { it * 2 to 2 }, bassBright = false, parts = setOf(Part.Pad, Part.Arp),
                    chordBars = 2, pump = 0.15f, toms = true, verseSnare = false, kickLow = 40f, kickDecay = 600f, reverbSize = 0.88f, reverbMix = 0.5f,
                )
                MusicMood.Corporate -> Groove(
                    setOf(0, 8, 10), BACKBEAT, 2, 0.65f, bass = listOf(0 to 3, 6 to 2, 8 to 3, 14 to 2), parts = setOf(Part.Keys, Part.Arp, Part.Pad),
                    pump = 0.25f, verseSnare = false,
                )
                MusicMood.Tense -> Groove(
                    setOf(0, 8), setOf(12), 1, 0.4f, bass = (0 until 8).map { it * 2 to 1 }, bassBright = true, parts = setOf(Part.Pad),
                    chordBars = 2, pump = 0.3f, toms = true, reverbMix = 0.4f,
                )
            }
        }
    }

    private companion object {
        const val HAT_BASE = 40f
        val HAT_RATIOS = floatArrayOf(2f, 3f, 4.16f, 5.43f, 6.79f, 8.21f)
        val DETUNE = floatArrayOf(0.991f, 0.996f, 1f, 1.004f, 1.009f)
        val ARP = intArrayOf(0, 1, 2, 1, 2, 3, 2, 1)
        val KEY_STEPS = setOf(0, 6, 10)
        val STAB_STEPS = setOf(2, 6, 10, 14)
        val TOM_STEPS = setOf(0, 4, 8, 10, 12, 14)
        val ARP_SECTIONS = setOf(SectionKind.Drop, SectionKind.Verse, SectionKind.Intro)
    }
}
