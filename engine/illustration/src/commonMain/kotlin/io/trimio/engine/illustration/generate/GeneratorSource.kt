package io.trimio.engine.illustration.generate

import io.trimio.core.model.text.TextKey
import io.trimio.engine.illustration.Box
import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Illustration
import io.trimio.engine.illustration.IllustrationRequest
import io.trimio.engine.illustration.IllustrationSource
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.library.LibrarySource
import io.trimio.engine.illustration.library.Words
import io.trimio.engine.illustration.photo.PhotoStyler
import io.trimio.engine.illustration.quality.TextArtefacts
import io.trimio.engine.illustration.style.ArtStyle
import io.trimio.engine.illustration.style.FilmLook

/**
 * Writes the generator's prompt from a request: the scene in plain English (Persian words translated
 * through the library's bilingual keywords when the model reads only English), then the film's fixed
 * style words, and a negative prompt that keeps letters, logos and colour out.
 */
class PromptWriter(private val library: Library) {
    private val faToEn: Map<String, String> by lazy {
        buildMap {
            for (item in library.items) {
                val en = item.en.firstOrNull() ?: continue
                for (fa in item.fa) putIfAbsent(TextKey.of(fa), en)
            }
            PEOPLE.forEach { (fa, en) -> put(TextKey.of(fa), en) }
        }
    }

    fun prompt(request: IllustrationRequest, look: FilmLook, englishOnly: Boolean): String {
        val parts = listOf(request.subject, request.action, request.mood, request.objects.joinToString(", "), request.place.let { if (it.isBlank()) it else "at $it" })
            .filter { it.isNotBlank() }
            .map { if (englishOnly) english(it) else it }
            .filter { it.isNotBlank() }
        return (parts + STYLE.getValue(look.style)).joinToString(", ")
    }

    fun negative(): String = NEGATIVE

    /** English for [text]: ASCII words kept, Persian words translated where the library knows them, the rest dropped. */
    fun english(text: String): String = Words.terms(text).mapNotNull { t ->
        if (t.all { it.code < ASCII }) t else faToEn[t] ?: faToEn.entries.firstOrNull { (k, _) -> Words.match(t, k) > 0f }?.value
    }.distinct().joinToString(" ")

    companion object {
        private const val ASCII = 128
        val STYLE = mapOf(
            ArtStyle.Pencil to "pencil sketch illustration, graphite drawing, cross-hatching shading, monochrome, plain white background, centered, single subject",
            ArtStyle.Ink to "pen and ink illustration, fine line art, cross-hatching, black and white, plain white background, centered, single subject",
            ArtStyle.Flat to "simple flat illustration, clean outlines, grey tones, plain white background, centered, single subject",
        )
        const val NEGATIVE = "text, letters, words, writing, caption, watermark, signature, logo, brand, colorful, photo, frame, border, blurry, cropped, deformed hands"
        private val PEOPLE = listOf(
            "مرد" to "man", "زن" to "woman wearing a headscarf", "خانم" to "woman wearing a headscarf", "آقا" to "man", "پسر" to "young man",
            "دختر" to "young woman wearing a headscarf", "مردم" to "people", "کاربران" to "people", "کاربر" to "person", "مشتری" to "customer",
            "پیرمرد" to "old man", "پیرزن" to "old woman wearing a headscarf", "پزشک" to "doctor", "دکتر" to "doctor", "نگران" to "worried",
            "خوشحال" to "happy", "عصبانی" to "angry", "خسته" to "tired", "منتظر" to "waiting", "گوشی" to "phone", "موبایل" to "phone",
        )
    }
}

/**
 * The on-device generator as a source: prompt from the request, image-to-image from the library's own
 * sketch of the scene when the model takes an init picture (the composition stays the film's), a fixed
 * seed per scene, then [PhotoStyler] so the result wears exactly the film's look. A picture with
 * letter-like marks ([TextArtefacts]) is generated again with the next seed, at most [retries] times;
 * then the source gives up and the caller keeps the library's picture.
 */
class GeneratorSource(
    private val engine: DiffusionEngine,
    private val library: Library,
    private val template: LibrarySource? = LibrarySource(library),
    private val retries: Int = 2,
) : IllustrationSource {
    override val id: String = "generator"
    private val writer = PromptWriter(library)

    /** The film's first generated picture, kept as the style reference of the rest (models that take references). */
    var styleReference: Raster? = null

    override suspend fun illustrate(request: IllustrationRequest, look: FilmLook, size: Int): Illustration? {
        val recipe = engine.recipe
        val (w, h) = LibrarySource.frame(recipe.size, request.aspect).let { (a, b) -> snap(a) to snap(b) }
        val init = if (recipe.img2img) template?.illustrate(request, look.copy(accentObjects = false), recipe.size)?.image?.let { flatten(it, w, h) } else null
        val prompt = writer.prompt(request, look, recipe.englishOnly)
        val seedBase = look.seed * SEED_MIX + request.id.hashCode()
        for (attempt in 0..retries) {
            val job = DiffusionJob(
                prompt = prompt, negative = writer.negative(), width = w, height = h, seed = seedBase + attempt,
                steps = recipe.steps, cfg = recipe.cfg, init = init,
                references = if (recipe.references) listOfNotNull(styleReference) else emptyList(),
            )
            val raw = engine.generate(job) ?: return null
            val image = PhotoStyler.style(raw, look, size)
            // Checked before and after styling: the styler's lines can sharpen faint pseudo-letters into readable marks.
            if (TextArtefacts.find(raw).textLike || TextArtefacts.find(image).textLike) continue
            if (styleReference == null && recipe.references) styleReference = raw
            return Illustration(
                image = image,
                source = id,
                items = listOf("model:${engine.modelId}", "seed:${job.seed}"),
                // The prompt names every word of the request; a vision check may lower this.
                fit = PROMPT_FIT,
                look = look.id,
                subject = image.opaqueBounds() ?: Box(0f, 0f, image.width.toFloat(), image.height.toFloat()),
            )
        }
        return null
    }

    /** The template on white at the generator's size (models expect an opaque picture). */
    private fun flatten(r: Raster, w: Int, h: Int): Raster {
        val canvas = Raster.filled(w, h, Colors.WHITE)
        val fitted = r.resized(w, h)
        canvas.draw(fitted, 0, 0)
        return canvas
    }

    private fun snap(v: Int): Int = (v / GRID).coerceAtLeast(1) * GRID

    private companion object {
        const val SEED_MIX = 1_000_003L
        const val GRID = 64
        const val PROMPT_FIT = 0.75f
    }
}
