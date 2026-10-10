package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.NumberWords

/**
 * The call to action, read from the words: "send me the word X" (a comment or a DM with a
 * keyword), or an ask with no keyword — follow, save, the link in the bio, share it, or a question
 * for the comments. Only the second half of a piece asks; «کامنت بذاریم» told in a story or «پنج
 * هزار کامنت» (a stat) is not an ask.
 */
object Asks {

    /** The ask and the line it is made on, or null when the piece asks nothing. */
    fun read(texts: List<String>, lines: List<Lines.Line>): Pair<Int, Cta>? {
        val norm = texts.map(NumberWords::normalize)
        for (k in lines.indices.reversed()) {
            val line = lines[k]
            if (line.last * 2 < texts.size) break
            ask(line, texts, norm)?.let { return k to it }
        }
        return null
    }

    private fun ask(line: Lines.Line, texts: List<String>, norm: List<String>): Cta? {
        val w = line.range.map { Phrases.key(texts[it]) }
        fun any(set: Collection<String>) = w.any { x -> set.any { x == it || (it.length >= 3 && x.startsWith(it)) } }
        val keyword = keyword(line, texts, norm)
        // «کامنت بکن، بعد برو دایرکتتو چک کن» asks for a comment; «دایرکت بفرستید» for a DM.
        val dm = any(DM) && !any(listOf("کامنت", "comment"))
        val asked = w.any { it in SEND_IMPERATIVE } || w.indices.any { i -> w[i] in ASK && (i + 1..minOf(w.lastIndex, i + 2)).any { w[it] in IMPERATIVE } }
        val marker = w.any { it in KEYWORD_MARKERS }
        return when {
            keyword != null && (asked || marker || dm) -> Cta(action = if (dm) "dm" else "comment", keyword = keyword)
            any(FOLLOW) -> Cta(action = "follow")
            any(SAVE) && (w.any { it in IMPERATIVE } || "this" in w || "it" in w || w.any { it.startsWith("سیوش") }) -> Cta(action = "save")
            any(LINK) && any(BIO) -> Cta(action = "link")
            any(SHARE) && (w.any { it.startsWith("بفرستش") } || "this" in w || "it" in w || "send" in w) -> Cta(action = "share")
            line.range.any { texts[it].trimEnd().lastOrNull()?.let { c -> c in "?؟" } == true || texts.getOrNull(it + 1)?.let { n -> Phrases.key(n) in WRITE } == true } && any(COMMENTS) ->
                Cta(action = "comment")
            dm && asked -> Cta(action = "dm")
            else -> null
        }
    }

    /** The word to send: quoted («کد», "SHEET"), or the one after «کلمه»/"word". */
    private fun keyword(line: Lines.Line, texts: List<String>, norm: List<String>): String? {
        val quoted = line.range.firstOrNull { '«' in texts[it] || '"' in texts[it] }
        val marked = line.range.firstOrNull { Phrases.key(texts[it]) in KEYWORD_MARKERS }?.let { it + 1 }?.takeIf { it <= line.last }
        val at = quoted ?: marked ?: return null
        return texts[at].trim('«', '»', '"', '.', '،', ',', '!', '?', '؟').takeIf { it.isNotBlank() && Phrases.key(norm[at]) !in IMPERATIVE }
    }

    private val SEND_IMPERATIVE = setOf("بفرست", "بفرستید", "بفرستین", "بنویس", "بنویسید", "بنویسین")
    private val ASK = setOf("کامنت", "comment", "ارسال", "send", "type", "write", "دایرکت", "dm")
    private val IMPERATIVE = setOf(
        "کن", "بکن", "کنید", "بکنید", "کنین", "بکنین", "بذار", "بذارید", "بزار", "بزارید", "بزن", "بزنید", "بده", "بدید", "بدین", "بفرستید", "below", "me", "it",
    )
    private val KEYWORD_MARKERS = setOf("کلمه", "کلمهی", "کلمهٔ", "کلمۀ", "word", "keyword")
    private val DM = listOf("دایرکت", "دایرک", "dm", "direct", "inbox")
    private val FOLLOW = listOf("فالو", "follow", "فالوم")
    private val SAVE = listOf("سیو", "سیوش", "ذخیره", "save")
    private val LINK = listOf("لینک", "link", "link's")
    private val BIO = listOf("بایو", "bio")
    private val SHARE = listOf("بفرستش", "شیر", "share", "send")
    private val COMMENTS = listOf("کامنت", "کامنتا", "comment", "comments", "بنویسید", "بگید", "know")
    private val WRITE = setOf("بنویسید", "بنویس", "بگید", "below")
}
