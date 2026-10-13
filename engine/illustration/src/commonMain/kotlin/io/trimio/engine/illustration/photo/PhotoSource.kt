package io.trimio.engine.illustration.photo

import io.trimio.engine.illustration.Box
import io.trimio.engine.illustration.Illustration
import io.trimio.engine.illustration.IllustrationRequest
import io.trimio.engine.illustration.IllustrationSource
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.style.FilmLook

/** The creator's attached pictures, decoded, by id (the app's media store answers; null when unknown). */
fun interface PhotoStore {
    suspend fun photo(id: String): Raster?
}

/**
 * The creator's own photo for a moment ([IllustrationRequest.photo]) drawn in the film's look by
 * [PhotoStyler]: the same lines, hatching and accent as every library picture, so a real product shot
 * or portrait sits in the film without breaking its style.
 */
class PhotoSource(private val store: PhotoStore) : IllustrationSource {
    override val id: String = "photo"

    override suspend fun illustrate(request: IllustrationRequest, look: FilmLook, size: Int): Illustration? {
        val key = request.photo ?: return null
        val photo = store.photo(key) ?: return null
        val image = PhotoStyler.style(photo, look, size)
        return Illustration(
            image = image,
            source = id,
            items = listOf("photo:$key"),
            // The creator chose this photo for this moment: it shows what they meant.
            fit = 1f,
            look = look.id,
            subject = image.opaqueBounds() ?: Box(0f, 0f, image.width.toFloat(), image.height.toFloat()),
        )
    }
}
