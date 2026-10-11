package io.trimio.feature.create

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.trimio.core.brand.BrandFonts
import io.trimio.engine.director.Brief
import io.trimio.engine.director.Lexicon
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage

/**
 * One of the 28 design languages as the picker draws it: the swatch is set from the style's own
 * look (canvas, living background, ink, accent, headline voice and emphasis mark), so each card is
 * a still of what the director will make, not an illustration of it.
 */
data class StyleCard(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    /** A word in the style's voice (what its headline would say). */
    val sample: String,
    val canvas: Color,
    val aurora: List<Color>,
    val ink: Color,
    val accent: Color,
    val onAccent: Color,
    val role: BrandFonts.Role,
    val weight: Int,
    val mark: Look.Mark,
    val caps: Boolean,
    /** Lively styles lean on hits and slams; the picker's "energetic" filter. */
    val energetic: Boolean,
) {
    val dark: Boolean get() = canvas.luminance() < 0.4f
}

/** The picker's view of the 28 languages, and the director's ranking of them for a brief. */
object StyleCards {
    val all: List<StyleCard> by lazy { StyleBook.all.map(::card) }

    fun card(style: StyleLanguage): StyleCard {
        val look = style.look
        return StyleCard(
            id = style.id, nameFa = style.nameFa, nameEn = style.nameEn, sample = SAMPLES[style.id] ?: style.nameFa.substringBefore(' '),
            canvas = look.canvas, aurora = look.aurora, ink = look.ink, accent = look.accent, onAccent = look.onAccent,
            role = look.headline.role, weight = look.headline.weight, mark = look.mark, caps = look.caps,
            energetic = style.motion.energy >= ENERGETIC,
        )
    }

    /**
     * Fit of every style to [brief], 0..100, the best at 100: the brief's topic as the style's
     * domain and its energy as the mood (as the planner reads them).
     */
    fun ranked(brief: Brief): List<Pair<StyleCard, Int>> {
        val domains = brief.topics.map(::domainOf).ifEmpty { listOf("general") }
        val mood = when {
            (brief.energy ?: MID) >= HIGH -> "energetic"
            (brief.energy ?: MID) <= LOW -> "calm"
            else -> "confident"
        }
        val scores = StyleBook.all.map { s -> s.id to domains.maxOf { d -> s.fitness(d, mood) } }
        val best = scores.maxOf { it.second }.coerceAtLeast(EPSILON)
        val byId = all.associateBy { it.id }
        // Shown as a match score: the floor keeps a weak fit honest without reading as a failure.
        return scores.map { (id, f) -> byId.getValue(id) to (FLOOR + (PERCENT - FLOOR) * f / best).toInt() }.sortedByDescending { it.second }
    }

    private fun domainOf(topic: Lexicon.Topic): String = when (topic) {
        Lexicon.Topic.Crypto -> "crypto"
        Lexicon.Topic.Forex, Lexicon.Topic.Trading -> "finance"
        Lexicon.Topic.Business -> "business"
        Lexicon.Topic.Tech -> "tech"
        Lexicon.Topic.Education -> "education"
        Lexicon.Topic.Motivation -> "lifestyle"
        Lexicon.Topic.Ad -> "marketing"
    }

    private const val ENERGETIC = 0.6f
    private const val HIGH = 0.75f
    private const val LOW = 0.45f
    private const val MID = 0.6f
    private const val EPSILON = 0.0001f
    private const val PERCENT = 100f
    private const val FLOOR = 50f

    /** What each style's headline would say in a reel: short, in its own register. */
    private val SAMPLES = mapOf(
        "noir" to "نوآر", "paper" to "مجله", "lumen" to "شفاف", "bold-brutalist" to "سود!", "kinetic-poster" to "بزن",
        "swiss-grid" to "۰۴", "retro-vhs" to "1987", "neon-cyber" to "وای!", "minimal-mono" to "آرام", "playful-pop" to "سودا!",
        "finance-terminal" to "+5.2%", "news-broadcast" to "فوری", "documentary" to "روایت", "cinematic-trailer" to "به‌زودی",
        "beauty-soft" to "نرم", "food-warm" to "خوشمزه", "tech-product" to "Pro", "education-chalk" to "درس", "sports-energy" to "گل!",
        "kids-bright" to "بازی", "corporate-clean" to "رشد", "luxury-gold" to "طلا", "street-hype" to "هایپ", "calm-wellness" to "نفس",
        "travel-film" to "سفر", "real-estate-premium" to "خانه", "gaming" to "GG", "podcast-studio" to "پادکست",
    )
}

/** The picker's filters, in the board's order. */
enum class StyleFilter { Suggested, All, Energetic, Dark, Light }
