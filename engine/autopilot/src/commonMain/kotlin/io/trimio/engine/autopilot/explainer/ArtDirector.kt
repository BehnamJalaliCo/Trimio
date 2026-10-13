package io.trimio.engine.autopilot.explainer

import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Rng
import io.trimio.engine.motion.explainer.ArtDirection

/**
 * The film's look, from what it is about and the seed: a palette, a layout grammar, a chapter
 * mark and a caption treatment. The topic sets the odds (a finance piece leans to deep greens and
 * graphite, health to mint and sky, history to paper and slate), the seed draws from them, and
 * the brief's own words («تیره», "paper", «بدون زیرنویس») win over both. A thousand voices: a
 * thousand looks.
 */
object ArtDirector {

    /** Palettes of the stage's palette book; light ones first. */
    val LIGHT = listOf("paper-sand", "paper-mint", "paper-rose", "paper-sky", "sun-cream")
    val DARK = listOf("ink-night", "ink-forest", "ink-plum", "chalk-slate", "mono-graphite")
    val PALETTES = LIGHT + DARK
    val LAYOUTS = listOf("header-top", "header-left", "center-stack", "split", "wide-margin")
    val MARKS = listOf("dashes", "numerals", "rule", "dots", "tab")
    val CAPTIONS = listOf("band-pill", "plain-lower", "underline", "box-top")
    const val NO_CAPTIONS = "none"

    /** How many accents a palette has: neighbouring chapters take different ones. */
    const val ACCENTS = 4

    /** Palettes each field leans to (three times as likely as the rest). */
    private val AFFINITY = mapOf(
        "tech" to listOf("paper-sand", "ink-night", "mono-graphite", "chalk-slate"),
        "ai" to listOf("paper-sand", "ink-night", "ink-plum", "mono-graphite"),
        "software" to listOf("paper-sky", "ink-night", "mono-graphite", "chalk-slate"),
        "finance" to listOf("ink-forest", "paper-mint", "mono-graphite", "sun-cream"),
        "crypto" to listOf("ink-forest", "ink-night", "mono-graphite"),
        "business" to listOf("paper-sand", "ink-forest", "mono-graphite", "paper-sky"),
        "health" to listOf("paper-mint", "paper-sky", "sun-cream", "ink-forest"),
        "medical" to listOf("paper-mint", "paper-sky", "chalk-slate"),
        "fitness" to listOf("sun-cream", "ink-plum", "paper-mint"),
        "education" to listOf("paper-sand", "chalk-slate", "paper-rose", "paper-sky"),
        "news" to listOf("paper-sand", "chalk-slate", "mono-graphite"),
        "food" to listOf("sun-cream", "paper-rose", "paper-sand"),
        "travel" to listOf("paper-sky", "sun-cream", "ink-forest"),
        "lifestyle" to listOf("paper-rose", "sun-cream", "ink-plum"),
    )

    fun choose(n: Narration, seed: Long): ArtDirection {
        val rng = Rng(seed).fork(SALT)
        val brief = RulesUnderstander.brief(n.prompt)
        val pool = when (brief.look) {
            "noir" -> DARK
            "paper", "lumen" -> LIGHT
            else -> PALETTES
        }
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
