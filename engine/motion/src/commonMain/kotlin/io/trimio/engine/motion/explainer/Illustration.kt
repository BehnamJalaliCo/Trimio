package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.visual.VectorIcon

/** The art style a film's illustrations are drawn (or styled) in. */
enum class IllustrationStyle {
    /** Monochrome pencil sketch on white, with the film's one accent around it. */
    PencilSketch,

    /** Clean monochrome line art. */
    Line,
}

/** A hero illustration: a raster picture the stage reads by [id] at render time, or a vector drawing. */
sealed interface Illustration {
    /** [id] is resolved through [IllustrationSource.image]; [width] / [height] give its shape. */
    data class Raster(val id: String, val width: Float, val height: Float) : Illustration

    data class Vector(val icon: VectorIcon) : Illustration
}

/**
 * Where hero illustrations come from (CC0 hand-drawn libraries, an on-device generator, the user's own
 * photos in the film's style — built elsewhere and injected). The stage asks for one per scene by a
 * query in English; [image] hands over raster pixels when the composition is drawn.
 */
interface IllustrationSource {
    fun find(query: String, style: IllustrationStyle): Illustration?

    fun image(id: String): ImageBitmap? = null

    companion object {
        /** No illustrations: heroes fall back to the stage's own hand-drawn glyphs. */
        val None = object : IllustrationSource {
            override fun find(query: String, style: IllustrationStyle): Illustration? = null
        }
    }
}

/** The media the composition draws illustrations from (pass it to the renderer or exporter with the composition). */
internal class IllustrationMedia(private val source: IllustrationSource) : MediaSource {
    private val cache = HashMap<String, ImageBitmap?>()

    override fun frame(source: String, time: Float): ImageBitmap? = cache.getOrPut(source) { this.source.image(source.removePrefix(PREFIX)) }

    companion object {
        const val PREFIX = "illustration:"
    }
}
