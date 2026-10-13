package io.trimio.engine.illustration

import io.trimio.engine.illustration.style.FilmLook
import kotlinx.serialization.Serializable

/**
 * What one scene's hero illustration should show. Written by the screenwriter from the narration;
 * words may be English or Persian. [sign] is a name to show on a sign or object — set it only when the
 * narration itself says the name (the illustration never invents brands). [aspect] is width/height.
 */
@Serializable
data class IllustrationRequest(
    /** Who or what the picture is about ("a worried young man", "a safe full of gold", «کاربران»). */
    val subject: String,
    /** What the subject does ("looking at his phone", «منتظر»). */
    val action: String = "",
    /** Things in the picture besides the subject ("gold bars", "a report"). */
    val objects: List<String> = emptyList(),
    /** Where it happens ("in front of a shop", «جلوی بانک»). */
    val place: String = "",
    /** The feeling to show ("worried", «خوشحال»). */
    val mood: String = "",
    /** A name to put on the sign or object, only when spoken in the narration. */
    val sign: String? = null,
    /** How many people when it is a group (0: read it from the words). */
    val count: Int = 0,
    val aspect: Float = 1f,
    /** The narration sentence this picture serves (for keyword fallback and the jury). */
    val line: String = "",
    val language: String = "fa",
    /** Id of the creator's own photo to style instead of drawing one, when they attached it for this moment. */
    val photo: String? = null,
    /** Stable id of the scene (the picture's media id on the stage). */
    val id: String = "",
)

/**
 * A finished illustration, already in the film's look: [image] has a transparent background where the
 * source allows (library drawings always; photos and generated pictures when their background could be
 * separated, else a soft vignette). Boxes are in [image] pixels.
 */
class Illustration(
    val image: Raster,
    /** "library", "photo" or "generator". */
    val source: String,
    /** Library pieces or model used (for credits and the jury). */
    val items: List<String>,
    /** How well the picture shows what was asked, 0..1 (keywords; a vision check may lower it). */
    val fit: Float,
    /** [FilmLook.id] it was styled with. */
    val look: String,
    /** The main subject's box (the stage aims motion and highlights at it). */
    val subject: Box,
    /** Where a spoken name may be lettered (a shop's sign band, an object's front), when [IllustrationRequest.sign] was set. */
    val signSlot: Box? = null,
    /** Words of the request the picture shows / does not show (jury notes). */
    val shown: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
)

/** One way to make an illustration. Sources answer null when they cannot serve a request (no photo, no model, no match). */
interface IllustrationSource {
    /** "library", "photo", "generator". */
    val id: String

    suspend fun illustrate(request: IllustrationRequest, look: FilmLook, size: Int = DEFAULT_SIZE): Illustration?

    companion object {
        /** Long side of an illustration in pixels (a hero picture fills about two thirds of a 1080-wide frame). */
        const val DEFAULT_SIZE = 768
    }
}
