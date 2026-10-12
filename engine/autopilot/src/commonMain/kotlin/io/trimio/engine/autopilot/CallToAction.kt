package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore

/**
 * The planner's call to action, in two steps: the comment field types the keyword, then, when the
 * speaker sends viewers to their DMs («بعد برو دایرکتتو چک کن»), a short stamp says so while the
 * field stays up. Once asked, the field is the last thing on screen: one card from the ask to the
 * very end, nothing new around it (the planner runs the ask's scene to the end of the piece). The
 * keyword shown is always the word the speaker said ([keyword]). An ask with no keyword — follow,
 * save, the link in the bio, share, a question for the comments — ends on an end card instead,
 * in the piece's language. Either is up for at least [MIN_SHOWN] seconds.
 */
internal class CallToAction(
    private val words: List<Word>,
    private val texts: List<String>,
    /** [texts], normalised. */
    private val norm: List<String>,
    private val lines: List<Lines.Line>,
    private val u: Understanding,
    /** The creator's brief: names spelled there win over sound-alikes. */
    private val brief: String,
    private val rtl: Boolean,
    private val locate: (String, Lines.Line) -> IntRange?,
) {
    /** The comment field is on screen. */
    var placed = false
        private set

    /** The ask's own beats (the DM step), kept even inside a running scene. */
    val beats = mutableListOf<BeatScore>()

    /**
     * The word viewers are asked to send, as the speaker said it: the model's keyword when it is
     * said (a model may answer in English — "Tether" for «تتر»), else the word after «کلمه» in the
     * ask, else the spoken form of the brand the model named.
     */
    val keyword: String? by lazy {
        val asked = u.cta?.keyword?.trim('«', '»', '"', ' ')?.ifBlank { null }?.let { k -> Proofreader.fromBrief(listOf(k), brief)[0] ?: k }
        fun clean(i: Int) = texts[i].trim('«', '»', '"', '.', '،', ',', '!', '؟', '?')
        val said = asked?.takeIf { locate(it, Lines.Line(0, words.lastIndex)) != null }
        val marked = words.indices.lastOrNull { i -> i + 1 < words.size && i * 2 >= words.size && norm[i] in KEYWORD_MARKERS }?.let { clean(it + 1) }
        val brand = asked?.let { a ->
            u.entities.firstOrNull { it.name.equals(a, ignoreCase = true) }?.let { clean(it.at) }?.takeIf { w -> w.any { it in '؀'..'ۿ' } }
                ?: words.indices.firstOrNull { Quantities.coinOf(texts[it]).equals(a, ignoreCase = true) }?.let(::clean)
        }
        said ?: marked ?: brand ?: asked
    }

    /** The ask on [line]: the comment field typing the keyword (landing as it is said), or the end card of an ask without one. */
    fun comment(line: Lines.Line): BeatScore? {
        val action = u.cta?.action ?: "comment"
        val keyword = keyword?.takeIf { action in setOf("comment", "dm") } ?: return endCard(line, action)
        val at = early(locate(keyword, line)?.first ?: line.first, line)
        return BeatScore(recipe = "comment", at = at, text = keyword, hold = tail(at), label = label(action), place = "top")
    }

    /**
     * The last screen of an ask with no keyword: what to do («فالو کن», "Link in bio") with an icon
     * for it, and the reason as said («نکته‌های بیشتر», "the weekly roundup") under it.
     */
    private fun endCard(line: Lines.Line, action: String): BeatScore {
        val question = action == "comment"
        val at = early(line.first, line)
        // The brief's handle (a Telegram bot, a page) is where the ask sends viewers: it heads the card, as written.
        val reason = Glossary.read(brief).handles.firstOrNull() ?: Phrases.title(words, line).takeIf { ' ' in it }
        return BeatScore(
            recipe = "endcard", at = at, text = if (question) (if (rtl) "نظرت رو کامنت کن" else "Tell me in the comments") else label(action),
            icon = action, label = reason, hold = tail(at), energy = 0.8f, place = "top",
        )
    }

    /** The ask's label in the piece's language and for its action («دایرکت بده» for a DM, "Comment" in English). */
    private fun label(action: String) = (if (rtl) LABELS else ENGLISH_LABELS)[action] ?: (if (rtl) LABELS else ENGLISH_LABELS).getValue("comment")

    /** To the very end of the piece, whatever tail the edit adds (the compiler stops it there). */
    private fun tail(at: Int) = maxOf(MIN_SHOWN, words.last().range.endMs / 1000f + TAIL - start(at))

    /** [at], or earlier in the ask (never before its first word) when the piece ends too soon after it for the card to be read. */
    private fun early(at: Int, line: Lines.Line): Int {
        val end = words.last().range.endMs / 1000f
        if (end - start(at) >= MIN_SHOWN) return at
        return (at downTo line.first).firstOrNull { end - start(it) >= MIN_SHOWN } ?: line.first
    }

    private fun start(i: Int) = words[i].range.startMs / 1000f

    /** Line [k]'s part of the ask, added to its beats [out]: the field on the last line if nothing was asked, the DM step after it. */
    fun follow(k: Int, line: Lines.Line, out: MutableList<BeatScore>) {
        val last = k == lines.lastIndex && u.cta != null
        // Nothing asked yet by the last line: ask there.
        if (last && !placed && out.none { it.recipe in RECIPES }) comment(line)?.let { out += it }
        val field = out.indexOfFirst { it.recipe in RECIPES }
        if (field >= 0 && !placed) {
            placed = true
            val at = out[field].at ?: line.first
            dmStep(at, lines.getOrNull(k + 1)?.last ?: line.last)?.let { dm ->
                // The field holds through the DM step (its scene runs on to it).
                out[field] = out[field].copy(until = dm.at)
                out += dm
                beats += dm
            }
        }
    }

    /** «بعد برو دایرکتتو چک کن»: a stamp of the DM step, below the comment field. */
    private fun dmStep(after: Int, until: Int): BeatScore? {
        val i = (after + 1..minOf(until, words.lastIndex)).firstOrNull { j -> DM_WORDS.any { norm[j].trimEnd('،', ',', '.').startsWith(it) } } ?: return null
        val end = (i + 1..minOf(i + 2, words.lastIndex)).firstOrNull { norm[it].trimEnd('،', ',', '.') in CHECK_VERBS }
        val text = if (end != null) (i..end).joinToString(" ") { texts[it].trimEnd('.', '،', ',') } else if (rtl) "دایرکت" else "DM"
        return BeatScore(recipe = "stamp", at = i, text = text, hold = 1.8f, energy = 0.8f, place = "lower")
    }

    private companion object {
        val KEYWORD_MARKERS = setOf("کلمه", "کلمهی", "کلمهٔ", "کلمۀ", "word", "keyword")
        val DM_WORDS = listOf("دایرکت", "دایرک", "dm", "direct", "inbox")
        val CHECK_VERBS = setOf("کن", "بکن", "کنید", "بکنید", "کنین", "بکنین", "check", "it")
        val LABELS = mapOf(
            "comment" to "کامنت کن", "follow" to "فالو کن", "save" to "ذخیره کن", "share" to "بفرست برای دوستت", "link" to "لینک در بیو", "dm" to "دایرکت بده",
        )
        val ENGLISH_LABELS = mapOf(
            "comment" to "Comment", "follow" to "Follow", "save" to "Save this", "share" to "Send it to a friend", "link" to "Link in bio", "dm" to "DM me",
        )

        /** The ask's own recipes: the comment field, the end card. */
        val RECIPES = setOf("comment", "endcard")

        /** Seconds the ask is up, at least. */
        const val MIN_SHOWN = 3.5f

        /** Past the last word: further than any edit's tail, so the field reaches the very end. */
        const val TAIL = 3f
    }
}
