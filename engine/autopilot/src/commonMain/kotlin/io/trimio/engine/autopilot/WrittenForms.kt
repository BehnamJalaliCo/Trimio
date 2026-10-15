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
        val written = ALWAYS[plain] ?: TRADING[plain]?.takeIf { trading(texts) } ?: site(plain, sites) ?: return null
        return written + tail
    }

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
    private const val SOUND_SLIP = 1
}
