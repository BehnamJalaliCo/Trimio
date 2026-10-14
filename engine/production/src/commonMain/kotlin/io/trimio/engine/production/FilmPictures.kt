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
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
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
    /** Pictures drawn before (a reopen after a restart draws none again). */
    private val files: FilmPictureFiles = FilmPictureFiles(null),
) : FilmStage {
    private var drawn: Illustrator? = null

    override suspend fun prepare(film: ExplainerFilm, onProgress: (Float) -> Unit): List<String> {
        val requests = film.chapters.flatMap { c -> c.beats.map { it.content.illustration to it.line } }
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
        if (requests.isEmpty()) return emptyList()
        val look = lookOf(film)
        val maker = drawn ?: illustrator().also { drawn = it }
        val n = requests.size.toFloat()
        val sources = try {
            requests.mapIndexed { i, r ->
                // A generated picture's every denoising step moves the bar, so minutes on a processor never look stalled.
                val steps = io.trimio.engine.illustration.generate.DrawProgress { step, total -> onProgress((i + step / total.coerceAtLeast(1).toFloat()) / n) }
                withContext(steps) { draw(maker, look, r.first, r.second, film.language) }.also { onProgress((i + 1) / n) }
            }
        } finally {
            maker.release()
        }
        val by = sources.filterNotNull().groupingBy { it }.eachCount().entries.joinToString(", ") { (k, v) -> "$k $v" }
        return listOf("illustrations: ${sources.count { it != null }} of ${requests.size} scenes drawn ($by; ${look.style.name.lowercase()})")
    }

    /**
     * One scene's picture into [FilmPictures] (kept from an earlier film when it was drawn already): where it came from
     * ("library", "generator", "photo", "kept"), or null when nothing fits.
     */
    private suspend fun draw(maker: Illustrator, look: FilmLook, encoded: String, line: String, language: String): String? {
        val id = idOf(look, encoded)
        if (FilmPictures.has(id)) return "kept"
        files.read(id)?.let { kept ->
            FilmPictures.keep(id, kept.toImageBitmap())
            return "kept"
        }
        val scene = SceneRequest.parse(encoded) ?: return null
        val request = PictureRequest(scene.subject, scene.action, scene.objects, sign = scene.sign.ifBlank { null }, line = line, language = language, id = id)
        val picture = maker.illustrate(request, look) ?: return null
        FilmPictures.keep(id, picture.image.toImageBitmap())
        files.write(id, picture.image)
        return picture.source
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

/**
 * The films' drawn pictures on disk (beside the checkpoints): a film opened again after a restart, or a 16:9 export,
 * takes its pictures from here instead of drawing them again (a generated one takes minutes on a processor). Raw ARGB
 * with its size; at most [MAX_FILES]. Null [dir]: nothing is kept.
 */
class FilmPictureFiles(private val dir: kotlinx.io.files.Path?) {
    fun read(id: String): io.trimio.engine.illustration.Raster? = runCatching {
        val file = fileOf(id) ?: return null
        if (!kotlinx.io.files.SystemFileSystem.exists(file)) return null
        kotlinx.io.files.SystemFileSystem.source(file).buffered().use { s ->
            val w = s.readInt()
            val h = s.readInt()
            io.trimio.engine.illustration.Raster(w, h, IntArray(w * h) { s.readInt() })
        }
    }.getOrNull()

    fun write(id: String, picture: io.trimio.engine.illustration.Raster) {
        val folder = dir ?: return
        runCatching {
            val fs = kotlinx.io.files.SystemFileSystem
            fs.createDirectories(folder)
            val file = fileOf(id) ?: return
            val tmp = kotlinx.io.files.Path(file.toString() + ".tmp")
            fs.sink(tmp).buffered().use { s ->
                s.writeInt(picture.width)
                s.writeInt(picture.height)
                picture.argb.forEach(s::writeInt)
            }
            fs.atomicMove(tmp, file)
            // Past [MAX_FILES] the folder starts over with this picture (a cache: a film opened later draws again).
            val all = fs.list(folder).filter { it.name.endsWith(EXT) }
            if (all.size > MAX_FILES) all.filter { it != file }.forEach { fs.delete(it, mustExist = false) }
        }
    }

    private fun fileOf(id: String): kotlinx.io.files.Path? {
        var h = FNV_OFFSET
        for (c in id) h = (h xor c.code.toULong()) * FNV_PRIME
        return dir?.let { kotlinx.io.files.Path(it, h.toString(16) + EXT) }
    }

    private companion object {
        const val EXT = ".argb"
        const val MAX_FILES = 200
        const val FNV_OFFSET = 0xcbf29ce484222325uL
        const val FNV_PRIME = 0x100000001b3uL
    }
}
