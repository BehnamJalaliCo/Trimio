package io.trimio.engine.retrieval

import io.trimio.core.model.text.TextKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A term as it must be spelled: [term] the canonical spelling (Persian, or a name's own Latin
 * spelling), [domain] (crypto, forex, finance, medical, tech, ai, social), [en] its English, [variants]
 * other spellings of the same word that people and recognisers write (Arabic letter forms,
 * spaces, sound-alike slips: these are corrected to [term]), [aliases] other names for it
 * (synonyms, Finglish, English names: found, never "corrected"), and [note] a short Persian
 * meaning for an on-screen definition.
 */
@Serializable
data class Term(
    val term: String,
    val domain: String,
    val en: String = "",
    val variants: List<String> = emptyList(),
    val note: String = "",
    val aliases: List<String> = emptyList(),
) {
    /** The spellings that are this very word, the canonical one first. */
    val spellings: List<String> get() = listOf(term) + variants

    /**
     * Every way a text names this term: its spellings, its English, and the aliases that cannot be
     * an everyday word (Latin names, or two words and more); a one-word Persian synonym («ابر» for
     * «فضای ابری») is left to search by meaning.
     */
    val names: List<String> get() = spellings + listOfNotNull(en.takeIf { it.isNotBlank() }) +
        aliases.filter { a -> a.none { it in '\u0600'..'\u06FF' } || TextKey.words(a).size > 1 }
}

/**
 * The domain glossary (and the creator's own, merged in): finds the terms a text names, by any of
 * their spellings ([find]), says how a heard word should be spelled ([spell]), and lists a topic's
 * terms for the recogniser's prompt and the director's ([forDomain]). Matching is on [TextKey]: one
 * to four words, half-spaces and spaces alike.
 */
class Glossary(val terms: List<Term>) {
    /** Key of a name (one to four words, compact) → the terms it names. */
    private val byKey: Map<String, List<Term>> = keys { it.names }

    /** Key of a spelling → the terms it is a spelling of. */
    private val bySpelling: Map<String, List<Term>> = keys { it.spellings }

    private fun keys(of: (Term) -> List<String>): Map<String, List<Term>> = buildMap<String, MutableList<Term>> {
        for (t in terms) for (s in of(t)) {
            val k = TextKey.of(s)
            if (k.length >= MIN_KEY) getOrPut(k) { mutableListOf() }.let { if (t !in it) it += t }
        }
    }
    private val maxWords = terms.flatMap { it.names }.maxOfOrNull { TextKey.words(it).size }?.coerceIn(1, MAX_WORDS) ?: 1

    val size: Int get() = terms.size

    /** A term found in a text: which words (indices into [TextKey.words] of the text) say it. */
    data class Found(val term: Term, val from: Int, val until: Int, val said: String)

    /** Every term [text] names, longest match first at each position, in order. */
    fun find(text: String): List<Found> {
        val words = TextKey.words(text)
        val out = mutableListOf<Found>()
        var i = 0
        while (i < words.size) {
            val at = i
            val hit = (minOf(maxWords, words.size - at) downTo 1).firstNotNullOfOrNull { n ->
                byKey[words.subList(at, at + n).joinToString("")]?.firstOrNull()?.let { t -> Found(t, at, at + n, words.subList(at, at + n).joinToString(" ")) }
            }
            if (hit != null) {
                out += hit
                i = hit.until
            } else {
                i++
            }
        }
        return out
    }

    /** The canonical spelling of [word] (or a phrase) when it is a known variant of a term; null otherwise or when ambiguous. */
    fun spell(word: String): String? = bySpelling[TextKey.of(word)]?.map { it.term }?.distinct()?.singleOrNull()

    fun lookup(term: String): Term? = byKey[TextKey.of(term)]?.firstOrNull()

    /** Up to [limit] terms of [domain] (all domains when null), in the glossary's order. */
    fun forDomain(domain: String?, limit: Int = Int.MAX_VALUE): List<Term> = terms.filter { domain == null || it.domain == domain }.take(limit)

    /** This glossary with [other]'s terms first (the creator's spellings win over the bundled ones). */
    operator fun plus(other: Glossary): Glossary = Glossary(other.terms + terms.filter { t -> other.lookup(t.term) == null })

    /** One document per term, for dense retrieval of what a sentence talks about. */
    fun docs(): List<Doc> = terms.map { t ->
        Doc("term:${TextKey.of(t.term)}:${t.domain}", "term", t.term, t.en, listOf(t.term, t.note).filter { it.isNotBlank() }.joinToString(" — "), t.variants + t.aliases, mapOf("domain" to t.domain))
    }

    /** The recogniser's prompt for a topic: canonical spellings, comma-separated, within [maxChars]. */
    fun asrPrompt(domain: String?, maxChars: Int = ASR_PROMPT_CHARS): String {
        val sb = StringBuilder()
        for (t in forDomain(domain)) {
            if (sb.length + t.term.length + 2 > maxChars) break
            if (sb.isNotEmpty()) sb.append("، ")
            sb.append(t.term)
        }
        return sb.toString()
    }

    companion object {
        const val DOMAINS = "crypto forex finance medical tech ai social"
        private const val MIN_KEY = 2
        private const val MAX_WORDS = 4
        private const val ASR_PROMPT_CHARS = 220
        private val json = Json { ignoreUnknownKeys = true }

        val Empty = Glossary(emptyList())

        /**
         * Parses the bundled format: a JSON array of rows `[term, domain, en, [variants], note, [aliases]]`
         * (the trailing fields optional).
         */
        fun parse(text: String): Glossary = Glossary(
            json.parseToJsonElement(text).jsonArray.map { row ->
                val a = row.jsonArray
                Term(
                    term = a[0].jsonPrimitive.content,
                    domain = a[1].jsonPrimitive.content,
                    en = a.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty(),
                    variants = (a.getOrNull(3) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    note = a.getOrNull(4)?.jsonPrimitive?.contentOrNull.orEmpty(),
                    aliases = (a.getOrNull(5) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                )
            },
        )
    }
}
