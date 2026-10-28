package io.trimio.engine.motion.recipe

/**
 * The style tokens a scene wears: which accent variant of the look's accents, which emphasis mark
 * and which stage grid — the micro-variation that keeps two edits of one piece from looking
 * templated. Indexes into the look's own lists; 0 is always the signature hand.
 */
data class SceneTokens(val accent: Int, val mark: Int, val grid: Int)

/**
 * How two edits of the same video stop looking templated: every device has several treatments
 * ([Recipe.variants]: layout × choreography, with the type treatment following the look) and the
 * finishing touches come in more than one hand. The choice is deterministic — a hash of the edit's
 * variety seed, the recipe and what the beat says — and weighted by what the look allows, so the
 * same seed and the same words always give the same treatment, a new seed a new one, and a style
 * never gets a treatment its rules exclude. A variety of 0 keeps everything in its signature form
 * (hand-written scores and tests stay exactly as they were). The same rule dresses the style
 * tokens themselves ([hands], [scene]): a scene wears one of two or three accent, mark and grid
 * hands, never the whole set.
 */
object Variety {
    /**
     * The treatment for a beat of [recipe]: [wanted] when the score names one (wrapped into range;
     * a treatment the look rules out falls back to the signature), else the signature form without
     * a variety seed, else a weighted, hashed pick.
     */
    fun pick(recipe: Recipe, look: Look, cue: Cue, variety: Int, wanted: Int?, salt: Int): Int {
        val n = recipe.variants
        if (n <= 1) return 0
        val weights = weights(recipe, look, cue)
        val signature = weights.indexOfFirst { it > 0f }.coerceAtLeast(0)
        if (wanted != null) return wanted.mod(n).takeIf { weights[it] > 0f } ?: signature
        if (variety == 0) return signature
        return weighted(weights, unit(mix(variety, recipe.name.hashCode(), salt)))
    }

    /** The next treatment after [v] the look allows (never the same form twice running), or [v]. */
    fun next(recipe: Recipe, look: Look, cue: Cue, v: Int): Int {
        val n = recipe.variants
        val weights = weights(recipe, look, cue)
        // Prefer a different layout, not only a different move of the same layout.
        val layouts = recipe.layouts.coerceAtLeast(1)
        val order = (1 until n).map { (v + it * layouts + it) % n } + (1 until n).map { (v + it) % n }
        return order.firstOrNull { it != v && weights[it] > 0f } ?: v
    }

    private fun weights(recipe: Recipe, look: Look, cue: Cue): List<Float> {
        val raw = recipe.variantWeights(look, cue)
        return List(recipe.variants) { i -> raw.getOrElse(i) { 1f }.coerceAtLeast(0f) }
    }

    /**
     * One of [count] hands for a finishing touch [what] (0 without a variety seed): stable for the
     * edit and the beat, varied between edits.
     */
    fun touch(cue: Cue, what: String, count: Int): Int =
        if (cue.variety == 0 || count <= 1) 0 else ((mix(cue.variety, what.hashCode(), cue.seed) ushr 1) % count)

    /**
     * The two or three hands of [count] token variants a scene may wear (never the whole set: a
     * scene stays coherent), deterministic in the edit's variety and seed. A variety of 0 keeps
     * the signature hand alone, so hand-written scores and tests stay exactly as they were.
     */
    fun hands(cue: Cue, what: String, count: Int): List<Int> {
        val n = count.coerceAtLeast(1)
        if (cue.variety == 0 || n == 1) return listOf(0)
        val take = n.coerceAtMost(TOKEN_HANDS).coerceAtLeast(MIN_HANDS)
        val start = ((mix(cue.variety, what.hashCode(), cue.seed) ushr 1) % n)
        return List(take) { (start + it) % n }
    }

    /**
     * The token micro-variation of a scene: one hand each of accent, mark and grid, drawn from the
     * scene's own [hands] — deterministic in the edit's variety and seed.
     */
    fun scene(cue: Cue, accents: Int, marks: Int, grids: Int): SceneTokens = SceneTokens(
        accent = wear(cue, "accent", accents),
        mark = wear(cue, "mark", marks),
        grid = wear(cue, "grid", grids),
    )

    private fun wear(cue: Cue, what: String, count: Int): Int {
        val options = hands(cue, what, count)
        return if (options.size == 1) options[0] else options[touch(cue, what, options.size)]
    }

    /** A stable salt from what a beat says (its words, label and items), so content steers the pick too. */
    fun salt(text: String, label: String?, items: List<String>): Int = (text + "|" + label.orEmpty() + "|" + items.joinToString("|")).hashCode()

    /** A well-spread unit float in [0, 1) from three ints, for choices that are not a weighted list. */
    fun unit(a: Int, b: Int, c: Int): Float = unit(mix(a, b, c))

    private fun weighted(weights: List<Float>, u: Float): Int {
        val total = weights.sum()
        if (total <= 0f) return 0
        var acc = 0f
        for ((i, w) in weights.withIndex()) {
            acc += w / total
            if (u < acc) return i
        }
        return weights.indexOfLast { it > 0f }.coerceAtLeast(0)
    }

    /** A well-mixed 32-bit hash of three ints (murmur-style finaliser). */
    private fun mix(a: Int, b: Int, c: Int): Int {
        var h = a * GOLDEN + b
        h = (h xor (h ushr 16)) * M1
        h = (h xor c) * M2
        h = h xor (h ushr 13)
        h *= M1
        return h xor (h ushr 16)
    }

    private fun unit(h: Int): Float = ((h ushr 8) and 0xFFFFFF) / 16_777_216f

    private const val GOLDEN = -0x61c88647
    private const val M1 = -0x7a143595
    private const val M2 = -0x3d4d51cb
    private const val TOKEN_HANDS = 3
    private const val MIN_HANDS = 2
}
