package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.trimio.engine.motion.Contrast
import io.trimio.engine.motion.recipe.Look

/**
 * One explainer palette: a calm ground, an ink and a muted ink that both read on it (and on its
 * cards) at 4.5:1 or better, and four to six accents. A chapter takes one accent
 * ([ArtDirection]/[Chapter.accent]); accents fill shapes as they are, and whenever words are set
 * in an accent ([accentText]) or on one ([onAccent]) the colour is moved just as far as it must be
 * to keep the contrast floor.
 */
data class ExplainerPalette(
    val id: String,
    val background: Color,
    val ink: Color,
    val muted: Color,
    /** UI pieces (tiles, chips, cards) sit on this, one step off the background. */
    val card: Color,
    val accents: List<Color>,
    /** The thin caption band (tinted toward the first accent when null). */
    val band: Color? = null,
    /** This is a scene's palette on its accent (after a circle wipe): accent discs behind heroes would vanish. */
    val accentScene: Boolean = false,
) {
    val dark: Boolean get() = Contrast.luminance(background) < DARK_LUMA

    /** A hairline rim around cards. */
    val rim: Color get() = ink.copy(alpha = if (dark) 0.16f else 0.10f)

    /** Unfilled tracks (meter troughs, empty blocks, idle dots). */
    val track: Color get() = lerp(background, ink, if (dark) 0.16f else 0.11f)

    /** The caption pill and the words on it. */
    val pill: Color get() = if (dark) lerp(background, ink, 0.92f) else lerp(ink, Color.Black, 0.1f)
    val onPill: Color get() = if (dark) background else lerp(background, Color.White, 0.6f)

    val bandColor: Color get() = band ?: lerp(background, accents.first(), if (dark) 0.35f else 0.32f)

    /** Accent [i] (wrapping) as a fill. */
    fun accent(i: Int): Color = accents[i.mod(accents.size)]

    /** The same palette in a brand's colours ("#RRGGBB" accent and dark), each kept only when it reads. */
    fun branded(accentHex: String, darkHex: String): ExplainerPalette {
        val a = hex(accentHex)
        val d = hex(darkHex)?.takeIf { ratio(it, background) >= FLOOR }
        return copy(accents = listOfNotNull(a) + accents.filter { it != a }.take(if (a == null) accents.size else accents.size - 1), ink = d ?: ink)
    }

    /**
     * This palette for a scene whose ground is accent [i] itself (after a circle wipe): the dark takes
     * the accent's place, cards turn to a light tint of the accent, muted ink is darkened until it reads.
     */
    fun onAccentGround(i: Int): ExplainerPalette {
        val ground = accent(i)
        val ink = if (ratio(ink, ground) >= FLOOR) ink else io.trimio.engine.motion.recipe.Look.textOn(ground)
        return copy(
            id = "$id-on-accent", background = ground, ink = ink, muted = readable(muted, ground, ink),
            card = lerp(ground, Color.White, 0.8f), accents = listOf(ink) + accents.filterIndexed { k, _ -> k != i.mod(accents.size) }, band = band,
            accentScene = true,
        )
    }

    /** Accent [i] as ink on the background: itself when it reads at 4.5:1, else moved toward the ink. */
    fun accentText(i: Int, ground: Color = background): Color = readable(accent(i), ground, ink)

    /** Accent [i] as a body for words, and the words on it. */
    fun accentBody(i: Int): Color = io.trimio.engine.motion.recipe.Kit.readableFill(accent(i))
    fun onAccent(i: Int): Color = Look.textOn(accentBody(i))

    companion object {
        const val FLOOR = 4.5f
        private const val DARK_LUMA = 0.18f
        private const val MARGIN = 0.15f
        private const val BISECT = 18

        fun hex(s: String): Color? = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

        fun ratio(a: Color, b: Color): Float = Contrast.ratio(Contrast.luminance(a), Contrast.luminance(b))

        /** [c] on [ground], moved toward [toward] just far enough to clear the floor (with a margin). */
        fun readable(c: Color, ground: Color, toward: Color): Color {
            if (ratio(c, ground) >= FLOOR + MARGIN) return c
            var lo = 0f
            var hi = 1f
            repeat(BISECT) {
                val m = (lo + hi) / 2f
                if (ratio(lerp(c, toward, m), ground) >= FLOOR + MARGIN) hi = m else lo = m
            }
            return lerp(c, toward, hi)
        }
    }
}

/** The palette book: what [ArtDirection.palette] names. Unknown ids fall back to warm paper. */
object PaletteBook {
    private fun c(hex: Long) = Color(0xFF000000 or hex)

