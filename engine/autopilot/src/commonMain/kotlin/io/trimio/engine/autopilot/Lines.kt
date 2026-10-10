package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word

/**
 * The transcript cut into lines an editor would treat as one thought: sentence ends and real
 * pauses split, very long runs split again at commas or the longest pause, scraps merge into a
 * neighbour. Lines are what the understanding model reads one by one (far easier for a small
 * model than free word indices) and the planner's unit of rhythm.
 */
object Lines {
    data class Line(val first: Int, val last: Int) {
        val range: IntRange get() = first..last
        val size: Int get() = last - first + 1
    }

    /**
     * [pauses] (seconds, from the voice itself) mark breaths the recogniser's timing may hide; a
     * pause of [pauseMs] or more between words, or a sentence end, closes a line.
     */
    fun split(
        words: List<Word>,
        maxWords: Int = 12,
        minWords: Int = 3,
        pauseMs: Long = 450,
        pauses: List<ClosedFloatingPointRange<Float>> = emptyList(),
    ): List<Line> {
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<Line>()
        var start = 0
        fun breath(i: Int): Boolean {
            val a = words[i].range.endMs / 1000f - 0.05f
            val b = words.getOrNull(i + 1)?.range?.startMs?.div(1000f)?.plus(0.05f) ?: return false
            return pauses.any { p -> p.endInclusive - p.start >= BREATH_S && p.start >= a && p.endInclusive <= b }
        }
        for (i in words.indices) {
            val next = words.getOrNull(i + 1)
            val gap = next?.let { it.range.startMs - words[i].range.endMs } ?: 0L
            val ends = next == null || words[i].endsSentence || words[i].text.last() in END
            // Never inside a name ("Claude | Code"): a pause between two Latin words is a breath, not a break.
            val inName = next != null && latin(words[i].text) && latin(next.text) && words[i].text.last() !in SOFT
            val paused = gap >= pauseMs || breath(i)
            if (ends || (!inName && paused)) {
                lines += Line(start, i)
                start = i + 1
            }
        }
        val split = lines.flatMap { breakUp(it, words, maxWords) }
        return merge(split, minWords)
    }

    /**
     * Long lines break at the comma nearest the middle (never inside a run of names: "Claude Code،
     * Codex یا OpenCode" is one list), else at the longest pause, until every part fits.
     */
    private fun breakUp(line: Line, words: List<Word>, maxWords: Int): List<Line> {
        if (line.size <= maxWords) return listOf(line)
        val inner = (line.first + 2 until line.last - 1)
        val middle = line.first + line.size / 2f
        val cut = inner.filter { words[it].text.last() in SOFT && !(latin(words[it].text) && latin(words[it + 1].text)) }
            .minByOrNull { kotlin.math.abs(it + 0.5f - middle) }
            ?: inner.maxByOrNull { words[it + 1].range.startMs - words[it].range.endMs }
            ?: (line.first + line.size / 2)
        return breakUp(Line(line.first, cut), words, maxWords) + breakUp(Line(cut + 1, line.last), words, maxWords)
    }

    private fun merge(lines: List<Line>, minWords: Int): List<Line> {
        val out = mutableListOf<Line>()
        for (l in lines) {
            val prev = out.lastOrNull()
            val scrap = prev != null && (l.size < minWords || prev.size < minWords)
            if (scrap && prev!!.size + l.size <= MAX_MERGED) {
                out[out.lastIndex] = Line(prev.first, l.last)
            } else {
                out += l
            }
        }
        return out
    }

    private fun latin(w: String) = w.any { it in 'A'..'Z' || it in 'a'..'z' }

    private const val BREATH_S = 0.3f
    private const val END = ".!?؟…"
    private const val SOFT = "،,;؛:"
    private const val MAX_MERGED = 14
}
