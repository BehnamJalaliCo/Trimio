package io.trimio.engine.autopilot

/**
 * The creator's glossary, as the brief writes it: the sites it names (nobitex.ir), the technical
 * terms it spells (RSI, «واگرایی») with the meaning it gives them («RSI یعنی شاخص قدرت نسبی»,
 * "RSI (relative strength index)"), and whether it asks for terms to be explained («توضیح بده»).
 * Recognising them in the speech is [BriefNames]' work; this says what to show once they are heard.
 */
internal class Glossary(
    /** Domains, as written. */
    val sites: List<String>,
    /** Terms to their meaning (null when the brief gives none). */
    val terms: Map<String, String?>,
    /** The brief asks for a short definition of its terms on screen. */
    val explain: Boolean,
) {
    /** The brief's spelling of a heard [word] when it is one of its sites (or any domain said as one). */
    fun site(word: String): String? {
        val core = word.trim { !it.isLetterOrDigit() }
        if (!BriefNames.DOMAIN.matches(core)) return null
        return sites.firstOrNull { it.equals(core, ignoreCase = true) } ?: core.lowercase()
    }

    companion object {
        private val FILES = Regex("(?i)\\.(png|jpe?g|gif|webp|svg|heic|mp4|mov|m4v|webm|pdf|txt)$")
        private val SITE = Regex("(?i)(?:https?://)?(?:www\\.)?([a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.[a-z]{2,6})")
        private val EXPLAIN = listOf("توضیح", "تعریف", "explain", "define", "definition", "what it means")
        private val ACRONYM = Regex("\\b[A-Z][A-Z0-9]{1,5}\\b")
        private val QUOTED = Regex("«([^»]{2,30})»")
        private const val MAX_DEFINITION = 48

        fun read(brief: String): Glossary {
            val sites = SITE.findAll(brief).map { it.groupValues[1] }.filter { !FILES.containsMatchIn(it) && BriefNames.DOMAIN.matches(it) }.distinct().toList()
            val names = (ACRONYM.findAll(brief).map { it.value } + QUOTED.findAll(brief).map { it.groupValues[1].trim() }).distinct().toList()
            val terms = names.associateWith { definition(brief, it) }.filter { (t, d) -> d != null || ACRONYM.matches(t) }
            val lower = brief.lowercase()
            return Glossary(sites, terms, EXPLAIN.any { it in lower })
        }

        /** The meaning the brief gives [term]: «RSI یعنی …», "RSI (…)", "RSI: …", "RSI means …". */
        private fun definition(brief: String, term: String): String? {
            val t = Regex.escape(term)
            val patterns = listOf(
                Regex("$t»?\\s*\\(([^)]{3,80})\\)"),
                Regex("$t»?\\s*(?:یعنی|=|:|–)\\s*([^.،,؛;\\n()]{3,80})"),
                Regex("(?i)$t\\s+(?:means|stands for|is short for|is)\\s+([^.,;\\n()]{3,80})"),
            )
            val found = patterns.firstNotNullOfOrNull { it.find(brief)?.groupValues?.get(1) } ?: return null
            // The meaning only: the brief's instruction after it («… رو توضیح بده») is cut off.
            val clean = found.split(" رو ", " را ", " and ", " و توضیح", " توضیح").first().trim().trim('«', '»', '"')
            return clean.takeIf { it.length in 3..MAX_DEFINITION }
        }
    }
}
