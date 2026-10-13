package io.trimio.engine.perception.speakers

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Fft
import io.trimio.engine.perception.Perception
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One speaker's turn: who (0-based, in order of first appearance) and when, in seconds. */
data class Turn(val speaker: Int, val start: Float, val end: Float)

/**
 * Who speaks when. [speakers] is how many voices were found; [measured] is false when the add-on is
 * missing and everything was given to one speaker. Consumers: dialogue layouts and two-speaker
 * videos (caption colour or side per speaker, cuts on turn changes).
 */
data class Diarization(val turns: List<Turn>, val speakers: Int, val measured: Boolean) {
    /** The speaker at [time] (seconds), or null in silence. */
    fun speakerAt(time: Float): Int? = turns.firstOrNull { time >= it.start && time < it.end }?.speaker

    /** Each word's speaker: the turn under the word's middle, else the nearest turn. */
    fun speakersOf(words: List<Word>): List<Int> = words.map { w ->
        val mid = (w.range.startMs + w.range.endMs) / 2000f
        speakerAt(mid) ?: turns.minByOrNull { minOf(kotlin.math.abs(it.start - mid), kotlin.math.abs(it.end - mid)) }?.speaker ?: 0
    }

    companion object {
        fun single(durationSeconds: Float) = Diarization(listOf(Turn(0, 0f, durationSeconds)), 1, measured = false)
    }
}

/**
 * pyannote segmentation-3.0 (MIT) finds speech and speaker changes inside 10 s windows (seven
 * "powerset" classes: silence, one of three local speakers, or two at once, every ~17 ms); WeSpeaker
 * CAM++ (Apache-2.0, VoxCeleb) embeds each single-speaker stretch from 80-band Kaldi filterbanks;
 * average-linkage clustering of the embeddings by cosine similarity names the voices across the video.
 */
object SpeakerTurns {
    const val RATE = 16_000
    private const val WINDOW = 160_000
    private const val STEP = 80_000
    private const val FRAME_STEP = 270
    private const val FRAME_FIELD = 721
    private const val MIN_SPAN = 0.4f
    private const val MAX_PIECE = 3f

    /** Single-speaker spans (seconds), from overlapping segmentation windows: a span ends where the local speaker changes or speech stops. */
    fun spans(net: Network, audio: FloatArray): List<ClosedFloatingPointRange<Float>> {
        val spans = ArrayList<ClosedFloatingPointRange<Float>>()
        var start = 0
        do {
            spans += window(net, audio, start)
            start += STEP
        } while (start + WINDOW - STEP < audio.size)
        return spans.filter { it.endInclusive - it.start >= MIN_SPAN }.sortedBy { it.start }
            .flatMap { s -> pieces(s) }
    }

    /** The spans of one 10 s window starting at sample [start], kept to the window's middle (the overlaps belong to the neighbours). */
    private fun window(net: Network, audio: FloatArray, start: Int): List<ClosedFloatingPointRange<Float>> {
        val chunk = FloatArray(WINDOW)
        audio.copyInto(chunk, 0, start, min(audio.size, start + WINDOW))
        val out = net.run(mapOf(net.inputNames.first() to Tensor.of(longArrayOf(1, 1, WINDOW.toLong()), chunk))).values.first()
        val frames = out.shape[1].toInt()
        val classes = out.shape[2].toInt()
        val logits = out.requireFloats()
        val keepFrom = if (start == 0) 0f else (start + (WINDOW - STEP) / 2f) / RATE
        val keepUntil = if (start + WINDOW >= audio.size) Float.MAX_VALUE else (start + (WINDOW + STEP) / 2f) / RATE
        val spans = ArrayList<ClosedFloatingPointRange<Float>>()
        var runSpeaker = 0
        var runStart = 0f
        for (f in 0..frames) {
            val time = (start + f * FRAME_STEP + FRAME_FIELD / 2f) / RATE
            val speaker = if (f == frames) 0 else dominant(logits, f * classes, classes)
            if (speaker == runSpeaker) continue
            if (runSpeaker != 0) clip(runStart, time, keepFrom, keepUntil)?.let { spans += it }
            runSpeaker = speaker
            runStart = time
        }
        return spans
    }

