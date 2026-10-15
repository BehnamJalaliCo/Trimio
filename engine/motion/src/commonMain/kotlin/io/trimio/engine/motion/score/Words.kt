package io.trimio.engine.motion.score

import io.trimio.core.model.transcript.Word

/** Reading the transcript the way an editor does: what carries meaning, how to chunk captions. */
object Words {

    /** Function words: never a highlight, never a headline's anchor, however loudly said. */
    private val function = setOf(
        "و", "یا", "که", "از", "به", "با", "در", "تو", "توی", "برای", "تا", "را", "رو", "این", "اون", "آن", "همین", "یه", "یک",
        "است", "هست", "بود", "شد", "شده", "میشه", "می‌شه", "می‌کنه", "میکنه", "کنه", "بکنه", "بکنی", "بشه", "باشه", "داره", "دارم",
        "من", "تو", "ما", "شما", "اون‌ها", "خودت", "خودم", "برات", "براتون", "بعد", "قبل", "کافیه", "باعث", "هم", "همه", "کل", "خیلی",
        "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "with", "is", "are", "it", "this", "that", "you", "your", "my",
    )

    fun isContent(word: String): Boolean {
        val n = NumberWords.normalize(word)
        return n.length > 1 && n !in function
    }

    /**
     * A hesitation, not a word («اِم», «اوم», «ااا», «آه», "um", "uh"): captions never show it. Its time
     * stays in the transcript (the cut list and the rhythm hear it); only the text is left out.
     * A plain «ام» is a word («خسته ام»), so only the voweled «اِم» counts.
     */
    fun isFiller(word: String): Boolean {
        val w = word.trim { !it.isLetter() && it !in '\u064B'..'\u0652' }.lowercase()
        return w.isNotEmpty() && FILLER.matches(w)
    }

    private val FILLER = Regex("ا{2,}|آ{2,}|آه+|اِم+|امم+|او+م+|ا+ه{2,}|u+[mh]+|uhm+|e+r+m+|h+m{2,}")

    /** Stress weighted by meaning: loud function words do not count. */
    fun weight(w: Word): Float = if (isContent(w.text)) w.emphasis else w.emphasis * 0.15f

    /**
     * Caption chunks: up to [maxWords] words, breaking at sentence ends and real pauses (and at commas with [commas]), then
     * folding orphans (a lone word, or a flash shorter than [minSeconds]) into a neighbour.
     */
    private fun clauseEnd(w: Word) = w.text.lastOrNull()?.let { it == '،' || it == ',' } == true

    fun captionLines(words: List<Word>, maxWords: Int, pauseSeconds: Float = 0.45f, minSeconds: Float = 0.55f, commas: Boolean = false): List<List<Word>> {
        val lines = mutableListOf<MutableList<Word>>()
        var current = mutableListOf<Word>()
        for ((i, w) in words.withIndex()) {
            current += w
            val next = words.getOrNull(i + 1)
            val pause = next != null && (next.range.startMs - w.range.endMs) / 1000f > pauseSeconds
            val full = current.size >= maxWords || next == null
            val ends = w.endsSentence || (commas && clauseEnd(w))
            if (ends || pause || full) {
                lines += current
                current = mutableListOf()
            }
        }
        fun seconds(l: List<Word>) = (l.last().range.endMs - l.first().range.startMs) / 1000f
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val orphan = line.size == 1 || seconds(line) < minSeconds
            val prev = lines.getOrNull(i - 1)
            val next = lines.getOrNull(i + 1)
            when {
                orphan && prev != null && prev.size <= maxWords && !prev.last().endsSentence -> { prev += line; lines.removeAt(i) }
                orphan && next != null && next.size <= maxWords && !line.last().endsSentence -> { next.addAll(0, line); lines.removeAt(i) }
                orphan && prev != null && prev.size <= maxWords -> { prev += line; lines.removeAt(i) }
                else -> i++
            }
        }
        return lines
    }
}
