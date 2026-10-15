package io.trimio.engine.autopilot

/**
 * Words the list knows as they are said but that are never written so on screen: the spoken spelling of a word
 * with a written one («دولار» → «دلار»), and trading talk whose term is spelled one way («تیپی», «تی‌پی» → «تیپ»,
 * a trade tip) — the latter only in a piece that talks trading, where «تیپی» is no style. A word keeps its endings'
 * punctuation («دولار،» → «دلار،»).
 */
internal object WrittenForms {

    /**
     * The written form of [word] in a piece whose words are [texts], or null when it is written as it is. A site the
     * recogniser wrote half in Persian («کوینپرو.com») is the brief's [sites] spelling of it (coinepro.com) when their
     * sounds agree.
     */
    fun of(word: String, texts: List<String>, sites: List<String> = emptyList()): String? {
        val core = word.trimEnd(*TRAIL)
        val tail = word.substring(core.length)
        val plain = core.replace("‌", "")
        val written = ALWAYS[plain] ?: TRADING[plain]?.takeIf { trading(texts) } ?: site(plain, sites) ?: echo(plain, texts) ?: return null
        return written + tail
    }

    /**
     * A word misheard by its first letter as another word the list knows («برودیمون» for «ورودیمون»): the piece itself
     * says which one was meant. When its stem is said nowhere else in the piece and the stem with another first letter
     * is said at least [ECHOES] times («ورودی», «ورودیشم», «ورودی مونه»), it is that word, its endings kept.
     */
    private fun echo(heard: String, texts: List<String>): String? {
        if (heard.length < MIN_ECHO || heard.none { it in '\u0600'..'\u06FF' }) return null
        val said = texts.map { it.trimEnd(*TRAIL).replace("\u200C", "") }
        val endings = ENDINGS.filter { heard.endsWith(it) && heard.length - it.length >= MIN_STEM }
        // A stem said again in the piece is what was said: no echo of another.
        if (endings.any { e -> said.count { it.startsWith(heard.dropLast(e.length)) } > 1 }) return null
        return endings.firstNotNullOfOrNull { e -> echoed(heard.dropLast(e.length), said)?.plus(e) }
    }

    /** [stem] with another first letter, said at least [ECHOES] times in [said], when there is exactly one such. */
    private fun echoed(stem: String, said: List<String>): String? =
        said.filter { it.length >= stem.length && it.first() != stem.first() && it.substring(1).startsWith(stem.substring(1)) }
            .groupingBy { it.first() + stem.substring(1) }.eachCount().filterValues { it >= ECHOES }.keys.singleOrNull()

    private fun site(heard: String, sites: List<String>): String? {
        val dot = heard.lastIndexOf('.')
        if (dot <= 0 || sites.isEmpty()) return null
        val name = heard.substring(0, dot)
        val end = heard.substring(dot).lowercase()
        val sound = if (name.any { it in '؀'..'ۿ' }) BriefNames.persianSkeleton(name) else BriefNames.latinSkeleton(name)
        if (sound.length < MIN_SOUND) return null
        return sites.firstOrNull { s ->
            val sd = s.lastIndexOf('.')
            sd > 0 && s.substring(sd).lowercase() == end && PersianRepairs.distance(BriefNames.latinSkeleton(s.substring(0, sd)), sound) <= SOUND_SLIP
        }?.takeIf { it != heard }
    }

    private fun trading(texts: List<String>): Boolean = texts.count { w -> TRADE_WORDS.any { w.replace("‌", "").startsWith(it) } } >= MIN_TRADE

    private val TRAIL = charArrayOf('،', ',', '.', '!', '?', '؟', '؛', ';', ':')
    private val ALWAYS = mapOf("دولار" to "دلار", "دولارو" to "دلارو", "دولاری" to "دلاری")
    private val TRADING = mapOf("تیپی" to "تیپ", "تیپیه" to "تیپه", "تیپیها" to "تیپ‌ها", "تیپیهامون" to "تیپ‌هامون")
    private val TRADE_WORDS = listOf("سیگنال", "ترید", "تتر", "کریپتو", "بیتکوین", "استاپ", "استاب", "فیوچرز", "اسپات", "لیکوئید", "تریدر")
    private const val MIN_TRADE = 2
    private const val MIN_SOUND = 3
    private const val MIN_ECHO = 5
    private const val MIN_STEM = 5
    private const val ECHOES = 2
    private val ENDINGS = listOf("مونه", "مون", "تون", "شون", "شم", "ش", "ه", "")
    private const val SOUND_SLIP = 1
}
