package io.trimio.engine.autopilot

import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage

/**
 * The planner's choices as a [StyleLanguage] tilts them. The planner keeps its own options (by
 * energy band, by mood); the style multiplies them by its taste and adds its own vocabulary,
 * rarer than the planner's own, where the moment allows it. Nothing here is per style: every
 * style speaks through the same weights.
 */
internal class StyleChoices(val style: StyleLanguage, private val taste: Taste) {

    /**
     * The creator's taste in the style's cadence: its share of full-frame graphics, and its
     * density — never sparser than the rhythm floor (a calm style is calmer in how things move,
     * not in leaving the speaker alone longer).
     */
    val paced: Taste = taste.copy(
        takeover = (taste.takeover * style.rhythm.takeover).coerceIn(MIN_TAKEOVER, MAX_TAKEOVER),
        density = (taste.density * maxOf(1f, style.rhythm.density)).coerceIn(MIN_DENSITY, MAX_DENSITY),
    )

    /** A headline's entrance options: the band's own (zero weights dropped) plus the style's entrances that suit the band. */
    fun entrances(band: List<Pair<String, Float>>, e: Float): List<Pair<String, Float>> {
        val live = band.filter { it.second > 0f }
        val suits = when {
            e >= HIGH -> HIGH_ENTRANCES
            e >= MID -> MID_ENTRANCES
            else -> LOW_ENTRANCES
        }
        val own = style.motion.entrances.keys.filter { r -> r in suits && live.none { it.first == r } }.map { it to EXTRA }
        return (live + own).map { (r, w) -> r to w * style.motion.entrance(r) }
    }

    /** A beat's energy, leaning a quarter of the way toward the style's own. */
    fun lean(e: Float): Float = if (style.motion.energy < 0f) e else e + (style.motion.energy - e) * LEAN

    /** An intense line may get a push-in, a calm one a locked-off frame: as often as the style's camera habit says. */
    fun camera(energy: Float, rng: Rng): String? = when {
        energy >= HIGH -> rng.weighted(listOf<Pair<String?, Float>>("push-in" to style.motion.push, null to 1f))
        energy <= CALM -> rng.weighted(listOf<Pair<String?, Float>>("still" to style.motion.still, null to 1f))
        else -> null
    }

    /** Transitions into a full-frame graphic: the mood's, plus the style's own entrance vocabulary. */
    fun into(base: List<Pair<String, Float>>) = styled(base, style.motion.enter, style.motion::into)

    /** Transitions back to the speaker: the planner's, plus the style's exit vocabulary. */
    fun outOf(base: List<Pair<String, Float>>) = styled(base, style.motion.exit, style.motion::outOf)

    private fun styled(base: List<Pair<String, Float>>, own: Map<String, Float>, weigh: (String) -> Float): List<Pair<String, Float>> =
        (base + own.keys.filter { t -> t in SCENE_TRANSITIONS && base.none { it.first == t } }.map { it to EXTRA })
            .map { (t, w) -> t to w * weigh(t) * taste.transition(t) }

    companion object {
        /**
         * The style: one the brief asks for by name or style word ([StyleBook.asked]); otherwise one
         * answering the brief's plain look word (dark, light, calm) when it has one. Among those,
         * the ones that fit the domain go first, and the seed picks by fit to domain and mood and
         * the creator's taste (a look they liked lifts the styles of its tone).
         */
        fun choose(brief: String, u: Understanding, mood: String, taste: Taste, rng: Rng, notes: MutableList<String>): StyleLanguage {
            val asked = StyleBook.asked(brief)
            val tone = u.brief.look.takeIf { it != "auto" && (it in StyleBook.TONES || StyleBook.of(it) != null) }
            val pool = asked.ifEmpty { StyleBook.all.filter { s -> tone == null || s.answers(tone) } }.ifEmpty { StyleBook.all }
            val fitting = pool.filter { (it.fit.domains[u.domain] ?: 0f) >= FITTING }.ifEmpty { pool }
            fun liked(s: StyleLanguage) = taste.look(s.id) * s.tones.maxOf { t -> if (t == s.id) 1f else taste.look(t) }
            val picked = rng.weighted(fitting.map { s -> s to (s.fitness(u.domain, mood) + FIT_FLOOR) * liked(s) })
            notes += "style ${picked.id}: " + when {
                asked.isNotEmpty() -> "the brief asks for it (${asked.joinToString { it.id }})"
                tone != null -> "answers «$tone», fits ${u.domain}/$mood"
                else -> "fits ${u.domain}/$mood"
            }
            return picked
        }

        private const val MIN_TAKEOVER = 0.1f
        private const val MAX_TAKEOVER = 0.6f
        private const val MIN_DENSITY = 0.5f
        private const val MAX_DENSITY = 1.5f
        private const val HIGH = 0.8f
        private const val MID = 0.5f
        private const val CALM = 0.35f

        /** Text entrances that suit each energy band, for a style's own vocabulary to join. */
        private val HIGH_ENTRANCES = setOf("slam", "stack", "spread", "glitch", "flip")
        private val MID_ENTRANCES = setOf("mask-rise", "flip", "type-on", "slam", "spread", "glitch", "stack", "blur-in")
        private val LOW_ENTRANCES = setOf("blur-in", "mask-rise", "type-on", "spread", "flip")
        private val SCENE_TRANSITIONS = setOf("cut", "whip", "zoom", "flash", "leak")

        /** A move only the style brings, against the planner's favourite (1). */
        private const val EXTRA = 0.45f
        private const val LEAN = 0.25f

        /** A style fits a domain from this weight; below it, it is a fallback. */
        private const val FITTING = 0.5f
        private const val FIT_FLOOR = 0.02f
    }
}
