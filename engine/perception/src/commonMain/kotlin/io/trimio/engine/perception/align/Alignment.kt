package io.trimio.engine.perception.align

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Perception
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Word times after forced alignment: the proofread words (their final spelling) timed against the
 * audio itself, so captions and cuts land on the syllable. [measured] is false when the aligner was
 * not installed or could not align and the recogniser's times were kept; [moved] counts words whose
 * time changed.
 */
data class Aligned(val words: List<Word>, val measured: Boolean, val moved: Int = 0)

/**
 * CTC forced alignment (the Viterbi path over blank-interleaved targets, as torchaudio's
 * `forced_align`) on the emissions of Meta's Omnilingual ASR CTC-300M (Apache-2.0, 1600+ languages,
 * a character vocabulary with Persian letters and digits). The network takes the raw 16 kHz
 * waveform (normalised to zero mean and unit variance) and emits one frame per 20 ms.
 */
class CtcAligner(private val vocab: Map<String, Int>) {
    private val space = vocab[" "]

    /** Target ids for [words] (spaces between words), and for each word the index range of its ids; untokenisable words get an empty range. */
    fun targets(words: List<String>): Pair<IntArray, List<IntRange>> {
        val ids = ArrayList<Int>()
        val spans = ArrayList<IntRange>()
        words.forEachIndexed { i, w ->
            if (i > 0 && space != null && ids.isNotEmpty()) ids += space
            val start = ids.size
            normalize(w).forEach { ch -> vocab[ch.toString()]?.let { ids += it } }
            spans += start until ids.size
        }
        return ids.toIntArray() to spans
    }

    /** Log-probabilities per frame for the [needed] vocabulary ids only (a full row is 10k floats), plus blank. */
    fun emissions(net: Network, audio16k: FloatArray, needed: IntArray): Array<FloatArray> {
        val x = standardized(audio16k)
        val out = net.run(mapOf(net.inputNames.first() to Tensor.of(longArrayOf(1, x.size.toLong()), x)))
        val logits = out.getValue(net.outputNames.first())
        val frames = logits.shape[1].toInt()
        val v = logits.shape[2].toInt()
        val data = logits.requireFloats()
        return Array(frames) { t ->
            val base = t * v
            var m = Float.NEGATIVE_INFINITY
            for (k in 0 until v) m = max(m, data[base + k])
            var sum = 0.0
            for (k in 0 until v) sum += exp((data[base + k] - m).toDouble())
            val lse = m + ln(sum).toFloat()
            FloatArray(needed.size) { j -> data[base + needed[j]] - lse }
        }
    }

    companion object {
        const val BLANK = 0
        const val FRAME_SECONDS = 0.02f

        fun fromTokens(text: String): CtcAligner = CtcAligner(
            text.lineSequence().filter { it.isNotEmpty() }.associate { line ->
                val cut = line.lastIndexOf(' ')
                line.substring(0, cut) to line.substring(cut + 1).trim().toInt()
            },
        )

        /** Lower case, no punctuation, no zero-width joiners (the vocabulary has none). */
        fun normalize(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }

        private fun standardized(x: FloatArray): FloatArray {
            if (x.isEmpty()) return x
            val mean = x.average()
            var v = 0.0
            for (s in x) v += (s - mean) * (s - mean)
            val sd = sqrt(v / x.size + VAR_EPS)
            return FloatArray(x.size) { ((x[it] - mean) / sd).toFloat() }
        }

        private const val VAR_EPS = 1e-7

        /**
         * The best path's first and last frame for every target position, or null when the audio has
         * too few frames. [logp] holds log-probabilities for the columns of [columns] (blank first).
         */
        fun viterbi(logp: Array<FloatArray>, targets: IntArray, columns: IntArray): Array<IntArray>? {
            val frames = logp.size
            val l = targets.size
            if (l == 0 || frames < l) return null
            val col = HashMap<Int, Int>().apply { columns.forEachIndexed { i, id -> put(id, i) } }
            val states = 2 * l + 1
            fun label(s: Int) = if (s % 2 == 0) BLANK else targets[s / 2]
            val lab = IntArray(states) { col.getValue(label(it)) }
            val neg = Float.NEGATIVE_INFINITY
            var prev = FloatArray(states) { neg }
            val back = Array(frames) { ByteArray(states) }
            prev[0] = logp[0][lab[0]]
            prev[1] = logp[0][lab[1]]
            for (t in 1 until frames) {
                val cur = FloatArray(states) { neg }
                // A state can only be reached once enough frames remain for the rest of the targets.
                val lo = max(0, states - 2 * (frames - t) - 1)
                val hi = min(states - 1, 2 * t + 1)
                for (s in lo..hi) {
                    var best = prev[s]
                    var from: Byte = 0
                    if (s >= 1 && prev[s - 1] > best) {
                        best = prev[s - 1]
                        from = 1
                    }
                    // Skipping the blank between two different tokens.
                    val canSkip = s >= 2 && s % 2 == 1 && targets[s / 2] != targets[s / 2 - 1]
                    if (canSkip && prev[s - 2] > best) {
                        best = prev[s - 2]
                        from = 2
                    }
                    if (best == neg) continue
                    cur[s] = best + logp[t][lab[s]]
                    back[t][s] = from
                }
                prev = cur
            }
            var s = if (prev[states - 1] >= prev[states - 2]) states - 1 else states - 2
            if (prev[s] == neg) return null
            val first = IntArray(l) { -1 }
            val last = IntArray(l) { -1 }
            for (t in frames - 1 downTo 0) {
                if (s % 2 == 1) {
                    val k = s / 2
                    if (last[k] < 0) last[k] = t
                    first[k] = t
                }
                s -= back[t][s].toInt()
            }
            return arrayOf(first, last)
        }
    }
}

