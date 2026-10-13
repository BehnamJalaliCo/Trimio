package io.trimio.engine.autopilot.explainer

import io.trimio.engine.motion.explainer.FilmGrammar

/**
 * Which film a narration wants. A concept or a comparison («فرق مدل‌ها», "how compound interest
 * works", steps, tiers) is a **diagram film**: calm chapters whose stage builds. A story, an event or
 * a piece of news («قضیه میلی گلد چیه؟», "users say…", "a report was published", yesterday, the
 * company, people gathered) is an **illustrated news film**: one fast scene per sentence, a headline
 * and a hero illustration. The brief's own words win («خبری», "news", «دیاگرام»); a model's choice
 * comes next ([LlmDirector]); these rules decide otherwise.
 */
object Grammars {

    /** The grammar the brief asks for in so many words, or null. */
    fun asked(prompt: String): FilmGrammar? {
        val p = prompt.lowercase().replace("‌", "")
        return when {
            NEWS_ASKS.any { it in p } -> FilmGrammar.IllustratedNews
            DIAGRAM_ASKS.any { it in p } -> FilmGrammar.DiagramChapters
            else -> null
        }
    }

    /** The grammar for [n]: the brief's, else by how much the words tell a story against how much they explain. */
    fun choose(n: Narration): FilmGrammar {
        asked(n.prompt)?.let { return it }
        val (news, diagram) = scores(n)
        return if (news > diagram + MARGIN) FilmGrammar.IllustratedNews else FilmGrammar.DiagramChapters
    }

    /** (story score, explanation score), per hundred words, from the words and the field. */
    fun scores(n: Narration): Pair<Float, Float> {
        val keys = n.keys.map { Metaphors.bare(it) }
        fun count(set: Set<String>) = keys.count { k -> set.any { s -> k == s || (s.length >= STEM && k.startsWith(s)) } }
        val per = HUNDRED / keys.size.coerceAtLeast(1)
        var news = count(STORY) * per
        var diagram = count(EXPLAIN) * per
        if (n.understanding.domain == "news") news += DOMAIN
        if (n.understanding.domain in CONCEPT_DOMAINS) diagram += DOMAIN / 2
        // Ideas the diagram language draws (tiers, limits, paths, steps) are explanation.
        val ideas = n.clauses.count { c -> Metaphors.find(c.words.map { n.keys[it] }).any { it.strength >= 1.1f && it.idea in DIAGRAM_IDEAS } }
        diagram += ideas * HUNDRED / n.clauses.size.coerceAtLeast(1) / 4f
        return news to diagram
    }

    private val NEWS_ASKS = listOf("خبری", "اخبار", "news", "illustrated", "تصویرسازی", "داستانی", "روایی", "story")
    private val DIAGRAM_ASKS = listOf("دیاگرام", "diagram", "آموزشی", "مفهومی", "explainer chapters", "توضیحی")

    /** Words that tell a story: events, reports, people acting, time of the event, money that went somewhere. */
    private val STORY = setOf(
        "قضیه", "ماجرا", "خبر", "گزارش", "اتفاق", "پرونده", "حادثه", "کاربرا", "کاربران", "مردم", "مشتری", "شکایت", "تجمع", "اعتراض", "دیروز", "امروز",
        "هفته پیش", "منتشر", "اعلام", "واریز", "برداشت", "تسویه", "پلیس", "دادگاه", "شرکت", "میگن", "گفتن", "فروختن", "خریدن", "بسته", "توقیف", "مسدود",
        "news", "report", "reported", "yesterday", "today", "announced", "users", "customers", "people", "police", "court", "company", "said", "says",
        "happened", "event", "protest", "arrested", "closed", "withdraw", "withdrawal", "sold", "bought", "lawsuit",
    )

    /** Words that explain: kinds, differences, how, why, tiers, steps, use, works. */
    private val EXPLAIN = setOf(
        "فرق", "تفاوت", "مدل", "یعنی", "چطور", "چرا", "روش", "مرحله", "قدم", "نکته", "استفاده", "کاربرد", "مقایسه", "بهترین", "قویترین", "سطح",
        "difference", "how", "why", "works", "work", "step", "steps", "tip", "tips", "use", "compare", "better", "best", "level", "type", "types", "means",
    )
    private val CONCEPT_DOMAINS = setOf("ai", "tech", "software", "education", "health", "medical", "fitness", "finance")
    private val DIAGRAM_IDEAS = setOf(Idea.Rank, Idea.Drain, Idea.Path, Idea.Routine, Idea.Portion, Idea.Persona, Idea.Steps, Idea.Compare, Idea.Levels, Idea.Uses)

    private const val HUNDRED = 100f
    private const val DOMAIN = 4f
    private const val MARGIN = 1.5f
    private const val STEM = 4
}
