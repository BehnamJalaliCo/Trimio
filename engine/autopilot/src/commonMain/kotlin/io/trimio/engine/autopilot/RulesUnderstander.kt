package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * The understanding step without a model: what can be read reliably from the words alone. Names
 * in Latin script (and coins named in Persian) are brands, numbers are counters — or, when their
 * units say so, a voucher (money given), stats (views and comments), a countdown (hours left) or
 * a progress bar (a quota that filled) — "send me the word X" is the call to action, the line with
 * the strongest number is the payoff, and each line's most stressed words are its title. Every edit still gets the planner's full craft; it just knows less about the content.
 */
object RulesUnderstander {

    fun understand(transcript: Transcript, prompt: String, lines: List<Lines.Line> = Lines.split(transcript.words)): Understanding {
        val words = transcript.words
        val texts = words.map { it.text }
        val norm = texts.map(NumberWords::normalize)
        val entities = names(texts) + coins(texts)
        val ctaLines = lines.filter { isCtaLine(it, texts, norm) }.toSet()
        val cta = cta(ctaLines.firstOrNull(), texts, norm)
        val senses = lines.map { Quantities.sense(texts.subList(it.first, it.last + 1), it.first) }
        val reads = lines.mapIndexed { k, line ->
            val inLine = entities.filter { it.at in line.range }.map { it.name }.distinct()
            val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
            // The numbers' meaning picks the richer graphics: a voucher, stats, a countdown, a progress bar.
            val rich = senses[k].richShow()
            when {
                line in ctaLines -> LineRead(role = "cta", show = "comment", title = cta!!.keyword)
                inLine.size >= 2 -> LineRead(role = "list", show = "logos", items = inLine, energy = 0.7f)
                rich != null -> LineRead(
                    role = if (rich == "voucher") "benefit" else "number", show = rich, title = title(line, words),
                    items = listOfNotNull(senses[k].voucher?.brand.takeIf { rich == "voucher" }), energy = 0.85f,
                )
                number != null && (number.percent || number.value >= 10) -> LineRead(role = "number", show = "counter", title = title(line, words), energy = 0.8f)
                else -> LineRead(role = "claim", show = "headline", title = title(line, words), energy = 0.4f + 0.5f * line.range.maxOf { words[it].emphasis })
            }
        }
        val payoff = lines.indices.filter { senses[it].quantities.isNotEmpty() }.maxByOrNull { senses[it].weight() }
        val coins = senses.any { s -> s.quantities.any { it.brand != null } }
        return Understanding(
            title = reads.firstOrNull()?.title.orEmpty(),
            domain = if (coins || norm.any { it in CRYPTO }) "crypto" else "general",
            mood = brief(prompt).let { if (it.energy >= 0.7f) "energetic" else "confident" },
            brief = brief(prompt),
            entities = entities,
            lines = reads,
            hook = payoff?.let { Hook(it, reads[it].title) },
            cta = cta,
        )
    }

    /** Coins named in Persian («تتر», «بیت‌کوین»): brands, so their marks can be found. */
    private fun coins(texts: List<String>): List<Entity> = texts.indices.mapNotNull { i ->
        val unit = Quantities.unitOf(texts[i])?.takeIf { it.brand != null && texts[i].none { c -> c in 'A'..'Z' } } ?: return@mapNotNull null
        Entity(i, i, unit.brand!!, kind = "brand")
    }

    /** Runs of capitalised Latin words, split at commas: "Claude Code، Codex" → two names. */
    private fun names(texts: List<String>): List<Entity> {
        val out = mutableListOf<Entity>()
        var i = 0
        while (i < texts.size) {
            if (!latinName(texts[i])) { i++; continue }
            val start = i
            val parts = mutableListOf<String>()
            while (i < texts.size && latinName(texts[i])) {
                parts += texts[i].trimEnd('،', ',', '.')
                i++
                if (texts[i - 1].last() in "،,.") break
            }
            out += Entity(start, i - 1, parts.joinToString(" "), kind = "brand")
        }
        return out
    }

    private fun latinName(w: String) = w.trimEnd('،', ',', '.').let { it.isNotEmpty() && it.first().isUpperCase() && it.all { c -> c.isLetterOrDigit() || c in "-+." } }