    val all: List<ExplainerPalette> = listOf(
        ExplainerPalette(
            "paper-warm", c(0xECE7DD), c(0x2A2622), c(0x655E55), c(0xF7F4EE),
            listOf(c(0x4F8A6E), c(0xC88A2E), c(0xD2694C), c(0x4A3F7E), c(0x2F7F86), c(0xB4546E)),
            band = c(0xE8CFA2),
        ),
        ExplainerPalette(
            "ink-night", c(0x13151B), c(0xEDEAE3), c(0x9D9BA4), c(0x1E2129),
            listOf(c(0xC8F06A), c(0xFF8A6B), c(0x7CC4FF), c(0xB49CFF), c(0xFFC857)),
        ),
        ExplainerPalette(
            "chalk", c(0x1F2B26), c(0xF1EFE6), c(0xA9B5AC), c(0x28372F),
            listOf(c(0xF4D35E), c(0xF28FAD), c(0x8EC5FC), c(0x9EE6B8), c(0xF6A55B)),
        ),
        ExplainerPalette(
            "mint", c(0xE3F0E8), c(0x14322A), c(0x4A665C), c(0xF4FAF6),
            listOf(c(0x1F8A6A), c(0xE07A5F), c(0x3D5A9E), c(0xD9A21B), c(0x8E5BB5)),
        ),
        ExplainerPalette(
            "dusk", c(0x2A2140), c(0xF6EEF5), c(0xBBADC8), c(0x352A4F),
            listOf(c(0xFF9E7A), c(0xFFD166), c(0x7FD1D9), c(0xF28CB6), c(0xB8A2FF)),
        ),
        ExplainerPalette(
            "mono", c(0xF1F1EF), c(0x151515), c(0x5C5C5C), c(0xFFFFFF),
            listOf(c(0x1A1A1A), c(0xE4572E), c(0x4F4F4F), c(0x2F5BD3)),
        ),
        ExplainerPalette(
            "sand", c(0xF3E9D7), c(0x3B2A1A), c(0x725C48), c(0xFBF5EA),
            listOf(c(0xC2703D), c(0x5B7F4E), c(0x2F6690), c(0xA63D40), c(0xD9A441)),
        ),
        ExplainerPalette(
            "plum", c(0xF4ECF1), c(0x2D1830), c(0x6A5470), c(0xFCF7FA),
            listOf(c(0x7B2D6B), c(0xD9715A), c(0x2E7D7A), c(0xC59A2D), c(0x4B4FA8)),
        ),
        ExplainerPalette(
            "slate", c(0x1B2430), c(0xE8EDF2), c(0x97A5B4), c(0x243041),
            listOf(c(0x5FB3F9), c(0xF2A65A), c(0x7ED6A8), c(0xF27E9A), c(0xC3A6FF)),
        ),
        // Illustrated news: white paper, one saturated accent, one dark.
        ExplainerPalette(
            "news-gold", c(0xFFFFFF), c(0x1B2063), c(0x626678), c(0xF5F5F7),
            listOf(c(0xF7B500), c(0xE5483B), c(0x2A9D8F), c(0xF28C28)),
        ),
        ExplainerPalette(
            "news-red", c(0xFFFFFF), c(0x14161C), c(0x5F6370), c(0xF5F5F6),
            listOf(c(0xE63946), c(0xF4A261), c(0x457B9D), c(0x2A9D8F)),
        ),
        ExplainerPalette(
            "news-teal", c(0xFFFFFF), c(0x0B2A3C), c(0x56687A), c(0xF3F6F8),
            listOf(c(0x14B8A6), c(0xF59E0B), c(0x8B5CF6), c(0xEF476F)),
        ),
        ExplainerPalette(
            "linen-blue", c(0xEAEFF4), c(0x17243A), c(0x536276), c(0xF8FAFC),
            listOf(c(0x2F6FDE), c(0xE8833A), c(0x2BA084), c(0xC2417A), c(0x6B4FD8)),
        ),
    )

    private val aliases = mapOf(
        "paper" to "paper-warm", "paper-sand" to "sand", "warm-paper" to "paper-warm", "warm" to "paper-warm", "cream" to "paper-warm",
        "night" to "ink-night", "dark" to "ink-night", "ink" to "ink-night", "noir" to "ink-night",
        "chalkboard" to "chalk", "board" to "chalk", "green" to "mint", "fresh" to "mint",
        "violet" to "dusk", "purple" to "dusk", "evening" to "dusk", "black-white" to "mono", "grey" to "mono", "gray" to "mono",
        "news" to "news-gold", "gold" to "news-gold", "yellow" to "news-gold", "red" to "news-red", "teal" to "news-teal",
        "beige" to "sand", "desert" to "sand", "berry" to "plum", "wine" to "plum", "navy" to "slate", "blue" to "linen-blue", "linen" to "linen-blue",
    )

    val ids: List<String> get() = all.map { it.id }

    fun named(id: String?): ExplainerPalette {
        val key = id?.trim()?.lowercase()?.replace(' ', '-')?.replace('_', '-').orEmpty()
        return all.firstOrNull { it.id == key } ?: all.firstOrNull { it.id == aliases[key] } ?: all.first()
    }
}
