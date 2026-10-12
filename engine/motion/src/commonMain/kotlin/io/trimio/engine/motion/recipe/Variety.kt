package io.trimio.engine.motion.recipe

/**
 * How two edits of the same video stop looking templated: every device has several treatments
 * ([Recipe.variants]) and the finishing touches (the hand of an underline, the shape of a loop)
 * come in more than one hand. The choice is deterministic — a hash of the edit's variety seed,
 * the recipe and what the beat says — and weighted by what the look allows, so the same seed and
 * the same words always give the same treatment, a new seed a new one, and a style never gets a
 * treatment its rules exclude. A variety of 0 keeps everything in its signature form (hand-written
 * scores and tests stay exactly as they were).
 */
object Variety {
    /**
     * The treatment for a beat of [recipe]: [wanted] when the score names one (wrapped into range),
     * else the signature form without a variety seed, else a weighted, hashed pick.
     */
    fun pick(recipe: Recipe, look: Look, cue: Cue, variety: Int, wanted: Int?, salt: Int): Int {
        val n = recipe.variants
        if (n <= 1) return 0
        val weights = recipe.variantWeights(look, cue).take(n).map { it.coerceAtLeast(0f) }
        // The first treatment the look allows: what a score without variety, or a ruled-out wish, gets.
        val signature = weights.indexOfFirst { it > 0f }.coerceAtLeast(0)
        wanted?.let { w -> return w.mod(n).takeIf { weights.getOrElse(it) { 1f } > 0f } ?: signature }
        if (variety == 0) return signature
        return weighted(weights, unit(mix(variety, recipe.name.hashCode(), salt)))
    }

    /**
     * One of [count] hands for a finishing touch [what] (0 without a variety seed): stable for
     * the edit, varied between edits.
     */
    fun touch(cue: Cue, what: String, count: Int): Int = if (cue.variety == 0 || count <= 1) 0 else (mix(cue.variety, what.hashCode(), 0) ushr 1) % count

    /** A stable salt from what a beat says (its words, label and items), so content steers the pick too. */
    fun salt(text: String, label: String?, items: List<String>): Int = (text + "|" + label.orEmpty() + "|" + items.joinToString("|")).hashCode()

    private fun weighted(weights: List<Float>, u: Float): Int {
        val total = weights.sum()
        if (total <= 0f) return 0
        var acc = 0f
        for ((i, w) in weights.withIndex()) {
            acc += w / total
            if (u < acc) return i
        }
        return weights.indexOfLast { it > 0f }
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
}
