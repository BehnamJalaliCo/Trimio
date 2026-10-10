package io.trimio.engine.autopilot

import kotlinx.serialization.Serializable

/**
 * What this creator likes, learned from their reactions (keep, regenerate, "less text", "more
 * energy") and applied to every later edit as weights on the planner's choices. Neutral is 1 for
 * weights and 0 for biases; learning moves them in small, bounded steps so one reaction never
 * swings the style.
 */
@Serializable
data class Taste(
    val recipes: Map<String, Float> = emptyMap(),
    val looks: Map<String, Float> = emptyMap(),
    val transitions: Map<String, Float> = emptyMap(),
    /** Added to every beat's energy (-0.3 … 0.3). */
    val energy: Float = 0f,
    /** How many graphics per line, relative (0.5 sparse … 1.5 dense). */
    val density: Float = 1f,
    /** Share of the piece given to full-frame graphic scenes (0.1 … 0.6). */
    val takeover: Float = 0.38f,
    /** Words per caption line, when the creator has a preference. */
    val captionWords: Int? = null,
    val captions: Boolean = true,
    val music: Boolean = true,
) {
    fun recipe(name: String) = recipes[name] ?: 1f
    fun look(name: String) = looks[name] ?: 1f
    fun transition(name: String) = transitions[name] ?: 1f

    /** The creator kept ([liked]) or rejected an edit made of [choices]. */
    fun learn(choices: Choices, liked: Boolean): Taste {
        val step = if (liked) STEP else -STEP
        fun Map<String, Float>.nudge(keys: Collection<String>) = toMutableMap().also { m ->
            keys.toSet().forEach { k -> m[k] = ((m[k] ?: 1f) * (1f + step)).coerceIn(MIN_W, MAX_W) }
        }
        return copy(
            recipes = recipes.nudge(choices.recipes),
            looks = looks.nudge(listOf(choices.look)),
            transitions = transitions.nudge(choices.transitions),
        )
    }

    /** Applies a spoken adjustment ("more energy", "fewer graphics", "no captions"). */
    fun adjust(a: Adjustment): Taste = copy(
        energy = (energy + a.energy * 0.15f).coerceIn(-0.3f, 0.3f),
        density = (density * (1f + a.density * 0.2f)).coerceIn(0.5f, 1.5f),
        takeover = (takeover + a.takeover * 0.08f).coerceIn(0.1f, 0.6f),
        captions = a.captions ?: captions,
        music = a.music ?: music,
        captionWords = a.captionWords ?: captionWords,
        looks = a.look?.let { l -> looks + (l to MAX_W) } ?: looks,
        recipes = recipes.toMutableMap().also { m ->
            a.moreOf.forEach { m[it] = ((m[it] ?: 1f) * 1.6f).coerceAtMost(MAX_W) }
            a.lessOf.forEach { m[it] = ((m[it] ?: 1f) * 0.4f).coerceAtLeast(MIN_W) }
        },
    )

    companion object {
        private const val STEP = 0.15f
        private const val MIN_W = 0.15f
        private const val MAX_W = 4f
    }
}

/** A request to change the next edit, read from the creator's words. All fields are relative. */
@Serializable
data class Adjustment(
    /** -1 calmer … +1 more energy. */
    val energy: Float = 0f,
    /** -1 fewer graphics … +1 more. */
    val density: Float = 0f,
    /** -1 more of the speaker … +1 more full-screen graphics. */
    val takeover: Float = 0f,
    val captions: Boolean? = null,
    val captionWords: Int? = null,
    val music: Boolean? = null,
    val look: String? = null,
    val moreOf: List<String> = emptyList(),
    val lessOf: List<String> = emptyList(),
)

/** What one edit chose, so a reaction to it can be learned. */
@Serializable
data class Choices(
    val seed: Long,
    val look: String,
    val music: String?,
    val recipes: List<String>,
    val transitions: List<String>,
)
