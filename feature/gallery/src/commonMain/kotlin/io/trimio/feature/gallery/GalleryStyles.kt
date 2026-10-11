package io.trimio.feature.gallery

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.style.StyleFamily
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage

/** The gallery's filters, in the board's order («همه» first). */
enum class GalleryFilter(val fa: String, val en: String) {
    All("همه", "All"),
    Kinetic("کینتیک", "Kinetic"),
    Glass("شیشه و عمق", "Glass & depth"),
    Minimal("مینیمال", "Minimal"),
    Bold("جسور", "Bold"),
    Cinematic("سینمایی", "Cinematic"),
}

/** One style as the gallery card shows it: its name, what it is like in a few words, its canvas, its filters. */
data class GalleryStyle(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val hintFa: String,
    val hintEn: String,
    val canvas: Color,
    val ink: Color,
    val groups: Set<GalleryFilter>,
) {
    /** A dark canvas carries light text over a soft scrim; a light one dark text and no scrim. */
    val dark: Boolean get() = canvas.luminance() < DARK_CANVAS
}

private const val DARK_CANVAS = 0.4f

/** The 28 styles, filtered: grouped by their picker family, their motion and their character. */
object GalleryStyles {
    val all: List<GalleryStyle> by lazy { StyleBook.all.map(::of) }

    fun filter(f: GalleryFilter): List<GalleryStyle> = if (f == GalleryFilter.All) all else all.filter { f in it.groups }

    fun of(s: StyleLanguage): GalleryStyle {
        val (hintFa, hintEn) = HINTS[s.id] ?: ("" to "")
        return GalleryStyle(s.id, s.nameFa, s.nameEn, hintFa, hintEn, s.look.canvas, s.look.ink, groups(s))
    }

    private fun groups(s: StyleLanguage): Set<GalleryFilter> = buildSet {
        val family = DesignStyle.fromId(s.pack)?.family
        if (family == StyleFamily.Typography || s.motion.energy >= KINETIC || s.id in KINETIC_IDS) add(GalleryFilter.Kinetic)
        if (family == StyleFamily.ShaderFx || family == StyleFamily.ThreeD || s.id in GLASS_IDS) add(GalleryFilter.Glass)
        if (s.id in MINIMAL_IDS || (s.motion.energy in 0f..CALM && s.look.canvas.luminance() > LIGHT)) add(GalleryFilter.Minimal)
        if (s.id in BOLD_IDS) add(GalleryFilter.Bold)
        if (s.id in CINEMATIC_IDS) add(GalleryFilter.Cinematic)
    }

    private const val LIGHT = 0.6f
    private const val KINETIC = 0.75f
    private const val CALM = 0.4f

    private val KINETIC_IDS = setOf("kinetic-poster", "sports-energy", "street-hype", "playful-pop", "gaming")
    private val GLASS_IDS = setOf("lumen", "tech-product", "luxury-gold", "neon-cyber")
    private val MINIMAL_IDS = setOf("paper", "finance-terminal", "education-chalk", "swiss-grid", "minimal-mono", "corporate-clean", "calm-wellness", "real-estate-premium")
    private val BOLD_IDS = setOf("bold-brutalist", "kinetic-poster", "neon-cyber", "playful-pop", "news-broadcast", "street-hype", "sports-energy", "gaming", "kids-bright")
    private val CINEMATIC_IDS = setOf("noir", "food-warm", "cinematic-trailer", "documentary", "travel-film", "luxury-gold", "retro-vhs")

    /** What each style is like, in a few words (the card's second line). */
    private val HINTS = mapOf(
        "noir" to ("سینمایی، تیتر عظیم" to "Cinematic, huge titles"),
        "paper" to ("مجله‌ای، سفید و منظم" to "Editorial, white and ordered"),
        "lumen" to ("عمق، نور، شیشه" to "Depth, light, glass"),
        "bold-brutalist" to ("جسور، سایه‌های تخت" to "Bold, flat shadows"),
        "kinetic-poster" to ("کلمه‌به‌کلمه، پرانرژی" to "Word by word, high energy"),
        "swiss-grid" to ("شبکه، عدد، نظم" to "Grid, numbers, order"),
        "retro-vhs" to ("نوار ویدیو، دهه‌ی هشتاد" to "Tape, the eighties"),
        "neon-cyber" to ("نئون، شب، تند" to "Neon, night, sharp"),
        "minimal-mono" to ("کم، آرام، دقیق" to "Little, calm, precise"),
        "playful-pop" to ("رنگی، شاد، بازیگوش" to "Colourful, happy, playful"),
        "finance-terminal" to ("بازار، نمودار، عدد" to "Markets, charts, figures"),
        "news-broadcast" to ("خبری، فوری، زیرنویس" to "Newsroom, urgent, tickers"),
        "documentary" to ("روایت، آرام، مستند" to "Story, calm, factual"),
        "cinematic-trailer" to ("تریلر، تاریک، باشکوه" to "Trailer, dark, grand"),
        "beauty-soft" to ("نرم، روشن، ظریف" to "Soft, bright, delicate"),
        "food-warm" to ("گرم، اشتهابرانگیز" to "Warm, appetising"),
        "tech-product" to ("محصول، براق، دقیق" to "Product, glossy, precise"),
        "education-chalk" to ("آموزشی، گچ و تخته" to "Teaching, chalk and board"),
        "sports-energy" to ("ورزشی، سریع، پرضرب" to "Sport, fast, punchy"),
        "kids-bright" to ("کودکانه، روشن، گرد" to "For kids, bright, round"),
        "corporate-clean" to ("سازمانی، تمیز، معتبر" to "Corporate, clean, credible"),
        "luxury-gold" to ("لوکس، طلایی، آهسته" to "Luxury, gold, unhurried"),
        "street-hype" to ("خیابانی، هایپ، استیکر" to "Street, hype, stickers"),
        "calm-wellness" to ("آرام، نفس، طبیعت" to "Calm, breath, nature"),
        "travel-film" to ("سفر، فیلم، افق" to "Travel, film, horizon"),
        "real-estate-premium" to ("ملک، فضا، ممتاز" to "Property, space, premium"),
        "gaming" to ("گیم، نئون، انفجار" to "Gaming, neon, bursts"),
        "podcast-studio" to ("پادکست، گفتگو، موج صدا" to "Podcast, talk, waveforms"),
    )
}
