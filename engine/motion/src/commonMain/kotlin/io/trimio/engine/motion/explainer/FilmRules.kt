package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Easing

/** How one scene arrives over the previous one. */
enum class SceneTransition(val id: String) {
    /** The stage clears and the next heading arrives (diagram chapters). */
    Cut("cut"),

    /** A giant accent circle wipes up from the bottom and becomes the next scene's background. */
    Circle("circle"),

    /** The next scene pushes the previous one aside. */
    Push("push"),

    /** The previous scene peels away from its bottom corner like a page. */
    Peel("peel"),
    ;

    companion object {
        fun named(id: String?): SceneTransition? {
            val key = id?.trim()?.lowercase()?.replace('_', '-').orEmpty()
            return entries.firstOrNull { it.id == key } ?: when (key) {
                "wipe", "circle-wipe", "iris", "mask" -> Circle
                "slide", "lateral", "push-left", "push-right" -> Push
                "curl", "page", "page-curl", "page-turn" -> Peel
                "hard", "none", "clear" -> Cut
                else -> null
            }
        }
    }
}

/**
 * A film grammar's design rules, as data the stage applies and the tests enforce (the owner's
 * principle: design knowledge lives in code, not only in documents). Times are seconds; sizes are
 * at a 1080-px short side.
 */
data class GrammarRules(
    val grammar: FilmGrammar,
    /** How long a chapter (a scene) should last; outside this the stage notes it for the screenwriter. */
    val sceneMin: Float,
    val sceneMax: Float,
    /** The longest the picture may stand still. */
    val maxStill: Float,
    /** Graphic devices on stage at once (besides a headline and a hero in news scenes). */
    val maxDevices: Int,
    /** Elements on a stage at once, all kinds together. */
    val maxLive: Int,
    /** How scenes follow each other: the first is the default; a news film cycles through them. */
    val transitions: List<SceneTransition>,
    /** Colours a scene uses besides its ground and ink: one accent (news adds one dark). */
    val accentsPerScene: Int,
    /** Air between elements on the stage. */
    val gap: Float,
    /** How elements arrive and how they glide when the stage reflows. */
    val enter: Easing,
    val glide: Easing,
    /** The hero illustration may cross the frame's bottom edge (it rises from below it). */
    val heroBleeds: Boolean,
    /** Pieces are drawn this much larger than the base unit (tall and wide frames). */
    val scaleTall: Float,
    val scaleWide: Float,
) {
    /** The transition into scene [index] (≥ 1): the one asked for when the grammar allows it, else its own cycle. */
    fun transitionInto(index: Int, asked: String): SceneTransition {
        val wanted = SceneTransition.named(asked)?.takeIf { it in transitions }
        return wanted ?: transitions[(index - 1).mod(transitions.size)]
    }
}

object FilmRules {
    /** Reference 1: a calm stage per chapter that builds; hard cuts only between chapters. */
    val Diagram = GrammarRules(
        grammar = FilmGrammar.DiagramChapters,
        sceneMin = 5f, sceneMax = 40f, maxStill = 6f, maxDevices = 4, maxLive = 4,
        transitions = listOf(SceneTransition.Cut), accentsPerScene = 1, gap = 64f,
        enter = Easing.ExpoOut, glide = Easing.Bezier(0.45f, 0f, 0.15f, 1f), heroBleeds = false,
        scaleTall = 1.16f, scaleWide = 1.1f,
    )

    /** Reference 2: one fast scene per sentence — headline, hero, one device — moving on every second or two. */
    val News = GrammarRules(
        grammar = FilmGrammar.IllustratedNews,
        sceneMin = 1.5f, sceneMax = 3.6f, maxStill = 2.5f, maxDevices = 1, maxLive = 4,
        transitions = listOf(SceneTransition.Circle, SceneTransition.Push, SceneTransition.Peel), accentsPerScene = 2, gap = 40f,
        enter = Easing.Spring(0.5f, 1.7f), glide = Easing.Bezier(0.5f, 0f, 0.2f, 1f), heroBleeds = true,
        scaleTall = 1.12f, scaleWide = 1.05f,
    )

    fun of(g: FilmGrammar): GrammarRules = when (g) {
        FilmGrammar.DiagramChapters -> Diagram
        FilmGrammar.IllustratedNews -> News
    }

    /** What a news scene holds where: the words on top, the hero below, one device between, decoration at the corners. */
    internal enum class Zone { Words, Device, Hero, Attached, Corners }

    internal fun zone(p: Primitive): Zone = when (p) {
        Primitive.Headline, Primitive.Heading, Primitive.Kicker, Primitive.BigWord, Primitive.Note, Primitive.Quote, Primitive.Annotation -> Zone.Words
        Primitive.Hero -> Zone.Hero
        Primitive.Bubble -> Zone.Attached
        Primitive.Stars -> Zone.Corners
        else -> Zone.Device
    }
}
