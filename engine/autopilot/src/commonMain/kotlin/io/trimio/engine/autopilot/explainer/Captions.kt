package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Numerals
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.explainer.CaptionChunk

/**
 * The narration on the caption band, a few words at a time: two to five words, broken where the
 * voice breaks (punctuation, a pause) and never longer than a glance; each chunk stays until the
 * next one starts when the gap is short, so the band never flickers.
 */
object Captions {

    fun of(words: List<Word>, persian: Boolean): List<CaptionChunk> {
        if (words.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Word>>()
        var current = mutableListOf<Word>()
        for ((i, w) in words.withIndex()) {
            current += w
            val next = words.getOrNull(i + 1)
            val gap = next?.let { (it.range.startMs - w.range.endMs) / MS } ?: 0.0
            val chars = current.sumOf { it.text.length + 1 }
            val breath = w.text.last() in BREAKS || gap >= PAUSE
            if (next == null || current.size >= MAX_WORDS || (current.size >= MIN_WORDS && (breath || chars + next.text.length > MAX_CHARS))) {
                groups += current
                current = mutableListOf()
            }
        }
        val balanced = balance(groups)
        return balanced.mapIndexed { k, g ->
            val start = g.first().range.startMs / MS
            val end = g.last().range.endMs / MS
            val next = balanced.getOrNull(k + 1)?.first()?.range?.startMs?.div(MS)
            val hold = if (next != null && next - end < HOLD) next else end
            val text = g.joinToString(" ") { it.text.trim() }.let { if (persian) Numerals.toPersian(it) else it }
            CaptionChunk(text, start, maxOf(hold, start + MIN_SHOW))
        }
    }

    /** A lone word joins its neighbour; when that would make six, the neighbour gives one word back. */
    private fun balance(groups: List<MutableList<Word>>): List<List<Word>> {
        val out = mutableListOf<MutableList<Word>>()
        for (g in groups) {
            val prev = out.lastOrNull()
            when {
                g.size >= MIN_WORDS || prev == null -> out += g
                prev.size < MAX_WORDS -> prev += g
                else -> out += (mutableListOf(prev.removeAt(prev.lastIndex)) + g).toMutableList()
            }
        }
        // A lone first word joins the next chunk (split again when that makes too many).
        if (out.size > 1 && out.first().size < MIN_WORDS) {
            val merged = out.removeAt(0) + out.removeAt(0)
            if (merged.size > MAX_WORDS) {
                out.add(0, merged.drop(MIN_WORDS + 1).toMutableList())
                out.add(0, merged.take(MIN_WORDS + 1).toMutableList())
            } else {
                out.add(0, merged.toMutableList())
            }
        }
        return out
    }

    const val MIN_WORDS = 2
    const val MAX_WORDS = 5
    private const val MAX_CHARS = 30
    private const val PAUSE = 0.3
    private const val HOLD = 0.45
    private const val MIN_SHOW = 0.35
    private const val MS = 1000.0
    private const val BREAKS = ".،,!?؟:؛;…"
}
