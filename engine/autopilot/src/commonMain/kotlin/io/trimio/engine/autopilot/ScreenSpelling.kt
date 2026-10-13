package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Score

/**
 * The last gate before anything is drawn: every Persian word on screen must be a word the viewer
 * heard (as spelled), a word of the brief, or a word the lexicon knows. A model's misspelling
 * («وچر» for «ووچر») is put back to the spoken word it sounds like, or left out; a title with
 * nothing left is dropped. Latin names and numbers pass untouched.
 */
class ScreenSpelling(private val lexicon: PersianSpelling, spoken: List<String>, prompt: String) {
    private val heard: Map<String, String> = spoken.map(::core).filter { it.isNotEmpty() }.associateBy(::plain)
    private val brief: Set<String> = prompt.split(SEPARATORS).map(::core).filter { it.isNotEmpty() }.map(::plain).toSet()
    private val bySound: Map<String, String> = heard.values.associateBy { Proofreader.key(it) }

    /** The score with every on-screen Persian word checked; [log] hears each change. */
    fun check(score: Score, log: (String) -> Unit): Score = score.copy(
        scenes = score.scenes.map { s -> s.copy(beats = s.beats.mapNotNull { b -> beat(b, log) }) },
    )

    private fun beat(b: BeatScore, log: (String) -> Unit): BeatScore? {
        val text = b.text?.let { phrase(it, log) }
        // A title that lost every word has nothing left to say.
        if (!b.text.isNullOrBlank() && text.isNullOrBlank()) {
            log("screen spelling: dropped ${b.recipe} «${b.text}»")
            return null
        }
        return b.copy(
            text = text,
            label = b.label?.let { phrase(it, log) }?.ifBlank { null },
            items = b.items.map { item -> if (item.startsWith(">")) ">" + phrase(item.drop(1), log) else phrase(item, log) }.filter { it.isNotBlank() && it != ">" },
            emphasis = b.emphasis.mapNotNull { e -> phrase(e, log).ifBlank { null } },
        )
    }

    private fun phrase(s: String, log: (String) -> Unit): String = s.split(' ').mapNotNull { token ->
        val c = core(token)
        when {
            c.isEmpty() || !persian(c) || known(c) -> token
            else -> soundsLike(c)?.let { fix -> token.replace(c, fix) }
                .also { log("screen spelling: «$c» → ${it?.let { f -> "«${core(f)}»" } ?: "left out"}") }
        }
    }.joinToString(" ")

    /** The spoken word this one sounds like: the same sound key, or one sound slip away when only one word is. */
    private fun soundsLike(c: String): String? {
        val k = Proofreader.key(c)
        bySound[k]?.let { return it }
        return bySound.filterKeys { k.length >= MIN_SLIP && distance(it, k) == 1 }.values.singleOrNull()
    }

    private fun known(c: String) = plain(c) in heard || plain(c) in brief || lexicon.count(c) > 0

    private companion object {
        val SEPARATORS = Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")
        const val ZWNJ = '\u200C'

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != ZWNJ }.trim(ZWNJ)
        fun plain(w: String) = w.replace(ZWNJ.toString(), "").replace("\u0654", "")
        const val MIN_SLIP = 3

        fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }

        fun persian(w: String) = w.any { it in '\u0620'..'\u06FF' && it.isLetter() }
    }
}