    /**
     * A call to action asks: "send me the word X", "comment X", "write …" — an imperative, or a
     * keyword marker with a send verb. «کامنت بذاریم» in a story or «پنج هزار کامنت» (a stat) is
     * not one, and the ask comes in the second half of the piece.
     */
    private fun isCtaLine(line: Lines.Line, texts: List<String>, norm: List<String>): Boolean {
        if (line.last * 2 < norm.size) return false
        val w = line.range.map { norm[it] }
        val marker = w.any { it in KEYWORD_MARKERS } || line.range.any { '«' in texts[it] || '"' in texts[it] }
        val send = w.any { it in SEND }
        val asked = w.indices.any { i -> w[i] in ASK && (i + 1..minOf(w.lastIndex, i + 2)).any { w[it] in IMPERATIVE } }
        return w.any { it in SEND_IMPERATIVE } || asked || (marker && send)
    }

    private fun cta(line: Lines.Line?, texts: List<String>, norm: List<String>): Cta? {
        line ?: return null
        val quoted = line.range.firstOrNull { '«' in texts[it] || '"' in texts[it] }
        val marked = line.range.firstOrNull { norm[it] in KEYWORD_MARKERS }?.let { it + 1 }?.takeIf { it <= line.last }
        val keyword = (quoted ?: marked)?.let { texts[it].trim('«', '»', '"', '.', '،', ',') }.orEmpty()
        val w = line.range.map { norm[it] }
        val action = if (w.none { it in SEND || it in ASK } && w.any { d -> DM.any { d.startsWith(it) } }) "dm" else "comment"
        return Cta(action = action, keyword = keyword)
    }

    private fun title(line: Lines.Line, words: List<io.trimio.core.model.transcript.Word>): String {
        val peak = line.range.filter { Words.isContent(words[it].text) }.maxByOrNull { Words.weight(words[it]) } ?: return ""
        return (peak..minOf(line.last, peak + 1)).joinToString(" ") { words[it].text.trimEnd('.', '،', ',') }
    }

    /** The brief, read by keywords in Persian and English. */
    fun brief(prompt: String): BriefRead {
        val p = prompt.lowercase()
        fun has(vararg k: String) = k.any { it in p }
        return BriefRead(
            look = when {
                has("نوآر", "noir", "تیره", "dark") -> "noir"
                has("کاغذ", "paper", "روشن", "light", "مینیمال") -> "paper"
                has("لومن", "lumen", "لوکس", "luxury", "آرام") -> "lumen"
                else -> "auto"
            },
            energy = when {
                has("پرانرژی", "تند", "هیجان", "energetic", "fast", "hype") -> 0.85f
                has("آرام", "calm", "ملایم", "soft") -> 0.35f
                else -> -1f
            },
            captions = when {
                has("کلمه‌به‌کلمه", "کلمه به کلمه", "word by word", "word-by-word") -> "word"
                has("بدون زیرنویس", "بدون کپشن", "no captions") -> "none"
                else -> "auto"
            },
            music = if (has("بدون موسیقی", "بدون موزیک", "no music")) "none" else "auto",
        )
    }

    private val SEND = setOf("ارسال", "بفرست", "بفرستید", "کامنت", "بنویس", "بنویسید", "comment", "dm")
    private val SEND_IMPERATIVE = setOf("بفرست", "بفرستید", "بفرستین", "بنویس", "بنویسید", "بنویسین")
    private val ASK = setOf("کامنت", "comment", "ارسال", "send", "type", "write")
    private val IMPERATIVE = setOf("کن", "بکن", "کنید", "بکنید", "کنین", "بکنین", "بذار", "بذارید", "بزار", "بزارید", "بزن", "بزنید", "below", "me", "it")
    private val KEYWORD_MARKERS = setOf("کلمه", "کلمهی", "کلمهٔ", "کلمۀ", "word", "keyword")
    private val DM = listOf("دایرکت", "دایرک", "dm", "direct", "inbox")
    private val CRYPTO = setOf("صرافی", "کریپتو", "ترید", "ارز", "بیتکوین", "تتر", "crypto", "exchange", "trading")
}
