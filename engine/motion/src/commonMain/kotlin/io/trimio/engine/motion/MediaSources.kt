package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Several sources behind one [MediaSource]: the speaker's footage and the creator's own pictures
 * and clips, each answering to its own id. The first source with a frame for an id wins, so the
 * creator's media go before a footage source that answers every id.
 */
class MediaSources(private val sources: List<MediaSource>) : MediaSource, AutoCloseable {
    constructor(vararg sources: MediaSource) : this(sources.toList())

    override fun frame(source: String, time: Float): ImageBitmap? = sources.firstNotNullOfOrNull { it.frame(source, time) }

    override fun close() = sources.forEach { (it as? AutoCloseable)?.close() }
}

/** A still the creator attached (a logo, a product photo, a screenshot): the same picture at every time, under [id]. */
class StillMedia(private val id: String, private val image: ImageBitmap) : MediaSource {
    override fun frame(source: String, time: Float): ImageBitmap? = image.takeIf { source == id }
}

/**
 * Footage left out: every id answers with a clear frame, so what would draw over the picture is
 * all that is drawn (the graphics layer handed to another editor, which cuts the footage itself).
 */
object ClearMedia : MediaSource {
    private val clear by lazy { ImageBitmap(2, 2) }

    override fun frame(source: String, time: Float): ImageBitmap = clear
}
