package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.TextLayoutEngine

/** A film's look, decided once: palette (in the brand's colours when given), grammar rules, reading direction, illustration style. */
internal class FilmLook(val pal: ExplainerPalette, val rules: GrammarRules, val rtl: Boolean, val persian: Boolean, val style: IllustrationStyle) {

    @Suppress("LongParameterList")
    fun hand(
        text: TextLayoutEngine, icons: IconResolver, illustrations: IllustrationSource, accent: Int, unit: Float, maxW: Float, maxH: Float, seed: Long,
        palette: ExplainerPalette = pal,
    ): Hand = Hand(text, palette, accent, rtl, unit, icons, maxW, maxH, seed).also {
        it.persian = persian
        it.illustrations = illustrations
        it.style = style
    }

    companion object {
        fun of(film: ExplainerFilm): FilmLook {
            val grammar = FilmGrammar.named(film.art.grammar)
            val lang = film.language.lowercase()
            val rtl = lang.startsWith("fa") || lang.startsWith("ar") || lang.startsWith("ur")
            val pal = PaletteBook.named(film.art.palette).branded(film.art.accent, film.art.dark)
            return FilmLook(pal, FilmRules.of(grammar), rtl, lang.startsWith("fa"), IllustrationStyle.PencilSketch)
        }
    }
}