/**
 * Times proofread words against the audio. Words are aligned in chunks of up to [chunkSeconds]
 * (bounded memory: the encoder attends over a whole chunk) cut where the recogniser saw a pause; a
 * word the aligner cannot place, or places more than [maxShift] seconds from the recogniser's
 * time, keeps the recogniser's time.
 */
class ForcedAligner(private val perception: Perception, private val chunkSeconds: Float = 20f, private val maxShift: Float = 1f) {
    private var aligner: CtcAligner? = null

    suspend fun align(words: List<Word>, audio: PcmAudio): Aligned {
        if (words.isEmpty() || audio.sampleRate != PcmAudio.WHISPER_RATE) return Aligned(words, measured = false)
        val ctc = if (perception.has(MediaAddOns.aligner)) load() else null
        val aligned = ctc?.let { c -> perception.network("alignment", MediaAddOns.aligner) { net -> chunks(words).flatMap { alignChunk(net, c, it, audio) } } }
            ?: return Aligned(words, measured = false)
        // A word that kept the recogniser's time must not start before the aligned word ahead of it.
        val out = aligned.toMutableList()
        for (i in 1 until out.size) {
            val floor = out[i - 1].range.startMs
            if (out[i].range.startMs < floor) out[i] = out[i].copy(range = TimeRange(floor, max(floor, out[i].range.endMs)))
        }
        val moved = out.indices.count { out[it].range != words[it].range }
        return Aligned(out, measured = true, moved = moved)
    }

    private fun load(): CtcAligner? = aligner ?: perception.readText(MediaAddOns.alignerTokens)?.let { runCatching { CtcAligner.fromTokens(it) }.getOrNull() }?.also { aligner = it }

    /** Consecutive word groups no longer than the chunk, split at the widest gaps. */
    internal fun chunks(words: List<Word>): List<List<Word>> {
        val out = ArrayList<List<Word>>()
        var start = 0
        while (start < words.size) {
            var end = start
            while (end + 1 < words.size && (words[end + 1].range.endMs - words[start].range.startMs) / MS <= chunkSeconds) end++
            if (end + 1 < words.size && end > start) {
                // Cut at the widest pause in the last third of the chunk.
                val from = start + (end - start) * 2 / 3
                end = (from..end).maxByOrNull { k -> words.getOrNull(k + 1)?.let { it.range.startMs - words[k].range.endMs } ?: 0L } ?: end
            }
            out += words.subList(start, end + 1)
            start = end + 1
        }
        return out
    }

    private fun alignChunk(net: Network, ctc: CtcAligner, chunk: List<Word>, audio: PcmAudio): List<Word> {
        val from = max(0L, chunk.first().range.startMs - MARGIN_MS)
        val until = min(audio.durationMs, chunk.last().range.endMs + MARGIN_MS)
        val (targets, spans) = ctc.targets(chunk.map { it.text })
        val columns = (intArrayOf(CtcAligner.BLANK) + targets.distinct()).distinct().toIntArray()
        val logp = ctc.emissions(net, audio.slice(from, until).samples, columns)
        val path = CtcAligner.viterbi(logp, targets, columns) ?: return chunk
        val (first, last) = path[0] to path[1]
        val frameMs = CtcAligner.FRAME_SECONDS * MS
        return chunk.mapIndexed { i, w ->
            val span = spans[i]
            if (span.isEmpty()) return@mapIndexed w
            val start = from + (first[span.first] * frameMs).toLong()
            val end = from + ((last[span.last] + 1) * frameMs).toLong()
            val shifted = abs(start - w.range.startMs) > maxShift * MS || abs(end - w.range.endMs) > maxShift * MS
            if (shifted || end <= start) w else w.copy(range = TimeRange(start, end))
        }
    }

    private companion object {
        const val MS = 1000f
        const val MARGIN_MS = 400L
    }
}
