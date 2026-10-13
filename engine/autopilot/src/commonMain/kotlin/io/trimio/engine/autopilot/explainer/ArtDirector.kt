package io.trimio.engine.autopilot.explainer

import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Rng
import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.CaptionStyle
import io.trimio.engine.motion.explainer.ChapterMark
import io.trimio.engine.motion.explainer.PaletteBook
import io.trimio.engine.motion.explainer.StageGrammar

/**
 * The film's look, from what it is about and the seed: a palette, a layout grammar, a chapter
 * mark and a caption treatment. The topic sets the odds (a finance piece leans to deep greens and
 * graphite, health to mint and sky, history to paper and slate), the seed draws from them, and
 * the brief's own words («تیره», "paper", «بدون زیرنویس») win over both. A thousand voices: a
 * thousand looks.
 */
object ArtDirector {

    /** The stage's palette book ([PaletteBook]): light grounds and dark ones. */
    val LIGHT: List<String> get() = PaletteBook.all.filter { !it.dark }.map { it.id }
    val DARK: List<String> get() = PaletteBook.all.filter { it.dark }.map { it.id }
    val PALETTES: List<String> get() = PaletteBook.ids

    /** The stage's layout grammars, chapter marks and caption treatments (the reference's own first). */
    val LAYOUTS: List<String> get() = StageGrammar.entries.map { it.id }
    val MARKS: List<String> get() = ChapterMark.entries.filter { it != ChapterMark.None }.map { it.id }
    val CAPTIONS: List<String> get() = CaptionStyle.entries.filter { it != CaptionStyle.None }.map { it.id }
    val NO_CAPTIONS: String get() = CaptionStyle.None.id

    /** How many accents a palette has: neighbouring chapters take different ones. */
    const val ACCENTS = 4

    /** Palettes each field leans to (three times as likely as the rest). */
    private val AFFINITY = mapOf(
        "tech" to listOf("paper-warm", "ink-night", "mono", "slate"),
        "ai" to listOf("paper-warm", "ink-night", "dusk", "mono"),
        "software" to listOf("linen-blue", "ink-night", "mono", "slate"),
        "finance" to listOf("mint", "slate", "mono", "sand"),
        "crypto" to listOf("ink-night", "slate", "chalk"),
        "business" to listOf("paper-warm", "linen-blue", "mono", "slate"),
        "health" to listOf("mint", "linen-blue", "sand", "plum"),
        "medical" to listOf("mint", "linen-blue", "slate"),
        "fitness" to listOf("sand", "dusk", "mint"),
        "education" to listOf("paper-warm", "chalk", "plum", "linen-blue"),
        "news" to listOf("paper-warm", "slate", "mono"),
        "food" to listOf("sand", "plum", "paper-warm"),
        "travel" to listOf("linen-blue", "sand", "chalk"),
        "lifestyle" to listOf("plum", "sand", "dusk"),
    )

    fun choose(n: Narration, seed: Long): ArtDirection {
        val rng = Rng(seed).fork(SALT)
        val brief = RulesUnderstander.brief(n.prompt)
        val pool = when (brief.look) {
            "noir" -> DARK
            "paper", "lumen" -> LIGHT
            else -> PALETTES
        }.ifEmpty { PALETTES }
        val leaning = AFFINITY[n.understanding.domain].orEmpty()
        val palette = rng.weighted(pool.map { it to if (it in leaning) LEAN else 1f })
        val layout = rng.weighted(LAYOUTS.mapIndexed { i, l -> l to if (i == 0) FIRST else 1f })
        val mark = rng.pick(MARKS)
        val captions = if (brief.captions == "none") NO_CAPTIONS else rng.weighted(CAPTIONS.mapIndexed { i, c -> c to if (i == 0) FIRST else 1f })
        return ArtDirection(palette, layout, mark, captions, seed)
    }

    /** Accents for [count] chapters: the seed's start, and never the same twice in a row. */
    fun accents(count: Int, seed: Long): List<Int> {
        val rng = Rng(seed).fork(SALT + 1)
        var a = (rng.next() * ACCENTS).toInt()
        return List(count) { i ->
            if (i > 0) a = (a + 1 + (rng.next() * (ACCENTS - 1)).toInt()) % ACCENTS
            a
        }
    }

    /** The music under the narration: calm by default, the field and the seed choose; none when the brief says so. */
    fun music(n: Narration, seed: Long): MusicMood? {
        val brief = RulesUnderstander.brief(n.prompt)
        if (brief.music == "none") return null
        MusicMood.fromId(brief.music)?.let { return it }
        val rng = Rng(seed).fork(SALT + 2)
        val options = when (n.understanding.domain) {
            "finance", "crypto", "business", "news" -> listOf(MusicMood.Corporate to 2f, MusicMood.Chill to 1f, MusicMood.Cinematic to 1f)
            "health", "medical", "lifestyle", "food", "travel" -> listOf(MusicMood.Chill to 2f, MusicMood.Uplifting to 1.5f)
            "fitness", "sports" -> listOf(MusicMood.Uplifting to 2f, MusicMood.Energetic to 1f)
            else -> listOf(MusicMood.Chill to 2f, MusicMood.Corporate to 1f, MusicMood.Cinematic to 1f, MusicMood.Uplifting to 1f)
        }
        return rng.weighted(options)
    }

    private const val SALT = 81
    private const val LEAN = 3f
    private const val FIRST = 1.6f
}
