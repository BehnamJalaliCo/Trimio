package io.trimio.engine.motion

/**
 * The gl-transitions catalog the app ships, as pure data. The picker, the compiler and the export
 * writer all read this object so a name means the same thing everywhere: the 1.x ids from
 * `com.gl-transitions:gl-transitions:1.76.1`, lowercase as the artifact ships them. Nothing here
 * loads a shader — it only decides which names exist and which neighbour a stranger becomes.
 */
object AgslTransitions {

    /**
     * The default pool. Curated for a short-form motion studio: dissolves and warps cut, cubes and
     * page turns travel, a few distortions (ripple, swirl, morph, pixelize, window slices) and one
     * glitch for energy. Names match the Maven artifact byte for byte so a recipe string is the id.
     */
    val verified: List<String> = listOf(
        "fade",
        "fadecolor",
        "crosswarp",
        "directionalwarp",
        "cube",
        "doorway",
        "bookflip",
        "invertedpagecurl",
        "ripple",
        "swirl",
        "morph",
        "pixelize",
        "windowslices",
        "glitchmemories",
    )

    /**
     * Whether the draw path needs Android 13. The catalog loads on every API the app supports;
     * TransitionShader draws AGSL and does not. The picker greys previews under 33 instead of
     * promising a shader the device cannot run.
     */
    val needsApi33: Boolean get() = true

    /**
     * What runs below 33 when AGSL is out of reach: the `glsl3/` sources go through Media3/GLES3,
     * so the cut still happens — just not through TransitionShader.
     */
    const val glsl3FallbackNote: String = "Below API 33 the glsl3/ sources go through Media3/GLES3."

    /**
     * Neighbours for names a recipe may still ask for: catalog siblings outside the pool, older
     * spellings, and near-aliases. Same input always maps to the same neighbour — a broken recipe
     * must never wander. Anything not listed falls to [DEFAULT_FALLBACK].
     */
    private val NEIGHBOURS: Map<String, String> = linkedMapOf(
        // Colour burns collapse onto the coloured fade; plain luma wipes onto the plain one.
        "burn" to "fadecolor",
        "colorphase" to "fadecolor",
        "filmburn" to "fadecolor",
        "undulatingburnout" to "fadecolor",
        "luma" to "fade",
        "lumablack" to "fade",
        "lumawhite" to "fade",
        // Warps and wipes share the directional family.
        "wind" to "directionalwarp",
        "wipeleft" to "directionalwarp",
        "wiperight" to "directionalwarp",
        "wipeup" to "directionalwarp",
        "wipedown" to "directionalwarp",
        "directionaleasing" to "directionalwarp",
        "crosszoom" to "crosswarp",
        "simplezoom" to "crosswarp",
        "squeeze" to "crosswarp",
        // Round and swirl distortions.
        "circle" to "ripple",
        "circleopen" to "ripple",
        "circleclose" to "ripple",
        "radial" to "ripple",
        "waterdrop" to "ripple",
        "dreamy" to "swirl",
        "dreamyzoom" to "swirl",
        "kaleidoscope" to "swirl",
        "pinwheel" to "swirl",
        "butterflywavescrawler" to "swirl",
        // Grid and block breaks.
        "crosshatch" to "pixelize",
        "flyeye" to "pixelize",
        "hexagonalize" to "pixelize",
        "mosaic" to "pixelize",
        "polkadots" to "pixelize",
        "randomsquares" to "windowslices",
        "squareswire" to "windowslices",
        "windowblinds" to "windowslices",
        "doomscreen" to "windowslices",
        "crazyparametricfun" to "glitchmemories",
        // Shape morphs and cubes.
        "displacement" to "morph",
        "heart" to "morph",
        "perlin" to "morph",
        "swap" to "morph",
        "bounce" to "cube",
        "stereoviewer" to "cube",
    )

    private const val DEFAULT_FALLBACK = "fade"

    /**
     * The name to draw when [name] is unknown or outside the pool. Verified names come back
     * unchanged (case-folded); listed strangers become their neighbour; the rest become
     * [DEFAULT_FALLBACK]. Pure and total — every string has an answer inside [verified].
     */
    fun fallbackFor(name: String): String {
        val key = name.trim().lowercase()
        if (key in verified) return key
        return NEIGHBOURS[key] ?: DEFAULT_FALLBACK
    }
}
