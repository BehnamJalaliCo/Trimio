package io.trimio.engine.perception.text

import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Perception

/**
 * Punctuation restored on recognised words: each word gets the mark that follows it (or none)
 * and sentence ends are known, so captions read properly and the line splitter breaks at real
 * sentence ends. Words keep their own spelling and timing; only trailing marks change.
 */
data class Punctuated(val words: List<Word>, val sentenceEnds: List<Boolean>, val measured: Boolean)

/**
 * `1-800-BAD-CODE/punct_cap_seg_47_language` (Apache-2.0; Persian is one of its 47 languages): a
 * 6-layer transformer over a 64k SentencePiece vocabulary that predicts, per subword, the mark after
 * it (16 classes, including ، and ؟) and whether a sentence ends there. Input is lower-cased,
 * unpunctuated text with BOS/EOS, at most 128 tokens; longer texts are windowed with a 16-token
 * overlap and the overlap's halves dropped on each side, as the reference `punctuators` package does.
 *
 * The gated ParsBERT PersianPunc model (MIT, macro-F1 91.3) would be the better Persian tagger; it
 * needs the owner's Hugging Face account to accept its terms, so it is not in the catalogue yet.
 */
object PunctCapSeg {
    private const val MAX = 128
    private const val OVERLAP = 16
    val POST = listOf(null, ".", ",", "?", "？", "，", "。", "、", "・", "।", "؟", "،", ";", "።", "፣", "፧")

    /** Post-punctuation label and sentence-end flag for the last subword of each word. */
    fun predict(net: Network, sp: SentencePiece, words: List<String>): List<Pair<String?, Boolean>> {
        val ids = ArrayList<Int>()
        val owner = ArrayList<Int>()
        words.forEachIndexed { w, word ->
            val t = sp.encode(word.lowercase())
            t.forEach { ids += it; owner += w }
        }
        val post = IntArray(ids.size)
        val seg = BooleanArray(ids.size)
        val body = MAX - 2
        var start = 0
        var index = 0
        val windows = ArrayList<IntRange>()
        while (start < ids.size) {
            val from = start - if (index == 0) 0 else OVERLAP
            val until = minOf(from + body, ids.size)
            windows += from until until
            start = from + body
            index++
        }
        windows.forEachIndexed { k, range ->
            val input = LongArray(range.count() + 2)
            input[0] = sp.bos.toLong()
            range.forEachIndexed { i, t -> input[i + 1] = ids[t].toLong() }
            input[input.size - 1] = sp.eos.toLong()
            val out = net.run(mapOf("input_ids" to Tensor.ofLongs(longArrayOf(1, input.size.toLong()), input)), listOf("post_preds", "seg_preds"))
            val p = out.getValue("post_preds").longs ?: LongArray(input.size)
            val s = out.getValue("seg_preds").bytes ?: ByteArray(input.size)
            val keepFrom = if (k > 0) OVERLAP / 2 else 0
            val keepUntil = range.count() - if (k < windows.lastIndex) OVERLAP / 2 else 0
            for (i in keepFrom until keepUntil) {
                post[range.first + i] = p[i + 1].toInt()
                seg[range.first + i] = s[i + 1].toInt() != 0
            }
        }
        val last = IntArray(words.size) { -1 }
        owner.forEachIndexed { t, w -> last[w] = t }
        return words.indices.map { w -> if (last[w] < 0) null to false else POST.getOrNull(post[last[w]]) to seg[last[w]] }
    }
}

/** Restores punctuation with the model when installed, else with rules over the words' own marks and pauses. */
class Punctuator(private val perception: Perception) {
    private var tokenizer: SentencePiece? = null

    suspend fun punctuate(words: List<Word>, language: Language): Punctuated {
        if (words.isEmpty()) return Punctuated(words, emptyList(), measured = false)
        val bare = words.map { Marks.strip(it.text) }
        val sp = if (perception.has(MediaAddOns.punctuation)) tokenizer() else null
        val labels = sp?.let { tok -> perception.network("punctuation", MediaAddOns.punctuation) { net -> PunctCapSeg.predict(net, tok, bare) } }
        if (labels == null) return Marks.byRules(words, language)
        val out = words.mapIndexed { i, w ->
            val mark = labels[i].first?.let { Marks.localize(it, language) }
            w.copy(text = bare[i].ifEmpty { w.text } + (mark ?: ""))
        }
        // A sentence end without a mark (the model can predict one apart from the other) closes with a full stop.
        val ends = labels.map { (mark, end) -> end || mark in Marks.FINAL }
        val closed = out.mapIndexed { i, w -> if (ends[i] && !w.endsSentence) w.copy(text = w.text.trimEnd('،', ',', '؛', ';') + ".") else w }
        return Punctuated(closed, ends, measured = true)
    }

    private fun tokenizer(): SentencePiece? = tokenizer ?: perception.readBytes(MediaAddOns.punctuationTokenizer)
        ?.let { runCatching { SentencePiece.load(it) }.getOrNull() }?.also { tokenizer = it }
}

/** Punctuation without a model, and the marks' Persian forms. */
object Marks {
    val FINAL = setOf(".", "?", "؟", "!", "。", "？", "।")
    private const val TRAILING = ".,،؛;:!?؟…"

    /** The word without trailing marks (and without a leading «). */
    fun strip(text: String): String = text.trimEnd { it in TRAILING }

    /** Persian text takes Arabic-script marks. */
    fun localize(mark: String, language: Language): String = if (language != Language.Persian) mark else when (mark) {
        ",", "，", "、" -> "،"
        "?", "？" -> "؟"
        ";" -> "؛"
        "。", "।", "።" -> "."
        else -> mark
    }

    /**
     * Rules: keep whisper's own marks, close a sentence at a pause of [pauseMs] or more, ask with ؟
     * when the sentence opens with a question word. Rough but never worse than nothing.
     */
    fun byRules(words: List<Word>, language: Language, pauseMs: Long = 700): Punctuated {
        val out = words.toMutableList()
        val ends = BooleanArray(words.size)
        var sentenceStart = 0
        for (i in words.indices) {
            val next = words.getOrNull(i + 1)
            val pause = next?.let { it.range.startMs - words[i].range.endMs } ?: Long.MAX_VALUE
            val end = words[i].endsSentence || pause >= pauseMs
            if (!end) continue
            ends[i] = true
            if (!out[i].endsSentence) {
                val question = words.subList(sentenceStart, i + 1).take(2).any { strip(it.text) in QUESTION }
                val mark = if (question) localize("?", language) else "."
                out[i] = out[i].copy(text = strip(out[i].text) + mark)
            }
            sentenceStart = i + 1
        }
        return Punctuated(out, ends.toList(), measured = false)
    }

    private val QUESTION = setOf("آیا", "چرا", "چطور", "چطوری", "چگونه", "کجا", "کی", "چی", "چه", "کدام", "کدوم", "چند", "why", "how", "what", "where", "when", "who")
}
