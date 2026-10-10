package io.trimio.engine.autopilot.workshop

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A script as the workshop writes it: plain words, with a few marks for what a real recording
 * has and a clean transcript does not.
 *
 * - `{clean|heard}`: the recogniser heard this differently (`{سیصد|سی سد}` a split, `{در نظر|درنظر}` a merge,
 *   `{Claude|Cloud}` a misheard name); `{um|}` is dropped;
 * - `~word`: a filler («اِم», "um"), voiced but usually not transcribed;
 * - `*word`: leaned on (louder, higher, a little longer).
 */
internal object ScriptReader {
    class Token(val clean: List<String>, val heard: List<String>?, val filler: Boolean, val stress: Boolean)

    fun tokens(script: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                c.isWhitespace() -> i++
                c == '{' -> {
                    val close = script.indexOf('}', i)
                    require(close > i) { "unclosed { at $i in ${script.take(40)}" }
                    // Punctuation right after the brace belongs to the last word of both readings.
                    var j = close + 1
                    while (j < script.length && !script[j].isWhitespace() && script[j] !in "{~*") j++
                    val tail = script.substring(close + 1, j)
                    val (clean, heard) = script.substring(i + 1, close).split('|').let { parts -> parts[0] to parts.getOrElse(1) { parts[0] } }
                    fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.let { w -> if (w.isEmpty()) w else w.dropLast(1) + (w.last() + tail) }
                    out += Token(words(clean), words(heard), filler = false, stress = false)
                    i = j
                }
                else -> {
                    var j = i
                    while (j < script.length && !script[j].isWhitespace()) j++
                    val raw = script.substring(i, j)
                    val filler = raw.startsWith('~')
                    val stress = raw.startsWith('*')
                    out += Token(listOf(raw.trimStart('~', '*')), null, filler, stress)
                    i = j
                }
            }
        }
        return out
    }
}

/** A speaker's delivery: voice pitch and pace. */
internal data class Delivery(
    /** Base fundamental frequency in Hz (≈115 a low male voice, ≈205 a female voice). */
    val f0: Float,
    /** Seconds per syllable: 0.15 is brisk colloquial speech, 0.19 a calm explainer. */
    val syllable: Float,
)

/** One spoken word on the source clock. */
internal class Spoken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val syllables: Int,
    val stress: Boolean,
    val filler: Boolean,
    /** Index of the script token this word came from. */
    val token: Int,
    /** 0 at the start of a sentence, 1 at its end: pitch declines across it. */
    val progress: Float,
)

/** The timed performance of a script: words, the breaths in its pauses, and its length. */
internal class Timeline(val words: List<Spoken>, val breaths: List<LongRange>, val lengthMs: Long)

/**
 * The speaker's timing: syllable-paced words, short joins inside a phrase, a breath-length pause
 * at commas, longer ones at full stops (now and then a long think the edit must cut), a pause
 * around fillers, a lead-in and a tail.
 */
internal object Performance {
    fun time(tokens: List<ScriptReader.Token>, delivery: Delivery, rng: Dice): Timeline {
        val words = mutableListOf<Spoken>()
        val breaths = mutableListOf<LongRange>()
        var t = (rng.range(0.35f, 0.6f) * 1000).toLong()
        val flat = tokens.withIndex().flatMap { (k, tok) -> tok.clean.map { Triple(k, it, tok) } }
        // Sentence progress for the pitch contour.
        val sentenceOf = IntArray(flat.size)
        var s = 0
        flat.forEachIndexed { i, (_, w, _) -> sentenceOf[i] = s; if (ends(w)) s++ }
        for ((i, item) in flat.withIndex()) {
            val (k, w, tok) = item
            val syl = syllables(w)
            val stressed = tok.stress || (!tok.filler && autoStress(w, rng))
            val length = (syl * delivery.syllable * rng.range(0.85f, 1.15f) * (if (stressed) 1.18f else 1f) * (if (tok.filler) 1.6f else 1f) + 0.04f)
                .coerceAtLeast(0.14f)
            val end = t + (length * 1000).toLong()
            val inSentence = flat.indices.filter { sentenceOf[it] == sentenceOf[i] }
            val progress = if (inSentence.size < 2) 0f else (i - inSentence.first()).toFloat() / (inSentence.size - 1)
            words += Spoken(w, t, end, syl, stressed, tok.filler, k, progress)
            val next = flat.getOrNull(i + 1)
            val gap = when {
                next == null -> rng.range(0.5f, 0.8f)
                ends(w) -> if (rng.next() < 0.12f) rng.range(1.1f, 1.7f) else rng.range(0.42f, 0.85f)
                w.last() in "،,:;" -> rng.range(0.2f, 0.4f)
                tok.filler || next.third.filler -> rng.range(0.22f, 0.45f)
                w.endsWith("...") || w.endsWith("…") -> rng.range(0.6f, 0.9f)
                rng.next() < 0.04f -> rng.range(0.28f, 0.5f)
                else -> rng.range(0.02f, 0.09f)
            }
            val gapMs = (gap * 1000).toLong()
            // A breath in pauses long enough to take one, late in the pause.
            if (next != null && gap >= 0.42f) {
                val b = (rng.range(0.2f, 0.3f) * 1000).toLong()
                val bs = end + (gapMs - b) * 2 / 3
                breaths += bs until bs + b
            }
            t = end + gapMs
        }
        return Timeline(words, breaths, t)
    }

