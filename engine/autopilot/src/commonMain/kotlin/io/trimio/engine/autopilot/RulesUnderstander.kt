package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.motion.score.NumberWords

/**
 * The understanding step without a model: what can be read reliably from the words alone. Names
 * (Latin words that sit like names, coins named in Persian) are brands; concrete things («تخم‌مرغ»,
 * "garlic", «قرص») get a picture; lists said out loud become lists; numbers are counters — or, when
 * their units say so, a voucher (money given), stats (views and comments), a countdown (hours
 * left), a progress bar (a quota that filled), a chart (a percentage across years); the ask
 * (comment a word, DM, follow, save, the link in the bio, share, a question) is the call to action;
 * the line whose number means most (a result, a price, a gift — not a frequency) is the payoff;
 * and each line's title is the noun phrase it stresses. Every edit still gets the planner's full
 * craft; it just knows less about the content.
 */
object RulesUnderstander {

    fun understand(transcript: Transcript, prompt: String, lines: List<Lines.Line> = Lines.split(transcript.words)): Understanding {
        val words = transcript.words
        val texts = words.map { it.text }
        val norm = texts.map(NumberWords::normalize)
        val things = things(texts)
        val entities = names(texts, prompt) + coins(texts) + things
        val ask = Asks.read(texts, lines)
        val senses = lines.map { Quantities.sense(texts.subList(it.first, it.last + 1), it.first) }
        val lists = Enumerations.lists(texts, lines)
        val wantsList = BriefAsks.read(prompt).any { it.kind == "list" }
        val series = series(texts, lines, senses)
        val reads = lines.mapIndexed { k, line ->
            val inLine = entities.filter { it.at in line.range && it.kind == "brand" }.map { it.name }.distinct()
            val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
            // The numbers' meaning picks the richer graphics; money is a voucher only when it is given.
            val rich = senses[k].richShow()?.takeIf { it != "voucher" || senses[k].gift }
            val list = lists[k]
            val title = Phrases.title(words, line)
            val thing = things.firstOrNull { it.at in line.range && (title.isEmpty() || Phrases.key(it.name) in title.split(' ').map(Phrases::key)) }
            when {
                ask?.first == k -> LineRead(role = "cta", show = "comment", title = ask.second.keyword)
                inLine.size >= 2 -> LineRead(role = "list", show = "logos", items = inLine, energy = 0.7f)
                list != null -> listRead(list, texts, wantsList)
                rich != null -> LineRead(
                    role = if (rich == "voucher") "benefit" else "number", show = rich, title = title,
                    items = listOfNotNull(senses[k].voucher?.brand.takeIf { rich == "voucher" }), energy = 0.85f,
                )
                k in series -> LineRead(role = "comparison", show = "chart", title = title, items = series.getValue(k), energy = 0.75f)
                number != null && (number.percent || number.value >= 10) -> LineRead(role = "number", show = "counter", title = title, energy = 0.8f)
                Enumerations.step(texts, line) != null -> LineRead(role = "steps", show = "headline", title = stepTitle(texts, line), energy = 0.75f)
                thing != null -> LineRead(role = "claim", show = "object", title = title, visual = thing.visual, energy = 0.6f)
                else -> LineRead(role = "claim", show = "headline", title = title, energy = 0.4f + 0.5f * line.range.maxOf { words[it].emphasis })
            }
        }
        val payoff = lines.indices.filter { k ->
            senses[k].quantities.any { it.kind == Quantities.Kind.Percent || it.value >= 10 } || Payoff.isResult(texts, lines[k]) && senses[k].quantities.isNotEmpty()
        }.maxByOrNull { Payoff.score(senses[it], texts, lines[it]) }?.takeIf { Payoff.score(senses[it], texts, lines[it]) > 0 }
        val coins = senses.any { s -> s.quantities.any { it.brand != null } }
        return Understanding(
            title = reads.firstOrNull()?.title.orEmpty(),
            domain = if (coins) "crypto" else Concepts.domainOf(texts, prompt),
            mood = brief(prompt).let { if (it.energy >= 0.7f) "energetic" else "confident" },
            brief = brief(prompt),
            entities = entities,
            lines = reads,
            hook = payoff?.let { Hook(it, reads[it].title) },
            cta = ask?.second,
        )
    }

