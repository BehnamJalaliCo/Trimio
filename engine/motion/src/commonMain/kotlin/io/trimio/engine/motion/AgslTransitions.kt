package io.trimio.engine.motion

/**
 * The transition catalog the app ships, as pure data over [TransitionShaders]: the lowercase
 * gl-transitions ids for the eight MIT ports, plus the in-house cuts that give whip, zoom and
 * glitch their pictures. A name in a recipe either is verified or becomes a verified neighbour —
 * the picker, the compiler and the export writer read this object so a name means one thing
 * everywhere. Nothing here draws: it only decides which names exist and which neighbour a
 * stranger becomes. Below a shader compiler the engine crossfades instead of drawing a program.
 */
object AgslTransitions {

    /** The programs the engine actually runs: the eight ports, then the in-house cuts. */
    val verified: List<String> = listOf(
        "fade",
        "fadecolor",
        "directionalwarp",
        "crosswarp",
        "lumawipe",
        "pixelize",
        "circleopen",
        "noisedissolve",
        "whip",
        "zoom",
        "glitchlite",
    )

    /**
     * The program [name] draws after [fallbackFor]. Total — every string has a program, so a
     * broken recipe can never wander and nothing has to guess at draw time.
     */
    fun programFor(name: String): TransitionProgram = TransitionShaders.program(fallbackFor(name))

    /**
     * Neighbours for names a recipe may still ask for: catalogue siblings outside the pool, older
     * spellings and near-aliases. Same input always maps to the same neighbour; anything not
     * listed falls to [DEFAULT_FALLBACK].
     */
    private val NEIGHBOURS: Map<String, String> = linkedMapOf(
        // Colour washes collapse onto the fade through colour.
        "burn" to "fadecolor",
        "colorphase" to "fadecolor",
        "filmburn" to "fadecolor",
        "undulatingburnout" to "fadecolor",
        "dip" to "fadecolor",
        "flash" to "fadecolor",
        "leak" to "fadecolor",
        "leak-cool" to "fadecolor",
        // A cut that tears is the glitch's picture; slices are its bands.
        "slice" to "glitchlite",
        "glitch" to "glitchlite",
        "glitchmemories" to "glitchlite",
        "crazyparametricfun" to "glitchlite",
        // Luma dissolves.
        "luma" to "lumawipe",
        "lumablack" to "lumawipe",
        "lumawhite" to "lumawipe",
        // Warps, wipes and page turns share the directional family.
        "wind" to "directionalwarp",
        "wipeleft" to "directionalwarp",
        "wiperight" to "directionalwarp",
        "wipeup" to "directionalwarp",
        "wipedown" to "directionalwarp",
        "directionaleasing" to "directionalwarp",
        "cube" to "directionalwarp",
        "doorway" to "directionalwarp",
        "bookflip" to "directionalwarp",
        "invertedpagecurl" to "directionalwarp",
        "bounce" to "directionalwarp",
        "stereoviewer" to "directionalwarp",
        // Warps and shape morphs.
        "crosszoom" to "crosswarp",
        "simplezoom" to "crosswarp",
        "squeeze" to "crosswarp",
        "swap" to "crosswarp",
        "morph" to "crosswarp",
        "displacement" to "crosswarp",
        "heart" to "crosswarp",
        "dreamy" to "crosswarp",
        "dreamyzoom" to "crosswarp",
        "kaleidoscope" to "crosswarp",
        "pinwheel" to "crosswarp",
        "butterflywavescrawler" to "crosswarp",
        // Round cuts: a ripple's ring is an opening circle.
        "circle" to "circleopen",
        "circleclose" to "circleopen",
        "radial" to "circleopen",
        "waterdrop" to "circleopen",
        "ripple" to "circleopen",
        // Grid and block breaks pixelate.
        "crosshatch" to "pixelize",
        "flyeye" to "pixelize",
        "hexagonalize" to "pixelize",
        "mosaic" to "pixelize",
        "polkadots" to "pixelize",
        "randomsquares" to "pixelize",
        "squareswire" to "pixelize",
        "windowblinds" to "pixelize",
        "doomscreen" to "pixelize",
        // Noise dissolves.
        "perlin" to "noisedissolve",
        "noise" to "noisedissolve",
        // The whip pan is the slide cut; a plain crossfade is the fade.
        "slide" to "whip",
        "crossfade" to "fade",
    )

    private const val DEFAULT_FALLBACK = "fade"

    /**
     * The name to draw when [name] is unknown or outside the pool: verified names come back
     * unchanged (case-folded), listed strangers become their neighbour, the rest become
     * [DEFAULT_FALLBACK]. Pure and total — every string has an answer inside [verified].
     */
    fun fallbackFor(name: String): String {
        val key = name.trim().lowercase()
        if (key in verified) return key
        return NEIGHBOURS[key] ?: DEFAULT_FALLBACK
    }
}