    /** The local speaker (1..3) of a powerset frame; an overlap counts as its first speaker; 0 is silence. */
    private fun dominant(logits: FloatArray, base: Int, classes: Int): Int {
        var best = 0
        for (c in 1 until classes) if (logits[base + c] > logits[base + best]) best = c
        return when (best) {
            0 -> 0
            1, 2, 3 -> best
            4, 5 -> 1
            else -> 2
        }
    }

    private fun clip(a: Float, b: Float, from: Float, until: Float): ClosedFloatingPointRange<Float>? {
        val s = max(a, from)
        val e = min(b, until)
        return if (e > s) s..e else null
    }

    /** Long spans are embedded in pieces of at most [MAX_PIECE] s, so a change the segmenter missed still shows. */
    private fun pieces(s: ClosedFloatingPointRange<Float>): List<ClosedFloatingPointRange<Float>> {
        val n = ((s.endInclusive - s.start) / MAX_PIECE).toInt() + 1
        val len = (s.endInclusive - s.start) / n
        return (0 until n).map { k -> (s.start + k * len)..(s.start + (k + 1) * len) }
    }

    /** A unit-length speaker embedding for [audio] (16 kHz). */
    fun embed(net: Network, audio: FloatArray): FloatArray {
        val feats = Fbank.compute(audio)
        val frames = feats.size / Fbank.BINS
        val out = net.run(mapOf(net.inputNames.first() to Tensor.of(longArrayOf(1, frames.toLong(), Fbank.BINS.toLong()), feats))).values.first().requireFloats()
        val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(EPS)
        return FloatArray(out.size) { out[it] / norm }
    }

    /**
     * Average-linkage agglomerative clustering by cosine similarity: merge the closest clusters while
     * their mean similarity is at least [threshold] or there are more than [maxSpeakers]. Labels are
     * numbered by first appearance.
     */
    fun cluster(embeddings: List<FloatArray>, threshold: Float = SAME_SPEAKER, maxSpeakers: Int = MAX_SPEAKERS): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        val sim = Array(n) { i -> FloatArray(n) { j -> dot(embeddings[i], embeddings[j]) } }
        val members = (0 until n).map { mutableListOf(it) }.toMutableList()
        while (members.size > 1) {
            var bi = -1
            var bj = -1
            var best = Float.NEGATIVE_INFINITY
            for (i in members.indices) for (j in i + 1 until members.size) {
                val s = members[i].sumOf { a -> members[j].sumOf { b -> sim[a][b].toDouble() } }.toFloat() / (members[i].size * members[j].size)
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
            if (best < threshold && members.size <= maxSpeakers) break
            members[bi].addAll(members[bj])
            members.removeAt(bj)
        }
        val raw = IntArray(n)
        members.forEachIndexed { c, m -> m.forEach { raw[it] = c } }
        val order = LinkedHashMap<Int, Int>()
        raw.forEach { order.getOrPut(it) { order.size } }
        return IntArray(n) { order.getValue(raw[it]) }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Spans with their labels → merged turns. */
    fun turns(spans: List<ClosedFloatingPointRange<Float>>, labels: IntArray, gap: Float = MERGE_GAP): List<Turn> {
        val out = ArrayList<Turn>()
        spans.forEachIndexed { i, s ->
            val last = out.lastOrNull()
            if (last != null && last.speaker == labels[i] && s.start - last.end <= gap) out[out.lastIndex] = last.copy(end = max(last.end, s.endInclusive))
            else out += Turn(labels[i], s.start, s.endInclusive)
        }
        return out
    }

    /** Cosine similarity above which two stretches are the same voice (measured: same 0.3–0.65, different −0.05–0.17). */
    const val SAME_SPEAKER = 0.25f
    private const val MAX_SPEAKERS = 6
    private const val MERGE_GAP = 1f
    private const val EPS = 1e-6f
}

/**
 * Kaldi-compatible log mel filterbanks (`torchaudio.compliance.kaldi.fbank` with 80 bins, 25/10 ms
 * frames, Hamming window, no dither) with the mean over time removed, as WeSpeaker feeds its models.
 */
object Fbank {
    const val BINS = 80
    private const val LENGTH = 400
    private const val SHIFT = 160
    private const val FFT = 512
    private const val PREEMPH = 0.97f
    private const val SCALE = 32768f
    private const val LOW_HZ = 20.0
    private const val FLOOR = 1.1920929e-7f

