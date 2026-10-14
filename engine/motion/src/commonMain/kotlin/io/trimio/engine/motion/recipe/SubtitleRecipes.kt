package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import io.trimio.engine.motion.Decoration
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Shape
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.fill

/**
 * The subtitle styles a creator picks for a subtitles-only edit (a YouTube video, or a reel with its silences cut and
 * nothing else on screen) — docs/research/SUBTITLES.md. Each is one recipe with its own face from the app's brand
 * fonts and its own treatment, from a calm two-line subtitle to word-by-word pop captions.
 */
enum class SubtitleStyle(val id: String, val recipe: String, val maxWords: Int, val faName: String, val enName: String) {
    /** Two calm lines, a sentence at a time, fading: made to be read for an hour (YouTube's default). */
    Classic("classic", "subtitles", 12, "کلاسیک", "Classic"),

    /** Each line on a dark rounded box, the platforms' own caption look: readable on any picture. */
    Box("box", "sub-box", 8, "جعبه‌ای", "Box"),

    /** The whole line up, each word taking the accent colour as it is said. */
    Karaoke("karaoke", "sub-karaoke", 7, "کارائوکه", "Karaoke"),

    /** Big, heavy, outlined: one to three words at a time, each popping in as it is said, the word being said in colour. */
    Pop("pop", "sub-pop", 3, "پاپ درشت", "Bold pop"),

    /** A short line with the word being said on an accent block that moves with the voice. */
    Highlight("highlight", "sub-highlight", 5, "هایلایت", "Highlight"),

    /** Small, light and low: out of the way of the picture. */
    Minimal("minimal", "sub-minimal", 10, "مینیمال", "Minimal"),
    ;

    companion object {
        fun of(id: String?): SubtitleStyle? = entries.firstOrNull { it.id == id }
    }
}

object SubtitleRecipes {
    /** A 16:9 frame's caption band is far wider than tall; a 9:16 one is not. */
    private const val WIDE = 6f

    /** Reading size: a share of the band's width on a tall frame; on a wide one, a share of its height up to [wideMax] px. */
    private fun readable(cue: Cue, tall: Float, wideMax: Float): Float =
        if (cue.width > cue.height * WIDE) minOf(cue.height * 0.42f, wideMax) else minOf(cue.width * tall, cue.height * 0.36f)

    private fun fadeIn(cue: Cue, lift: Float = 0f) =
        TextAnimator(TextUnit.All, UnitState(opacity = 0f, dy = lift), at = cue.at - 0.08f, duration = 0.16f, ease = Easing.ExpoOut)

    private fun fadeOut(cue: Cue) =
        TextAnimator(TextUnit.All, UnitState(opacity = 0f), at = cue.out - 0.12f, duration = 0.12f, ease = Easing.SineInOut, shape = Shape.Out)

    /** When each word is said, and when the next one starts (the last holds to the cue's end). */
    private fun spans(cue: Cue, n: Int): List<Pair<Float, Float>> {
        val starts = Craft.wordStarts(cue, n, 0.18f).map { it + Craft.LEAD }
        return starts.mapIndexed { i, s -> s to (starts.getOrNull(i + 1) ?: cue.out) }
    }

