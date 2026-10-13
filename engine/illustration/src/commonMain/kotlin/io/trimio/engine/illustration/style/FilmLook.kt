package io.trimio.engine.illustration.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The art style of a film's illustrations. One per film; every source is styled into it. */
@Serializable
enum class ArtStyle {
    /** Graphite pencil: sketchy double lines, hatching by tone, paper grain; monochrome plus the accent. */
    @SerialName("pencil") Pencil,

    /** Pen and ink: crisp lines, cross-hatching, solid blacks, no grain. */
    @SerialName("ink") Ink,

    /** Flat: clean outlines and two or three grey tones, no hatching (calm diagram films). */
    @SerialName("flat") Flat,
}

/**
 * The one look every illustration of a film shares: style, the accent (and the dark that pairs with it
 * for signs and highlight boxes), paper and ink, the line weight and hatching density, and the seed of
 * the hand (grain, pressure, wobble). Colours are ARGB. Built once per film (from the art direction)
 * and passed to every source, so no picture can drift out of the film's style.
 */
@Serializable
data class FilmLook(
    val style: ArtStyle = ArtStyle.Pencil,
    val accent: Int = YELLOW,
    val dark: Int = NAVY,
    val paper: Int = PAPER,
    val ink: Int = GRAPHITE,
    val seed: Long = 1L,
    /** Line weight relative to the default (1 ≈ 2 px at 768 px). */
    val lineWeight: Float = 1f,
    /** Hatching density relative to the default. */
    val hatching: Float = 1f,
    /** Paint the hero object's main colour area in the accent (the gold of a coin, the face of a sign). */
    val accentObjects: Boolean = true,
) {
    /** A short id of the look, for the jury's consistency check (equal looks give equal ids). */
    val id: String get() = "${style.name.lowercase()}-${hex(accent)}-${hex(dark)}-$seed"

    companion object {
        const val YELLOW = -0x3a00 // #FFC600
        const val NAVY = -0xeeee9a // #111166
        const val PAPER = -0x1 // white
        const val GRAPHITE = -0xd5d5d2 // #2A2A2E
        private const val RGB = 0xFFFFFF
        private const val HEX = 16

        private fun hex(c: Int) = (c and RGB).toString(HEX).padStart(6, '0')
    }
}
