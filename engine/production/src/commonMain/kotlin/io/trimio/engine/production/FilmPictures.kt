package io.trimio.engine.production

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.trimio.engine.autopilot.explainer.FilmStage
import io.trimio.engine.autopilot.explainer.StageRenderer
import io.trimio.engine.illustration.Illustrator
import io.trimio.engine.illustration.style.ArtStyle
import io.trimio.engine.illustration.style.FilmLook
import io.trimio.engine.illustration.toImageBitmap
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.ExplainerStage
import io.trimio.engine.motion.explainer.IconResolver
import io.trimio.engine.motion.explainer.Illustration
import io.trimio.engine.motion.explainer.IllustrationSource
import io.trimio.engine.motion.explainer.IllustrationStyle
import io.trimio.engine.motion.explainer.PaletteBook
import kotlin.concurrent.Volatile
import io.trimio.engine.autopilot.explainer.IllustrationRequest as SceneRequest
import io.trimio.engine.illustration.IllustrationRequest as PictureRequest

/**
 * The hero pictures of explainer films, drawn once and kept for the renderers: every exporter and
 * preview reads them through the platform's media ([ProductionPlatform.media] puts this source last),
 * under the stage's ids ("illustration:<film look>|<request>"). The newest [MAX] pictures stay.
 */
object FilmPictures : MediaSource {
    private const val PREFIX = "illustration:"
    private const val MAX = 64

    @Volatile
    private var pictures: Map<String, ImageBitmap> = emptyMap()

    override fun frame(source: String, time: Float): ImageBitmap? =
        if (source.startsWith(PREFIX)) pictures[source.removePrefix(PREFIX)] else null

    internal fun has(id: String): Boolean = id in pictures

    internal fun keep(id: String, image: ImageBitmap) {
        val next = LinkedHashMap(pictures)
        next.remove(id)
        next[id] = image
        while (next.size > MAX) next.remove(next.keys.first())
        pictures = next
    }

    internal fun size(id: String): Pair<Float, Float>? = pictures[id]?.let { it.width.toFloat() to it.height.toFloat() }
}

/**
 * The stage with the illustration layer behind it: [prepare] draws each scene's requested hero
 * (BeatContent.illustration, the screenwriter's encoded request) with the bundled CC0 library in the
 * film's one look; [compose] lays the film out with those pictures. A request the library cannot
 * show well stays unanswered and the stage draws its own glyph (never an unrelated picture).
 */
class IllustratedStage(
    private val text: TextLayoutEngine,
    private val icons: IconResolver = IconResolver.None,
    private val illustrator: suspend () -> Illustrator = { Illustrator.create() },
) : FilmStage {
    private var drawn: Illustrator? = null

    override suspend fun prepare(film: ExplainerFilm): List<String> {
        val requests = film.chapters.flatMap { c -> c.beats.map { it.content.illustration to it.line } }
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
        if (requests.isEmpty()) return emptyList()
        val look = lookOf(film)
        val maker = drawn ?: illustrator().also { drawn = it }
        val shown = requests.count { (encoded, line) -> draw(maker, look, encoded, line, film.language) }
        return listOf("illustrations: $shown of ${requests.size} scenes drawn from the library (${look.style.name.lowercase()})")
    }

    /** One scene's picture into [FilmPictures] (kept from an earlier film when it was drawn already); false when none fits. */
    private suspend fun draw(maker: Illustrator, look: FilmLook, encoded: String, line: String, language: String): Boolean {
        val id = idOf(look, encoded)
        if (FilmPictures.has(id)) return true
        val scene = SceneRequest.parse(encoded) ?: return false
        val request = PictureRequest(scene.subject, scene.action, scene.objects, sign = scene.sign.ifBlank { null }, line = line, language = language, id = id)
        val picture = maker.illustrate(request, look) ?: return false
        FilmPictures.keep(id, picture.image.toImageBitmap())
        return true
    }

    override fun compose(film: ExplainerFilm, format: String, fps: Int, duration: Float): Composition {
        val (w, h) = StageRenderer.size(format)
        val look = lookOf(film)
        val source = object : IllustrationSource {
            override fun find(query: String, style: IllustrationStyle): Illustration? {
                val id = idOf(look, query)
                val (pw, ph) = FilmPictures.size(id) ?: return null
                return Illustration.Raster(id, pw, ph)
            }

            override fun image(id: String): ImageBitmap? = FilmPictures.frame("illustration:$id", 0f)
        }
        return ExplainerStage(text, icons, source).compose(film, w, h, fps).composition
    }

    private companion object {
        fun idOf(look: FilmLook, encoded: String) = "${look.id}|$encoded"

        /** The film's art direction as the pictures' look: its palette's first accent, its ink as the dark. */
        fun lookOf(film: ExplainerFilm): FilmLook {
            val pal = PaletteBook.named(film.art.palette).branded(film.art.accent, film.art.dark)
            return FilmLook(style = ArtStyle.Pencil, accent = pal.accents.first().toArgb(), dark = pal.ink.toArgb(), seed = film.art.seed)
        }
    }
}
