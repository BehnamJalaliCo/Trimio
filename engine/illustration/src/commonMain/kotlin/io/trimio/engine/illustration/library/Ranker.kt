package io.trimio.engine.illustration.library

/** A library item and how well it shows a query, 0..1. */
data class Ranked(val item: LibraryItem, val score: Float)

/**
 * Picks library items for a query (a subject, an object or a place, in Persian or English). The bundled
 * [KeywordRanker] matches words; an app with the retrieval add-ons plugs in a ranker that searches by
 * meaning (see [io.trimio.engine.illustration.Illustrator] and docs/research/ILLUSTRATION.md §5).
 */
fun interface ItemRanker {
    /** Items of [kinds] (null: any) for [query], best first, at most [limit], each scored 0..1. */
    suspend fun rank(query: String, items: List<LibraryItem>, kinds: Set<String>?, limit: Int): List<Ranked>
}

/**
 * Word matching against each item's keywords: the share of the query's words an item shows, with the
 * head word (the last English noun, the first Persian one) counting double, and the item's own first
 * keyword (its name) a bonus — "gold coin" prefers the coin over a medal.
 */
object KeywordRanker : ItemRanker {
    override suspend fun rank(query: String, items: List<LibraryItem>, kinds: Set<String>?, limit: Int): List<Ranked> {
        val terms = Words.terms(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val head = head(query, terms)
        val total = terms.size + 1f
        return items.asSequence()
            .filter { kinds == null || it.kind in kinds }
            .map { item ->
                var s = 0f
                for (t in terms) {
                    val m = Words.best(t, item.keys)
                    s += if (t == head) m * 2 else m
                }
                val name = (item.en.firstOrNull().orEmpty() + " " + item.fa.firstOrNull().orEmpty())
                if (Words.terms(name).any { n -> terms.any { Words.match(it, n) > 0f } }) s += NAME_BONUS
                Ranked(item, s / total)
            }
            .filter { it.score > 0f }
            // Sorted on the raw score (the name bonus breaks ties among full matches), reported capped at 1.
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.copy(score = it.score.coerceAtMost(1f)) }
            .toList()
    }

    /**
     * The head noun: Persian puts it first («گاوصندوق پر از طلا»); English last, or last before a
     * qualifier ("safe full of gold", "man with a phone").
     */
    fun head(query: String, terms: List<String> = Words.terms(query)): String {
        val first = terms.first()
        if (first.first() in '؀'..'ۿ') return first
        val raw = io.trimio.core.model.text.TextKey.words(query)
        val cut = raw.indexOfFirst { it in BREAKS }
        val before = if (cut > 0) raw.subList(0, cut).filter { it in terms } else emptyList()
        return before.lastOrNull() ?: terms.last()
    }

    private const val NAME_BONUS = 0.35f
    private val BREAKS = setOf("of", "with", "full", "in", "on", "at", "for", "from", "containing", "holding", "near", "inside")
}
