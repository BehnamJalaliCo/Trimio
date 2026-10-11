package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import io.trimio.engine.motion.Contrast
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Grade
import io.trimio.engine.motion.Guard
import io.trimio.engine.motion.TypeSpec

/**
 * The design language a piece is rendered in: palette, type voices and texture. Recipes never
 * hard-code a colour or a font; they ask the look. The 28 styles ([io.trimio.engine.motion.style.StyleBook])
 * each carry one; [named] finds them by the score's `look`.
 */
data class Look(
    val name: String,
    /** Frame background when there is no footage. */
    val canvas: Color,
    /** Colours of the living background gradient (first is the base). */
    val aurora: List<Color>,
    val ink: Color,
    val muted: Color,
    /** The emphasis block colour and the text on it. */
    val accent: Color,
    val onAccent: Color,
    /** A second voice for numbers, arrows and "up" moves. */
    val positive: Color,
    val negative: Color,
    /** Hot colour for impacts and calls to action. */
    val hot: Color,
    /** Glass/card surface over the background. */
    val card: Color,
    val cardRim: Color,
    val headline: Voice,
    /** Spoken captions: heavy but readable for long runs of words. */
    val caption: Voice,
    val body: Voice,
    val number: Voice,
    val label: Voice,
    /** Film grain amount; 0 by default: grain costs bitrate and reads as noise on phones. */
    val grain: Float,
    val vignette: Float,
    /** Corner radius scale for cards and blocks (0 = Swiss sharp). */
    val roundness: Float,
    /** How emphasised words are marked by default. */
    val mark: Mark = Mark.Block,
    /** Text over footage gets a soft shadow so it never depends on the picture. */
    val textShadow: Color = Color.Black.copy(alpha = 0.55f),
    /**
     * How the look keeps text legible over any footage (see [Guard]): dark looks ring light ink
     * with a soft dark halo where the picture is bright; the paper look sets its dark ink on
     * paper plates where the picture is dark — it stays itself instead of inverting.
     */
    val guard: Guard = Guard(Guard.Style.Halo, light = Color(0xFFF2EDE4), dark = Color(0xFF0B0A09)),
    /**
     * The stage as a shader family ("glow", "leaks", "bokeh", "dust", "liquid"; see
     * [io.trimio.engine.motion.ShaderFamily]) — what a style's `background` names. Null keeps the
     * drifting aurora. Cards on a shader stage sit on frosted glass ([Stages.glass]).
     */
    val backdrop: String? = null,
    /** A distortion of the picture (footage, stages; never text): "halation" for film, "lens", "chromatic"; or null. */
    val lens: String? = null,
    /** The footage grade; null keeps the compiler's grade for the original three looks. */
    val grade: Grade? = null,
    /** The curve scene moves (whip, zoom) settle with: the style's easing family. */
    val settle: Easing = Easing.ExpoOut,
    /** Latin letters in headlines set in capitals (Persian has no case and is never touched). */
    val caps: Boolean = false,
) {
    /**
     * The same look with another accent ("#D7FF3A"): emphasis blocks, numbers and rings follow it;
     * text on the accent is dark or light, whichever reads better on it ([textOn]).
     */
    fun withAccent(hex: String?): Look {
        val rgb = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return this
        val c = Color(0xFF000000 or rgb)
        return copy(accent = c, onAccent = textOn(c), positive = if (positive == accent) c else positive)
    }

    /** Latin letters of a headline in capitals when the look sets them so. */
    fun cased(text: String): String = if (caps) text.map { if (it in 'a'..'z') it.uppercaseChar() else it }.joinToString("") else text

    data class Voice(val role: BrandFonts.Role, val weight: Int, val lineHeight: Float = 1.1f, val tracking: Float = 0f) {
        fun at(size: Float, weight: Int = this.weight) = TypeSpec(role, weight, size, lineHeight, tracking)
    }

    enum class Mark { Block, Ink, Underline, Circle }

    companion object {
        /** The signature look: warm black, bone ink, lime emphasis, ember heat. */
        val Noir = Look(
            name = "noir",
            canvas = Color(0xFF0B0A09),
            aurora = listOf(Color(0xFF0B0A09), Color(0xFF2E140A), Color(0xFF16210B), Color(0xFF221228)),
            ink = Color(0xFFF2EDE4),
            muted = Color(0xFF8F887D),
            accent = Color(0xFFD7FF3A),
            onAccent = Color(0xFF0B0A09),
            positive = Color(0xFFD7FF3A),
            negative = Color(0xFFFF5B2E),
            hot = Color(0xFFFF5B2E),
            card = Color(0x1FFFF6EB),
            cardRim = Color(0x38FFFFFF),
            headline = Voice(BrandFonts.Role.Display, 1000, lineHeight = 1.04f),
            caption = Voice(BrandFonts.Role.Expressive, 900, lineHeight = 1.28f),
            body = Voice(BrandFonts.Role.Expressive, 800, lineHeight = 1.3f),
            number = Voice(BrandFonts.Role.Accent, 900, lineHeight = 1f),
            label = Voice(BrandFonts.Role.Accent, 600, lineHeight = 1.2f, tracking = 0.16f),
            grain = 0f,
            vignette = 0.5f,
            roundness = 1f,
        )

        /** Swiss paper: bone white, black ink, lime blocks, sharp corners, a grid underneath. */
        val Paper = Look(
            name = "paper",
            canvas = Color(0xFFEFEDE6),
            aurora = listOf(Color(0xFFEFEDE6), Color(0xFFE6E2D6), Color(0xFFF4F1E8)),
            ink = Color(0xFF111111),
            muted = Color(0xFF6E6B64),
            accent = Color(0xFFD7FF3A),
            onAccent = Color(0xFF111111),
            positive = Color(0xFF111111),
            negative = Color(0xFFE5401A),
            hot = Color(0xFFFF5B2E),
            card = Color(0xFFFFFFFF),
            cardRim = Color(0x24111111),
            headline = Voice(BrandFonts.Role.Display, 1000, lineHeight = 1.0f),
            caption = Voice(BrandFonts.Role.Ui, 900, lineHeight = 1.3f),
            body = Voice(BrandFonts.Role.Ui, 700, lineHeight = 1.35f),
            number = Voice(BrandFonts.Role.Accent, 900, lineHeight = 1f),
            label = Voice(BrandFonts.Role.Accent, 600, lineHeight = 1.2f, tracking = 0.16f),
            grain = 0f,
            vignette = 0.12f,
            roundness = 0f,
            textShadow = Color.Transparent,
            guard = Guard(Guard.Style.Plate, light = Color(0xFFEFEDE6), dark = Color(0xFF111111), radius = 0.06f),
        )

        /** Cool, luminous and calm: iridescent light on deep blue, soft blur entrances. */
        val Lumen = Look(
            name = "lumen",
            canvas = Color(0xFF070B14),
            aurora = listOf(Color(0xFF070B14), Color(0xFF123A5C), Color(0xFF3B2A78), Color(0xFF5C2550)),
            ink = Color(0xFFF4F6FF),
            muted = Color(0xFF8A93B0),
            accent = Color(0xFF9BE7FF),
            onAccent = Color(0xFF071019),
            positive = Color(0xFF9BE7FF),
            negative = Color(0xFFFFC6E0),
            hot = Color(0xFFB7A6FF),
            card = Color(0x1AFFFFFF),
            cardRim = Color(0x40FFFFFF),
            headline = Voice(BrandFonts.Role.Expressive, 900, lineHeight = 1.15f),
            caption = Voice(BrandFonts.Role.Expressive, 800, lineHeight = 1.3f),
            body = Voice(BrandFonts.Role.Ui, 600, lineHeight = 1.4f),
            number = Voice(BrandFonts.Role.Accent, 700, lineHeight = 1f),
            label = Voice(BrandFonts.Role.Accent, 500, lineHeight = 1.2f, tracking = 0.18f),
            grain = 0f,
            vignette = 0.45f,
            roundness = 1.4f,
            mark = Mark.Ink,
            guard = Guard(Guard.Style.Halo, light = Color(0xFFF4F6FF), dark = Color(0xFF070B14)),
        )

        val all = listOf(Noir, Paper, Lumen)

        /** Accent variants per look that keep its character (the first is the look's own). */
        val accents: Map<String, List<String>> = mapOf(
            "noir" to listOf("#D7FF3A", "#FF6B3D", "#3AE0FF", "#FFC93A", "#B79CFF"),
            "paper" to listOf("#D7FF3A", "#FF7A45", "#4C7DFF", "#FF5FA8"),
            "lumen" to listOf("#9BE7FF", "#B7A6FF", "#7CFFC4", "#FFC6E0"),
        )

        /** The look of the original three or of any of the 28 styles; noir when nothing matches. */
        fun named(name: String?): Look =
            all.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: io.trimio.engine.motion.style.StyleBook.of(name)?.look ?: Noir

        private val DARK_TEXT = Color(0xFF0B0A09)
        private val LIGHT_TEXT = Color(0xFFF7F4EE)

        /** Near-black or bone text, whichever has more contrast on [c] (a bright red takes black, a deep one bone). */
        fun textOn(c: Color): Color {
            val l = Contrast.luminance(c)
            return if (Contrast.ratio(l, Contrast.luminance(DARK_TEXT)) >= Contrast.ratio(l, Contrast.luminance(LIGHT_TEXT))) DARK_TEXT else LIGHT_TEXT
        }
    }
}
