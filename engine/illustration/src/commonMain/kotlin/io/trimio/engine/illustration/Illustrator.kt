package io.trimio.engine.illustration

import io.trimio.engine.illustration.generate.DiffusionEngine
import io.trimio.engine.illustration.generate.GeneratorSource
import io.trimio.engine.illustration.library.ItemRanker
import io.trimio.engine.illustration.library.KeywordRanker
import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.library.LibrarySource
import io.trimio.engine.illustration.photo.PhotoSource
import io.trimio.engine.illustration.photo.PhotoStore
import io.trimio.engine.illustration.quality.StyleCheck
import io.trimio.engine.illustration.style.FilmLook

/**
 * A vision model's opinion of how well a picture shows a description, 0..1 (the director's eyes,
 * Qwen3.5 with its projector, when installed). Null when it cannot tell.
 */
fun interface VisionCheck {
    suspend fun score(image: Raster, description: String): Float?
}

/**
 * The illustration layer's one entry point. For each scene it asks, in order:
 *  1. the creator's photo for that moment, when attached ([PhotoSource]);
 *  2. the library, which is instant, licence-clean and always in style — kept when it shows the
 *     request well ([goodEnough]);
 *  3. the generator add-on, when installed, for what the library cannot show (its picture is kept
 *     only when a vision check, if present, agrees it shows the request better);
 * and answers the best picture at or above [minFit], else null (the stage then uses a diagram
 * primitive: never an irrelevant picture). Every answer is already in the film's [FilmLook].
 */
class Illustrator(
    private val library: LibrarySource,
    private val photos: PhotoSource? = null,
    private val generator: GeneratorSource? = null,
    private val vision: VisionCheck? = null,
    private val goodEnough: Float = GOOD_ENOUGH,
    private val minFit: Float = MIN_FIT,
) {
    suspend fun illustrate(request: IllustrationRequest, look: FilmLook, size: Int = IllustrationSource.DEFAULT_SIZE): Illustration? {
        photos?.illustrate(request, look, size)?.let { return it }
        val fromLibrary = library.illustrate(request, look, size)?.let { checked(it, request) }
        if (fromLibrary != null && fromLibrary.fit >= goodEnough) return fromLibrary
        val generated = generator?.illustrate(request, look, size)?.let { checked(it, request) }
        val best = listOfNotNull(fromLibrary, generated).maxByOrNull { it.fit }
        return best?.takeIf { it.fit >= minFit }
    }

    /** Frees what the generator holds (its model) once a film's pictures are drawn; it loads again for the next film. */
    fun release() {
        generator?.release()
    }

    /** A film's pictures with the jury's style report (one look, no foreign colour, even density, subjects shown, no text). */
    suspend fun film(requests: List<IllustrationRequest>, look: FilmLook, size: Int = IllustrationSource.DEFAULT_SIZE): Pair<List<Illustration?>, StyleCheck.Report> {
        val pictures = requests.map { illustrate(it, look, size) }
        return pictures to StyleCheck.film(pictures.filterNotNull(), look, minFit)
    }

    /** The keyword fit, lowered (never raised above it for library pictures) by the vision check when one is installed. */
    private suspend fun checked(p: Illustration, request: IllustrationRequest): Illustration {
        val v = vision ?: return p
        val description = listOf(request.subject, request.action, request.objects.joinToString(", "), request.place).filter { it.isNotBlank() }.joinToString(", ")
        val seen = v.score(p.image, description) ?: return p
        val fit = if (p.source == "library") minOf(p.fit, seen) else seen
        return Illustration(p.image, p.source, p.items, fit, p.look, p.subject, p.signSlot, p.shown, p.missing)
    }

    companion object {
        const val GOOD_ENOUGH = 0.8f
        const val MIN_FIT = 0.5f

        /**
         * The usual wiring: the bundled library ranked by [ranker] (words, or meaning when the retrieval
         * add-ons are installed), the creator's [photos], and the generator when its add-on [engine] is loaded.
         */
        suspend fun create(
            ranker: ItemRanker = KeywordRanker,
            photos: PhotoStore? = null,
            engine: DiffusionEngine? = null,
            vision: VisionCheck? = null,
            library: Library? = null,
        ): Illustrator {
            val lib = library ?: Library.bundled()
            return Illustrator(
                library = LibrarySource(lib, ranker),
                photos = photos?.let(::PhotoSource),
                // One more draw at most when letter-like marks show: a studio picture takes minutes on a processor.
                generator = engine?.let { GeneratorSource(it, lib, retries = if (it.recipe.steps > 1) 1 else 2) },
                vision = vision,
            )
        }
    }
}
