package io.trimio.engine.illustration.library

import io.trimio.engine.illustration.Box
import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Illustration
import io.trimio.engine.illustration.IllustrationRequest
import io.trimio.engine.illustration.IllustrationSource
import io.trimio.engine.illustration.style.FilmLook
import io.trimio.engine.illustration.style.SketchStyler
import io.trimio.engine.illustration.style.StyleInput
import io.trimio.engine.illustration.vector.Affine
import io.trimio.engine.illustration.vector.Drawing
import io.trimio.engine.illustration.vector.Render
import kotlin.math.max
import kotlin.math.min

/**
 * The composable library as a source: people (Open Peeps, posed and dressed from the words), the hero
 * object or place, up to two props, laid out as one scene and drawn in the film's look. Answers null
 * when nothing in the library shows the request (the caller then tries the generator, or the stage
 * falls back to a diagram primitive) — never an unrelated picture.
 */
class LibrarySource(
    private val library: Library,
    private val ranker: ItemRanker = KeywordRanker,
    /** Below this keyword score an item is not offered. */
    private val floor: Float = DEFAULT_FLOOR,
) : IllustrationSource {
    override val id: String = "library"

    /** What a request turns into before drawing (exposed for the jury and tests). */
    class Plan(
        val cast: Cast,
        val peeps: List<Peep>,
        val hero: LibraryItem?,
        val place: LibraryItem?,
        val props: List<LibraryItem>,
        val shown: List<String>,
        val missing: List<String>,
        val fit: Float,
    ) {
        val isEmpty: Boolean get() = peeps.isEmpty() && hero == null && place == null && props.isEmpty()
        val items: List<String> get() = peeps.flatMap { it.pieces } + listOfNotNull(hero?.src, place?.src) + props.map { it.src }
    }

    suspend fun plan(request: IllustrationRequest, look: FilmLook): Plan {
        val cast = People.cast(request.subject, request.action, request.mood, request.count)
        val place = request.place.takeIf { it.isNotBlank() }?.let { best(it, PLACES) }
        val hero = if (cast.isEmpty) best(request.subject, null) ?: best(request.line, null) else null
        val held = held(cast)
        val props = request.objects.mapNotNull { o -> best(o, OBJECTS)?.takeIf { p -> held.none { it in p.keys } } }
            .filter { it != hero }.distinctBy { it.id }.take(MAX_PROPS)
        val peeps = if (cast.isEmpty) emptyList() else People.dress(cast, library, look.seed xor request.id.hashCode().toLong(), request.language, place != null)
        val (shown, missing) = coverage(request, cast, listOfNotNull(hero, place) + props)
        val weight = shown.size + missing.size
        val fit = if (weight == 0) NEUTRAL_FIT else shown.size.toFloat() / weight
        return Plan(cast, peeps, hero, place, props, shown, missing, fit)
    }

    override suspend fun illustrate(request: IllustrationRequest, look: FilmLook, size: Int): Illustration? {
        val plan = plan(request, look)
        if (plan.isEmpty) return null
        val (w, h) = frame(size, request.aspect)
        val scene = compose(plan, w.toFloat() / h)
        val flat = Render.paint(scene.drawing, w, h, Affine.scale(w / SCENE_W))
        val accent = if (look.accentObjects) scene.accentParts.firstNotNullOfOrNull { accentMask(flat.shapes, flat.regions, it, w, h) } else null
        val image = SketchStyler(look).style(StyleInput(flat.image, regions = flat.regions, accent = accent))
        val k = w / SCENE_W
        return Illustration(
            image = image,
            source = id,
            items = plan.items,
            fit = plan.fit,
            look = look.id,
            subject = scene.subject?.scaled(k) ?: image.opaqueBounds() ?: Box(0f, 0f, w.toFloat(), h.toFloat()),
            signSlot = request.sign?.takeIf { it.isNotBlank() }?.let { scene.sign?.scaled(k) },
            shown = plan.shown,
            missing = plan.missing,
        )
    }

    private suspend fun best(query: String, kinds: Set<String>?): LibraryItem? =
        ranker.rank(query, library.items, kinds, 1).firstOrNull()?.takeIf { it.score >= floor }?.item

    /** Words a pose already shows in the figure's hands (a phone, a paper, a cup): no second copy as a prop. */
    private fun held(cast: Cast): List<String> = cast.poses.flatMap { HELD[it].orEmpty() }

    private fun coverage(request: IllustrationRequest, cast: Cast, items: List<LibraryItem>): Pair<List<String>, List<String>> {
        val wanted = (Words.words(request.subject) + request.objects.flatMap(Words::words) + Words.words(request.place)).distinct()
        val keys = items.flatMap { it.keys } + cast.words + PeopleWords.PERSON.takeIf { !cast.isEmpty }.orEmpty() +
            cast.poses.flatMap { HELD[it].orEmpty() } + cast.faces.takeIf { it.isNotEmpty() }?.let { feelingWords(request) }.orEmpty()
        val (shown, missing) = wanted.partition { t -> Words.best(t, keys) > 0f || t in MODIFIERS }
        return shown to missing
    }

    private fun feelingWords(request: IllustrationRequest): List<String> = Words.terms("${request.subject} ${request.mood}")

    private class Scene(val drawing: Drawing, val subject: Box?, val sign: Box?, val accentParts: List<String>)

    /** Lays the plan out in scene units: [SCENE_W] wide, [SCENE_W] / [aspect] high. */
    private fun compose(plan: Plan, aspect: Float): Scene {
        val sw = SCENE_W
        val sh = SCENE_W / aspect
        var drawing = Drawing.Empty
        var subject: Box? = null
        var sign: Box? = null
        val people = plan.peeps.map { People.draw(it, library) }
        plan.place?.let { place ->
            val box = if (people.isEmpty()) Box(sw * 0.05f, sh * 0.08f, sw * 0.95f, sh * 0.92f) else Box(sw * 0.04f, sh * 0.04f, sw * 0.96f, sh * 0.62f)
            val (d, b) = fit(place.drawing.withPart("place:${place.src}"), box, 1f)
            drawing += d
            sign = Box(b.left + b.width * SIGN_INSET, b.top + b.height * SIGN_TOP, b.right - b.width * SIGN_INSET, b.top + b.height * SIGN_BOTTOM)
            if (people.isEmpty() && plan.hero == null) subject = b
        }
        if (people.isNotEmpty()) {
            val area = when {
                plan.place != null -> Box(sw * 0.06f, sh * 0.30f, sw * 0.94f, sh)
                plan.props.isNotEmpty() -> Box(sw * 0.02f, sh * 0.04f, sw * 0.64f, sh)
                else -> Box(sw * 0.08f, sh * 0.03f, sw * 0.92f, sh)
            }
            val (d, b) = row(people, area)
            drawing += d
            subject = b
        }
        plan.hero?.let { hero ->
            val box = if (plan.props.isEmpty()) Box(sw * 0.12f, sh * 0.12f, sw * 0.88f, sh * 0.88f) else Box(sw * 0.14f, sh * 0.03f, sw * 0.86f, sh * 0.80f)
            val (d, b) = fit(hero.drawing.withPart("hero:${hero.src}"), box, 0.5f)
            drawing += d
            subject = b
            if (plan.place == null) sign = Box(b.left + b.width * 0.22f, b.top + b.height * 0.38f, b.right - b.width * 0.22f, b.top + b.height * 0.56f)
        }
        plan.props.forEachIndexed { i, prop ->
            val box = when {
                people.isNotEmpty() && plan.place == null -> Box(sw * 0.62f, sh * (0.18f + i * 0.36f), sw * 0.98f, sh * (0.5f + i * 0.36f))
                // In front of the hero, over its lower part (gold bars before a safe, a coin before a chart).
                plan.hero != null && plan.props.size == 1 -> Box(sw * 0.50f, sh * 0.58f, sw * 0.94f, sh * 0.98f)
                plan.hero != null -> Box(sw * (0.04f + i * 0.58f), sh * 0.62f, sw * (0.38f + i * 0.58f), sh * 0.98f)
                else -> Box(sw * (0.06f + i * 0.6f), sh * 0.70f, sw * (0.34f + i * 0.6f), sh * 0.98f)
            }
            drawing += fit(prop.drawing.withPart("prop:${prop.src}"), box, 1f).first
        }
        val accent = listOfNotNull(plan.hero?.let { "hero:${it.src}" }) + plan.props.map { "prop:${it.src}" }
        return Scene(drawing, subject, sign, accent)
    }

    /** People side by side, the same height, overlapping a little, standing on the bottom of [area]. */
    private fun row(people: List<Drawing>, area: Box): Pair<Drawing, Box> {
        val bounds = people.map { it.bounds ?: Box(0f, 0f, 1f, 1f) }
        val n = people.size
        val overlap = if (n > 1) OVERLAP else 0f
        val unitW = bounds.maxOf { it.width / it.height }
        val total = unitW * (n - overlap * (n - 1))
        val hgt = min(area.height, area.width / total)
        var x = area.centerX - total * hgt / 2
        var out = Drawing.Empty
        var box: Box? = null
        // Middle figure in front: draw outer ones first.
        val order = (0 until n).sortedByDescending { kotlin.math.abs(it - (n - 1) / 2f) }
        val xs = FloatArray(n)
        for (i in 0 until n) {
            xs[i] = x
            x += unitW * hgt * (1 - overlap)
        }
        for (i in order) {
            val b = bounds[i]
            val s = hgt / b.height
            val dx = xs[i] + (unitW * hgt - b.width * s) / 2 - b.left * s
            val dy = area.bottom - b.bottom * s
            out += people[i].transformed(Affine(s, 0f, 0f, s, dx, dy), "person:$i")
            val placed = Box(b.left * s + dx, b.top * s + dy, b.right * s + dx, b.bottom * s + dy)
            box = box?.union(placed) ?: placed
        }
        return out to (box ?: area)
    }

    /** [d] scaled into [box] keeping its aspect, centred horizontally, at [anchorY] vertically. */
    private fun fit(d: Drawing, box: Box, anchorY: Float): Pair<Drawing, Box> {
        val b = d.bounds ?: return Drawing.Empty to box
        val s = min(box.width / b.width, box.height / b.height)
        val dx = box.centerX - b.centerX * s
        val dy = box.top + (box.height - b.height * s) * anchorY - b.top * s
        return d.transformed(Affine(s, 0f, 0f, s, dx, dy)) to Box(b.left * s + dx, b.top * s + dy, b.right * s + dx, b.bottom * s + dy)
    }

    private fun Drawing.withPart(part: String) = transformed(Affine.Identity, part)

    companion object {
        const val SCENE_W = 1000f
        const val DEFAULT_FLOOR = 0.3f
        private const val NEUTRAL_FIT = 0.5f
        private const val MAX_PROPS = 2
        private const val OVERLAP = 0.18f
        private const val SIGN_INSET = 0.18f
        private const val SIGN_TOP = 0.08f
        private const val SIGN_BOTTOM = 0.26f
        private const val MIN_SATURATION = 0.35f
        private const val HUE_BIN = 30f
        private const val HUE_SPREAD = 28f
        private val PLACES = setOf("place", "nature")
        private val OBJECTS = setOf("object", "symbol", "food", "health", "transport", "nature")

        /** Words of request text that only qualify (sizes, ages), never a missing picture. */
        private val MODIFIERS = setOf("young", "old", "big", "small", "new", "جوان", "پیر", "بزرگ", "کوچک", "جدید")

        private val HELD = mapOf(
            "bust/Device" to listOf("phone", "mobile", "smartphone", "گوشی", "موبایل", "تلفن", "app", "اپ"),
            "bust/Paper" to listOf("paper", "document", "report", "letter", "کاغذ", "سند", "گزارش", "نامه", "برگه", "قرارداد"),
            "bust/Coffee" to listOf("coffee", "tea", "cup", "قهوه", "چای", "فنجان"),
        ).mapValues { (_, v) -> v.map(io.trimio.core.model.text.TextKey::of) }

        /** The size of an illustration of long side [size] and [aspect] (width/height). */
        fun frame(size: Int, aspect: Float): Pair<Int, Int> =
            if (aspect >= 1f) size to max(1, (size / aspect).toInt()) else max(1, (size * aspect).toInt()) to size

        /**
         * Where the accent paints: in the shapes of [part] whose colour is saturated and of its main hue
         * (the gold of a coin, the body of a phone), so one object carries the film's accent.
         */
        fun accentMask(shapes: List<io.trimio.engine.illustration.vector.Shape>, regions: IntArray, part: String?, w: Int, h: Int): FloatArray? {
            if (part == null) return null
            val candidates = shapes.withIndex().filter { (_, s) ->
                s.part == part && s.fill?.let { Colors.saturation(it) >= MIN_SATURATION && Colors.a(it) > 0 } == true
            }
            if (candidates.isEmpty()) return null
            val area = HashMap<Int, Float>()
            for ((_, s) in candidates) {
                val bin = (Colors.hue(s.fill!!) / HUE_BIN).toInt()
                area[bin] = (area[bin] ?: 0f) + (s.bounds?.let { it.width * it.height } ?: 0f)
            }
            val mainBin = area.maxByOrNull { it.value }?.key ?: return null
            val mainHue = (mainBin + 0.5f) * HUE_BIN
            val chosen = candidates.filter { (_, s) -> Colors.hueDistance(Colors.hue(s.fill!!), mainHue) <= HUE_SPREAD }.map { it.index }.toSet()
            val mask = FloatArray(w * h)
            var any = false
            for (i in regions.indices) if (regions[i] in chosen) {
                mask[i] = 1f; any = true
            }
            return mask.takeIf { any }
        }
    }
}