    fun ends(w: String) = w.isNotEmpty() && (w.last() in ".!?؟" || w.endsWith("…"))

    /** Numbers, Latin names and some long content words are leaned on. */
    private fun autoStress(w: String, rng: Dice): Boolean {
        val core = w.trim { !it.isLetterOrDigit() }
        return when {
            core.any { it.isDigit() } -> true
            core.firstOrNull()?.let { it in 'A'..'Z' } == true -> rng.next() < 0.7f
            core.length >= 6 -> rng.next() < 0.3f
            else -> rng.next() < 0.06f
        }
    }

    /** Spoken syllables, estimated from the written word (Persian leaves short vowels unwritten). */
    fun syllables(w: String): Int {
        val core = w.filter { it.isLetterOrDigit() }
        if (core.isEmpty()) return 1
        val digits = core.count { it.isDigit() }
        val persian = core.count { it in '؀'..'ۿ' }
        return if (persian > 0) {
            max(1, ((persian / 2.3f).roundToInt()) + (digits * 1.5f).roundToInt())
        } else {
            val vowels = Regex("[aeiouyAEIOUY]+").findAll(core).count()
            max(1, vowels - (if (core.endsWith("e") && vowels > 1) 1 else 0) + (digits * 1.5f).roundToInt())
        }
    }
}

/**
 * A voice for the pipeline's ears: each syllable a voiced burst (harmonics of a gliding pitch, a
 * short consonant noise at its onset) under a rounded envelope, so energy is modulated at the
 * syllable rate (≈4–6 Hz) like speech; stressed words louder and higher, pitch declining across a
 * sentence, quiet breaths in pauses and a room tone underneath. VAD, prosody and the silence cuts
 * run on it for real.
 */
internal object SyntheticVoice {
    const val RATE = 48_000
    private const val HARMONICS = 8

    fun render(timeline: Timeline, delivery: Delivery, seed: Long): PcmAudio {
        val n = (timeline.lengthMs * RATE / 1000).toInt()
        val out = FloatArray(n)
        val rng = Dice(seed)
        // Room tone ≈ -62 dBFS, lightly low-passed.
        var lp = 0f
        for (i in 0 until n) {
            lp += 0.35f * ((rng.next() * 2f - 1f) - lp)
            out[i] = lp * 0.0022f
        }
        val norm = 1f / sqrt((1..HARMONICS).sumOf { (1.0 / it.toDouble().pow(1.2)).pow(2) / 2 }).toFloat()
        var phase = 0.0
        for (w in timeline.words) {
            val start = (w.startMs * RATE / 1000).toInt()
            val end = minOf(n, (w.endMs * RATE / 1000).toInt())
            val level = db(-20f + rng.range(-2f, 2f) + (if (w.stress) 5f else 0f) - (if (w.filler) 7f else 0f))
            val pitch = delivery.f0 * (1f - 0.13f * w.progress) * (if (w.stress) 1.22f else 1f) * (if (w.filler) 0.92f else 1f)
            val per = (end - start) / w.syllables.toFloat()
            for (s in 0 until w.syllables) {
                val a = start + (s * per).toInt()
                val b = minOf(end, start + ((s + 1) * per).toInt())
                val sylPitch = pitch * rng.range(0.95f, 1.06f)
                val glide = rng.range(-0.06f, 0.04f)
                val onset = (0.025f * RATE).toInt()
                val sylLevel = level * rng.range(0.8f, 1.1f)
                for (i in a until b) {
                    val x = (i - a).toFloat() / (b - a).coerceAtLeast(1)
                    // A rounded syllable envelope with a short dip between syllables.
                    val env = sin(PI * x).toFloat().let { it * sqrt(it) }
                    val f = sylPitch * (1f + glide * x)
                    phase += 2 * PI * f / RATE
                    var v = 0.0
                    for (h in 1..HARMONICS) v += sin(phase * h) / h.toDouble().pow(1.2)
                    var sample = (v * norm).toFloat() * env * sylLevel
                    if (i - a < onset) sample += (rng.next() * 2f - 1f) * sylLevel * 0.35f * (1f - (i - a).toFloat() / onset)
                    out[i] += sample
                }
            }
        }
        // Breaths: soft band noise ≈ -42 dBFS.
        for (b in timeline.breaths) {
            val a = (b.first * RATE / 1000).toInt()
            val e = minOf(n, (b.last * RATE / 1000).toInt())
            var lo = 0f
            var hi = 0f
            for (i in a until e) {
                val x = (i - a).toFloat() / (e - a).coerceAtLeast(1)
                val white = rng.next() * 2f - 1f
                lo += 0.25f * (white - lo)
                hi = white - lo
                out[i] += hi * 0.012f * sin(PI * x).toFloat()
            }
        }
        return PcmAudio(out, RATE)
    }

    private fun db(v: Float) = 10f.pow(v / 20f) * sqrt(2f)
}

/**
 * The workshop's own randomness (java.util.Random, fixed by its specification): scenarios must not
 * change when the production [io.trimio.engine.autopilot.Rng] does.
 */
internal class Dice(seed: Long) {
    private val r = java.util.Random(seed)

    fun next(): Float = r.nextFloat()

    fun range(a: Float, b: Float): Float = a + (b - a) * r.nextFloat()

    fun chance(p: Float): Boolean = r.nextFloat() < p

    fun <T> pick(options: List<T>): T = options[r.nextInt(options.size)]
}