    /** Each line on a dark rounded box (the box gives the contrast: no halo on top). */
    val Box = object : Recipe("sub-box", Kind.Text) {
        override val preferredHeight = 0.13f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, Look.Voice(BrandFonts.Role.Ui, 700, lineHeight = 1.5f), readable(cue, 0.07f, 54f), cue.width * 0.86f, cue.height, maxLines = 2)
            val n = cue.words.size
            val box = Decoration.Block(0 until n, cue.at - 0.1f, Color(0xC4000000), Color.White, duration = 0.01f, padX = 0.4f, padTop = 0.14f, padBottom = 0.18f, radius = 0.24f)
            val node = Craft.text(cue.copy(emphasis = emptySet()), f.type, Color.White, look, listOf(fadeIn(cue, lift = 0.18f), fadeOut(cue)), listOf(box), name = name, shadowed = false)
            return Built(listOf(Craft.onBody(node)))
        }
    }

    /** The line up at once; each word takes the accent as it is said, and keeps it. */
    val Karaoke = object : Recipe("sub-karaoke", Kind.Text) {
        override val preferredHeight = 0.14f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, Look.Voice(BrandFonts.Role.Expressive, 900, lineHeight = 1.32f), readable(cue, 0.082f, 62f), cue.width * 0.92f, cue.height, maxLines = 2)
            val inks = spans(cue, cue.words.size).mapIndexed { i, (start, _) -> Decoration.Ink(i..i, start - 0.04f, look.accent, duration = 0.1f) }
            val node = Craft.text(cue.copy(emphasis = emptySet()), f.type, look.ink, look, listOf(fadeIn(cue, lift = 0.12f), fadeOut(cue)), inks, name = name)
            return Built(listOf(node))
        }
    }

    /** One to three heavy outlined words, each popping in as it is said; the word being said in the accent. */
    val Pop = object : Recipe("sub-pop", Kind.Text) {
        override val preferredHeight = 0.18f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, Look.Voice(BrandFonts.Role.Display, 1000, lineHeight = 1.1f), readable(cue, 0.118f, 92f), cue.width * 0.9f, cue.height, maxLines = 2)
            val n = cue.words.size
            val said = spans(cue, n)
            val starts = said.map { it.first - Craft.LEAD }
            val pop = TextAnimator(TextUnit.Word, UnitState(scale = 0.62f, opacity = 0f, dy = 0.12f), at = cue.at, duration = 0.3f, ease = Easing.Spring(0.5f, 2.4f), times = starts)
            val now = said.mapIndexed { i, (start, next) -> Decoration.Ink(i..i, start - 0.04f, look.accent, duration = 0.06f, until = next - 0.02f) }
            val exit = TextAnimator(TextUnit.All, UnitState(scale = 0.9f, opacity = 0f), at = cue.out - 0.1f, duration = 0.1f, ease = Easing.Exit, shape = Shape.Out)
            val node = Craft.text(cue.copy(emphasis = emptySet()), f.type, Color.White, look, listOf(pop, exit), now, name = name, stroke = Stroke(Color.Black.fill(), f.type.size * 0.07f))
            return Built(listOf(node))
        }
    }

    /** A short line; the word being said sits on an accent block that moves on with the voice. */
    val Highlight = object : Recipe("sub-highlight", Kind.Text) {
        override val preferredHeight = 0.14f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, Look.Voice(BrandFonts.Role.Accent, 900, lineHeight = 1.45f), readable(cue, 0.088f, 64f), cue.width * 0.9f, cue.height, maxLines = 2)
            val blocks = spans(cue, cue.words.size).mapIndexed { i, (start, next) ->
                Decoration.Block(i..i, start - 0.05f, look.accent, look.onAccent, duration = 0.08f, padX = 0.16f, padTop = 0.06f, padBottom = 0.1f, radius = 0.2f, until = next - 0.02f)
            }
            val node = Craft.text(cue.copy(emphasis = emptySet()), f.type, look.ink, look, listOf(fadeIn(cue, lift = 0.15f), fadeOut(cue)), blocks, name = name)
            return Built(listOf(node))
        }
    }

    /** Small, light and low: the subtitle steps back from the picture. */
    val Minimal = object : Recipe("sub-minimal", Kind.Text) {
        override val preferredHeight = 0.11f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, Look.Voice(BrandFonts.Role.Accent, 500, lineHeight = 1.35f), readable(cue, 0.056f, 44f), cue.width * 0.8f, cue.height, maxLines = 2)
            val node = Craft.text(cue.copy(emphasis = emptySet()), f.type, Craft.voice(cue, look), look, listOf(fadeIn(cue), fadeOut(cue)), name = name)
            return Built(listOf(node))
        }
    }

    val all: List<Recipe> = listOf(Box, Karaoke, Pop, Highlight, Minimal)
}
