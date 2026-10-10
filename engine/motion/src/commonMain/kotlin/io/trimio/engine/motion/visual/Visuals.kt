package io.trimio.engine.motion.visual

import androidx.compose.ui.graphics.Color
import io.trimio.core.model.text.TextKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How one layer of a vector icon is painted. */
sealed interface Paint {
    data class Solid(val color: Color) : Paint
    /** The node's tint (single-colour icon sets). */
    data object Current : Paint
    /**
     * A gradient in its own space: [coords] are x1,y1,x2,y2 (linear) or cx,cy,r,fx,fy (radial),
     * mapped into the icon's space by [matrix] (a, b, c, d, e, f as in SVG).
     */
    data class Gradient(val linear: Boolean, val coords: FloatArray, val matrix: FloatArray, val stops: List<Pair<Float, Color>>) : Paint
}

/** One layer: an SVG path in the icon's viewport, filled (or stroked when [strokeWidth] > 0). */
data class VectorLayer(val d: String, val paint: Paint, val opacity: Float, val evenOdd: Boolean, val strokeWidth: Float)

/** A flattened vector icon from the visual vocabulary. */
data class VectorIcon(val name: String, val set: String, val width: Float, val height: Float, val layers: List<VectorLayer>, val keywords: List<String>) {
    /** "set:name", unique across the vocabulary. */
    val id: String get() = "$set:$name"

    /** Colourful icons carry their own palette; others take the look's ink. */
    val colourful: Boolean get() = layers.any { it.paint !is Paint.Current }
}

/**
 * The visual vocabulary: ~18 000 open icons across every domain — colourful objects, food,
 * people and places (Fluent Emoji), medicine and the body (Health Icons), vehicles, buildings,
 * tools and finance (Material Design Icons), and outline icons for everything else (Tabler).
 *
 * Search takes an English visual query (the director's understanding step translates "گوشی
 * پزشکی" to "stethoscope") and ranks by keyword match, then by the set that suits the concept.
 */
class VisualLibrary private constructor(private val icons: List<VectorIcon>) {

    private val byKey: Map<String, List<VectorIcon>> = buildMap<String, MutableList<VectorIcon>> {
        for (icon in icons) for (k in icon.keywords + icon.name) getOrPut(key(k)) { mutableListOf() } += icon
    }

    val size: Int get() = icons.size

    private val byId: Map<String, VectorIcon> = icons.associateBy { it.id }

    fun named(set: String, name: String): VectorIcon? = byId["$set:$name"]

    /** This vocabulary plus [more] (pictures found online for this piece). */
    fun withIcons(more: List<VectorIcon>): VisualLibrary = if (more.isEmpty()) this else VisualLibrary(icons + more.filter { it.id !in byId })

    /**
     * Best icon for [query]. Exact name and keyword hits win; otherwise words are matched one by
     * one. [prefer] orders the sets ("fluent" first for friendly colourful objects, "health" for
     * medicine…); [colourful] restricts to icons with their own colours.
     */
    fun find(query: String, prefer: List<String> = DEFAULT_ORDER, colourful: Boolean? = null): VectorIcon? =
        search(query, prefer, colourful).firstOrNull()

