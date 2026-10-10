package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * The understanding step without a model: what can be read reliably from the words alone. Names
 * in Latin script are brands, numbers are counters, "send me the word X" is the call to action,
 * the line with the biggest number is the payoff, and each line's most stressed words are its
 * title. Every edit still gets the planner's full craft; it just knows less about the content.
 */
object RulesUnderstander {

    fun understand(transcript: Transcript, prompt: String, lines: List<Lines.Line> = Lines.split(transcript.words)): Understanding {
        val words = transcript.words
        val texts = words.map { it.text }
        val norm = texts.map(NumberWords::normalize)
        val entities = names(texts)
        val cta = cta(texts, norm)
        val reads = lines.map { line ->
            val inLine = entities.filter { it.at in line.range }
            val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
            val isCta = cta != null && line.range.any { norm[it] in SEND }
            when {
                isCta -> LineRead(role = "cta", show = "comment", title = cta!!.keyword)
                inLine.size >= 2 -> LineRead(role = "list", show = "logos", items = inLine.map { it.name }, energy = 0.7f)
                number != null && (number.percent || number.value >= 10) -> LineRead(role = "number", show = "counter", title = title(line, words), energy = 0.8f)
                else -> LineRead(role = "claim", show = "headline", title = title(line, words), energy = 0.4f + 0.5f * line.range.maxOf { words[it].emphasis })
            }
        }
        val payoff = lines.indices.maxByOrNull { k ->
            NumberWords.findAll(texts.subList(lines[k].first, lines[k].last + 1)).maxOfOrNull { (if (it.percent) 100.0 else 0.0) + it.value.coerceAtMost(99.0) } ?: -1.0
        }?.takeIf { k -> NumberWords.findAll(texts.subList(lines[k].first, lines[k].last + 1)).isNotEmpty() }
        return Understanding(
            title = reads.firstOrNull()?.title.orEmpty(),
            mood = brief(prompt).let { if (it.energy >= 0.7f) "energetic" else "confident" },
            brief = brief(prompt),
            entities = entities,
            lines = reads,
            hook = payoff?.let { Hook(it, reads[it].title) },
            cta = cta,
        )
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

    private fun cta(texts: List<String>, norm: List<String>): Cta? {
        if (norm.none { it in SEND }) return null
        val quoted = texts.indexOfFirst { '«' in it || '"' in it }
        val keyword = if (quoted >= 0) texts[quoted].trim('«', '»', '"', '.', '،') else ""
        return Cta(action = "comment", keyword = keyword)
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
}