    /** A list said out loud: pictures in a row when most items are things the vocabulary shows (and the brief did not ask for a list), else rows. */
    private fun listRead(list: Enumerations.Said, texts: List<String>, wantsList: Boolean): LineRead {
        val items = list.items.map { r -> r.joinToString(" ") { Phrases.clean(texts[it]) } }
        val visuals = list.items.map { r -> r.firstNotNullOfOrNull { Concepts.visualOf(texts[it]) } }
        val pictured = visuals.count { it != null }
        return if (!wantsList && pictured >= 2 && pictured * 2 >= items.size) {
            val kept = items.indices.filter { visuals[it] != null }.take(MAX_ITEMS)
            LineRead(role = "list", show = "objects", items = kept.map { items[it] }, visual = kept.joinToString(",") { visuals[it]!! }, energy = 0.7f)
        } else {
            LineRead(role = "list", show = "list", items = items.take(MAX_ITEMS), energy = 0.7f)
        }
    }

    /** A step's title as said: the counter and the thing («حرکت اول پلانک», "Number one Control plus"). */
    private fun stepTitle(texts: List<String>, line: Lines.Line): String =
        Enumerations.step(texts, line)?.joinToString(" ") { Phrases.clean(texts[it]) }.orEmpty()

    /**
     * Percentages said for two or more years in neighbouring lines («سال ۱۴۰۱ … پنجاه و دو درصد»،
     * «سال ۱۴۰۲ … سی و نه درصد»): one chart on the first line, its points the figures said.
     */
    private fun series(texts: List<String>, lines: List<Lines.Line>, senses: List<Quantities.Sense>): Map<Int, List<String>> {
        fun dated(k: Int): Double? {
            val q = senses[k].quantities
            // The year may be said just before, on the line the pause cut off («تو سال ۱۴۰۱ | به ۵۲ درصد رسید»).
            val year = (q + senses.getOrNull(k - 1)?.quantities.orEmpty()).any { it.value in YEARS && it.decimals == 0 }
            return q.firstOrNull { it.kind == Quantities.Kind.Percent }?.value?.takeIf { year }
        }
        val out = mutableMapOf<Int, List<String>>()
        var k = 0
        while (k < lines.size) {
            val first = dated(k)
            if (first == null) { k++; continue }
            val run = mutableListOf(k)
            var j = k + 1
            while (j < lines.size && j - run.last() <= 2) { if (dated(j) != null) run += j; j++ }
            if (run.size >= 2) out[k] = run.map { Quantities.digits(dated(it)!!, false) }
            k = run.last() + 1
        }
        return out
    }

    /** Concrete things said, once each: entities with a picture query («تخم‌مرغ» → egg). */
    private fun things(texts: List<String>): List<Entity> {
        val seen = mutableSetOf<String>()
        return texts.indices.mapNotNull { i ->
            val visual = Concepts.visualOf(texts[i])?.takeIf { NumberWords.at(texts, i) == null && seen.add(it) } ?: return@mapNotNull null
            Entity(i, i, Phrases.clean(texts[i]), kind = "object", visual = visual)
        }.take(MAX_THINGS)
    }

    /** Coins named in Persian («تتر», «بیت‌کوین»): brands, so their marks can be found. */
    private fun coins(texts: List<String>): List<Entity> = texts.indices.mapNotNull { i ->
        val unit = Quantities.unitOf(texts[i])?.takeIf { it.brand != null && texts[i].none { c -> c in 'A'..'Z' } } ?: return@mapNotNull null
        Entity(i, i, unit.brand!!, kind = "brand")
    }

    /** Runs of names ([Names]), split at commas: "Claude Code، Codex" → two names. */
    private fun names(texts: List<String>, prompt: String): List<Entity> {
        val out = mutableListOf<Entity>()
        var i = 0
        while (i < texts.size) {
            if (!Names.isName(texts, i, prompt)) { i++; continue }
            val start = i
            val parts = mutableListOf<String>()
            while (i < texts.size && (i == start || Names.isName(texts, i, prompt) || nameTail(texts[i])) && parts.size < MAX_NAME_WORDS) {
                parts += Names.bare(texts[i])
                i++
                if (texts[i - 1].last() in "،,.:!?؟") break
            }
            out += Entity(start, i - 1, parts.joinToString(" "), kind = "brand")
        }
        return out
    }

    /** The rest of a product name after its brand: "Pro", "Max", "15", "S24". */
    private fun nameTail(w: String) = Names.bare(w).let { b -> b.isNotEmpty() && (b.all { it.isDigit() } || (b.first().isUpperCase() && b.all { it.isLetterOrDigit() })) }

    /** English words capitalised only because they start a sentence ("I", "The", "Someone"): not names. */
    fun isCommonWord(w: String) = Names.isCommonWord(w)

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

    private val YEARS = 1300.0..2100.0
    private const val MAX_ITEMS = 4
    private const val MAX_THINGS = 14
    private const val MAX_NAME_WORDS = 4
}