    fun search(query: String, prefer: List<String> = DEFAULT_ORDER, colourful: Boolean? = null, limit: Int = 5): List<VectorIcon> {
        // An exact pick ("fluent:automobile"), as the director's candidate choice returns it.
        byId[query.trim()]?.let { return listOf(it) }
        val q = key(query)
        if (q.isEmpty()) return emptyList()
        canonical[q]?.let { (set, name) -> named(set, name)?.let { if (colourful == null || it.colourful == colourful) return listOf(it) } }
        val words = query.lowercase().split(' ', '-', '_').filter { it.length > 1 }.map(::key)
        val scored = HashMap<VectorIcon, Float>()
        fun add(icon: VectorIcon, score: Float) {
            if (colourful != null && icon.colourful != colourful) return
            scored[icon] = maxOf(scored[icon] ?: 0f, score)
        }
        fun relevance(icon: VectorIcon, k: String): Float = when {
            key(icon.name) == k -> EXACT_NAME
            icon.name.split('-').any { key(it) == k } -> NAME_WORD
            else -> TAG
        }
        byKey[q]?.forEach { add(it, relevance(it, q)) }
        // Phrases: every query word found counts, weighted by where it was found.
        if (words.size > 1) for (w in words) byKey[w]?.forEach { icon ->
            // English phrases end in their head noun ("car engine" is an engine): it weighs double.
            add(icon, words.withIndex().sumOf { (i, ww) ->
                val found = icon.keywords.any { key(it) == ww } || key(icon.name).contains(ww)
                if (found) relevance(icon, ww).toDouble() * if (i == words.lastIndex) 2.0 else 1.0 else 0.0
            }.toFloat() * WORD)
        }
        val order = prefer.withIndex().associate { it.value to it.index }
        // The preferred set dominates: a colourful match beats a monochrome exact name.
        for ((icon, sc) in scored.entries.toList()) scored[icon] = sc + SET_STEP * (prefer.size - (order[icon.set] ?: prefer.size))
        // Ties go to the more specific icon (fewer keywords), then the shorter name.
        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<VectorIcon, Float>> { it.value }.thenBy { it.key.keywords.size }.thenBy { it.key.name.length })
            .take(limit).map { it.key }
    }

    /** Everyday concepts whose best picture is not the literal keyword match. */
    private val canonical: Map<String, Pair<String, String>> = listOf(
        "car" to "automobile", "vehicle" to "automobile", "heart" to "red-heart", "love" to "red-heart", "phone" to "mobile-phone",
        "smartphone" to "mobile-phone", "money" to "money-bag", "cash" to "dollar-banknote", "home" to "house", "doctor" to "health-worker",
        "nurse" to "health-worker", "medicine" to "pill", "hospital" to "hospital", "fire" to "fire", "hot" to "fire", "time" to "alarm-clock",
        "clock" to "alarm-clock", "idea" to "light-bulb", "growth" to "chart-increasing", "increase" to "chart-increasing", "profit" to "chart-increasing",
        "decrease" to "chart-decreasing", "loss" to "chart-decreasing", "success" to "check-mark-button", "done" to "check-mark-button",
        "check" to "check-mark-button", "warning" to "warning", "danger" to "warning", "question" to "red-question-mark", "target" to "direct-hit",
        "goal" to "direct-hit", "gift" to "wrapped-gift", "star" to "star", "celebration" to "party-popper", "party" to "party-popper",
        "computer" to "laptop", "laptop" to "laptop", "email" to "e-mail", "message" to "speech-balloon", "chat" to "speech-balloon",
        "food" to "fork-and-knife-with-plate", "kitchen" to "cooking", "cook" to "cook", "coffee" to "hot-beverage", "tea" to "teacup-without-handle",
        "workout" to "flexed-biceps", "strong" to "flexed-biceps", "gym" to "flexed-biceps", "sleep" to "sleeping-face", "happy" to "grinning-face",
        "sad" to "crying-face", "angry" to "pouting-face", "think" to "thinking-face", "search" to "magnifying-glass-tilted-left",
        "location" to "round-pushpin", "map" to "world-map", "world" to "globe-showing-europe-africa", "education" to "graduation-cap",
        "school" to "school", "baby" to "baby", "family" to "family", "key" to "key", "lock" to "locked", "security" to "locked",
        "engine" to "gear", "settings" to "gear", "tool" to "hammer-and-wrench", "repair" to "hammer-and-wrench", "build" to "building-construction",
        "house price" to "house", "real estate" to "house-with-garden", "rent" to "house", "bitcoin" to "coin", "gold" to "1st-place-medal",
        "winner" to "trophy", "trophy" to "trophy", "rocket" to "rocket", "launch" to "rocket", "fast" to "high-voltage", "energy" to "high-voltage",
    ).associate { (k, v) -> key(k) to ("fluent" to v) }

    companion object {
        val DEFAULT_ORDER = listOf("fluent", "health", "mdi", "tabler")
        private const val EXACT_NAME = 2f
        private const val NAME_WORD = 1.8f
        private const val TAG = 1.2f
        private const val WORD = 0.8f
        private const val SET_STEP = 0.6f

        fun key(s: String) = TextKey.of(s).replace(" ", "")

        /** Parses packs written by tools/visuals/build_visuals.py: set name → JSON text. */
        fun parse(packs: Map<String, String>): VisualLibrary {
            val json = Json { ignoreUnknownKeys = true }
            val icons = packs.flatMap { (set, text) ->
                json.parseToJsonElement(text).jsonObject.map { (name, v) -> icon(set, name, v.jsonObject) }
            }
            return VisualLibrary(icons)
        }

        val Empty = VisualLibrary(emptyList())

        private fun icon(set: String, name: String, o: JsonObject): VectorIcon {
            val v = o.getValue("v").jsonArray
            val layers = o.getValue("e").jsonArray.map { e ->
                val a = e.jsonArray
                VectorLayer(
                    d = a[0].jsonPrimitive.content,
                    paint = paint(a[1]),
                    opacity = a[2].jsonPrimitive.float,
                    evenOdd = a[3].jsonPrimitive.int == 1,
                    strokeWidth = a[4].jsonPrimitive.float,
                )
            }
            return VectorIcon(name, set, v[0].jsonPrimitive.float, v[1].jsonPrimitive.float, layers, o.getValue("k").jsonArray.map { it.jsonPrimitive.content })
        }

        private fun paint(p: kotlinx.serialization.json.JsonElement): Paint = when (p) {
            is JsonPrimitive -> if (p.content == "cur") Paint.Current else Paint.Solid(color(p.content))
            is JsonObject -> Paint.Gradient(
                linear = p.getValue("t").jsonPrimitive.content == "l",
                coords = p.getValue("c").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                matrix = p.getValue("m").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
                stops = p.getValue("s").jsonArray.map { s -> (s as JsonArray).let { it[0].jsonPrimitive.float to color(it[1].jsonPrimitive.content) } },
            )
            else -> Paint.Current
        }

        private fun color(hex: String): Color {
            val h = hex.removePrefix("#")
            val rgb = h.take(6).toLong(16)
            val a = if (h.length >= 8) h.substring(6, 8).toInt(16) else 255
            return Color(((a.toLong() shl 24) or rgb).toInt())
        }
    }
}