    private val window = FloatArray(LENGTH) { (0.54 - 0.46 * cos(2 * PI * it / (LENGTH - 1))).toFloat() }
    private val banks: Array<FloatArray> by lazy { melBanks() }

    /** Frames × 80, row-major. */
    fun compute(audio: FloatArray): FloatArray {
        val frames = if (audio.size < LENGTH) 0 else 1 + (audio.size - LENGTH) / SHIFT
        val out = FloatArray(frames * BINS)
        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        for (f in 0 until frames) {
            val base = f * SHIFT
            var mean = 0f
            for (k in 0 until LENGTH) mean += audio[base + k] * SCALE
            mean /= LENGTH
            re.fill(0f)
            im.fill(0f)
            var prev = audio[base] * SCALE - mean
            for (k in 0 until LENGTH) {
                val x = audio[base + k] * SCALE - mean
                re[k] = (x - PREEMPH * (if (k == 0) x else prev)) * window[k]
                prev = x
            }
            Fft.transform(re, im)
            for (m in 0 until BINS) {
                var e = 0f
                val bank = banks[m]
                for (b in bank.indices) if (bank[b] != 0f) e += bank[b] * (re[b] * re[b] + im[b] * im[b])
                out[f * BINS + m] = ln(max(e, FLOOR))
            }
        }
        // Cepstral mean normalisation over the utterance.
        for (m in 0 until BINS) {
            var s = 0f
            for (f in 0 until frames) s += out[f * BINS + m]
            val mean = if (frames == 0) 0f else s / frames
            for (f in 0 until frames) out[f * BINS + m] -= mean
        }
        return out
    }

    private fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)

    private fun melBanks(): Array<FloatArray> {
        val lo = mel(LOW_HZ)
        val hi = mel(SpeakerTurns.RATE / 2.0)
        val delta = (hi - lo) / (BINS + 1)
        return Array(BINS) { m ->
            val left = lo + m * delta
            val center = left + delta
            val right = center + delta
            FloatArray(FFT / 2 + 1) { b ->
                val fm = mel(b * SpeakerTurns.RATE.toDouble() / FFT)
                if (fm <= left || fm >= right) 0f else min((fm - left) / (center - left), (right - fm) / (right - center)).toFloat()
            }
        }
    }
}

/** Diarization with the add-on when installed (both networks), else one speaker. */
class Diarizer(private val perception: Perception) {
    suspend fun diarize(audio: PcmAudio, maxSpeakers: Int = 6): Diarization {
        val duration = audio.size / audio.sampleRate.toFloat()
        if (audio.sampleRate != SpeakerTurns.RATE || !perception.has(MediaAddOns.speakerTurns, MediaAddOns.speakerVoices)) {
            perception.note("speakers: add-on missing, one speaker")
            return Diarization.single(duration)
        }
        val spans = perception.network("speakers", MediaAddOns.speakerTurns) { SpeakerTurns.spans(it, audio.samples) } ?: return Diarization.single(duration)
        if (spans.isEmpty()) return Diarization(emptyList(), 0, measured = true)
        val embeddings = perception.network("speakers", MediaAddOns.speakerVoices) { net ->
            spans.map { s -> SpeakerTurns.embed(net, audio.samples.copyOfRange((s.start * SpeakerTurns.RATE).toInt(), min(audio.size, (s.endInclusive * SpeakerTurns.RATE).toInt()))) }
        } ?: return Diarization.single(duration)
        val labels = SpeakerTurns.cluster(embeddings, maxSpeakers = maxSpeakers)
        val turns = SpeakerTurns.turns(spans, labels)
        return Diarization(turns, (labels.maxOrNull() ?: -1) + 1, measured = true)
    }
}
