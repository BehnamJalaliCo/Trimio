package io.trimio.engine.illustration.library

import io.trimio.core.model.text.TextKey
import io.trimio.engine.illustration.resources.Res
import io.trimio.engine.illustration.vector.Drawing
import io.trimio.engine.illustration.vector.Svg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One drawable object or place of the library, with its English and Persian keywords. */
class LibraryItem(
    val id: String,
    /** "fluent:bank": set and name. */
    val src: String,
    /** object, place, symbol, food, health, transport, nature. */
    val kind: String,
    val en: List<String>,
    val fa: List<String>,
    val width: Float,
    val height: Float,
    private val svg: String,
) {
    /** Folded keywords for matching. */
    val keys: Set<String> by lazy { (en + fa).map(TextKey::of).filter { it.isNotEmpty() }.toSet() }

    /** The drawing, in its own units (0..[width], 0..[height]). */
    val drawing: Drawing by lazy { Svg.parse(svg, tolerance = width / DETAIL, part = src) }

    val isPlace: Boolean get() = kind == "place"

    private companion object {
        /** Flattening fine enough for a 2000-pixel rendering. */
        const val DETAIL = 2000f
    }
}

/** A set of drawings an [io.trimio.engine.illustration.IllustrationSource] composes from: Open Peeps pieces and objects. */
class Library(val people: Map<String, String>, val items: List<LibraryItem>, val licences: Map<String, String> = emptyMap()) {
    private val parsed = HashMap<String, Drawing>()

    fun piece(key: String): Drawing? = people[key]?.let { svg -> parsed.getOrPut(key) { Svg.parse(svg, tolerance = PEEP_TOLERANCE, part = key) } }

    fun has(key: String): Boolean = key in people

    fun item(id: String): LibraryItem? = items.firstOrNull { it.id == id }

    operator fun plus(o: Library): Library = Library(people + o.people, items + o.items, licences + o.licences)

    companion object {
        /** Open Peeps units are about 1000 per figure: half a unit is finer than a pixel at 2000 px. */
        private const val PEEP_TOLERANCE = 0.5f
        private val json = Json { ignoreUnknownKeys = true }

        val Empty = Library(emptyMap(), emptyList())

        /** The library bundled with the app (people.json, objects.json in this module's resources). */
        suspend fun bundled(): Library = parse(
            Res.readBytes("files/illustration/people.json").decodeToString(),
            Res.readBytes("files/illustration/objects.json").decodeToString(),
        )

        /** A library from the two index documents (the bundled ones, or a downloaded illustration pack's). */
        fun parse(people: String?, objects: String?): Library {
            val pieces = people?.let { p ->
                json.parseToJsonElement(p).jsonObject["pieces"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            }.orEmpty()
            val root = objects?.let { json.parseToJsonElement(it).jsonObject }
            val items = root?.get("items")?.jsonArray?.map { e ->
                val o = e.jsonObject
                LibraryItem(
                    id = o.str("id"), src = o.str("src"), kind = o.str("kind"),
                    en = o["en"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    fa = o["fa"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    width = o["w"]?.jsonPrimitive?.float ?: DEFAULT_UNITS, height = o["h"]?.jsonPrimitive?.float ?: DEFAULT_UNITS,
                    svg = o.str("svg"),
                )
            }.orEmpty()
            val licences = root?.get("sources")?.jsonObject?.mapValues { (_, v) -> (v as? JsonObject)?.str("licence").orEmpty() }.orEmpty() +
                (if (pieces.isNotEmpty()) mapOf("open-peeps" to "CC0-1.0") else emptyMap())
            return Library(pieces, items, licences)
        }

        private const val DEFAULT_UNITS = 32f

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()
    }
}
