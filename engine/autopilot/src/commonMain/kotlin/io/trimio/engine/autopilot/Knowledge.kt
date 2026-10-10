package io.trimio.engine.autopilot

import androidx.compose.ui.graphics.Color
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.visual.Paint
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VectorLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the edit can learn from outside the device: marks for brands the bundled pack lacks,
 * pictures the bundled vocabulary lacks, one-line descriptions of people and products, and live
 * prices. Every answer is optional; offline, the edit is made from the bundled packs alone.
 */
interface Knowledge {
    /** A brand's official mark (24-unit path and colour), if one is published openly. */
    suspend fun brand(name: String): BrandLibrary.Entry? = null

    /** Single-colour or flat icons for an English query, best first. */
    suspend fun icons(query: String, limit: Int = 4): List<VectorIcon> = emptyList()

    /** A short description ("AI coding agent by OpenAI") in [language] ("fa", "en"). */
    suspend fun describe(name: String, language: String): String? = null

    /** Price in USD and 24 h change in percent for a crypto asset named or symbolised [name]. */
    suspend fun price(name: String): Price? = null

    data class Price(val symbol: String, val usd: Double, val change24h: Double)

    object Offline : Knowledge
}

/** Small string cache so a piece edited twice does not fetch twice (memory, or disk on desktop). */
interface KnowledgeCache {
    fun get(key: String): String?
    fun put(key: String, value: String)

    class Memory : KnowledgeCache {
        private val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }
}

/**
 * [Knowledge] from open sources: Simple Icons (CC0 brand marks, through jsDelivr), Iconify (open
 * icon sets), Wikipedia summaries and CoinGecko prices. Failures are silent: a missing answer only
 * means the bundled packs decide.
 */
class WebKnowledge(
    private val http: HttpClient,
    private val cache: KnowledgeCache = KnowledgeCache.Memory(),
    private val iconSets: List<String> = listOf("noto", "fluent-emoji-flat", "healthicons", "mdi", "tabler"),
) : Knowledge {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun fetch(url: String): String? {
        cache.get(url)?.let { return it.ifEmpty { null } }
        val text = runCatching {
            val r = http.get(url) { header("User-Agent", "Trimio/1.0 (video editor)") }
            if (r.status.isSuccess()) r.bodyAsText() else ""
        }.getOrNull() ?: return null // network errors are not cached
        cache.put(url, text)
        return text.ifEmpty { null }
    }

    override suspend fun brand(name: String): BrandLibrary.Entry? {
        val index = fetch("$SIMPLE_ICONS/_data/simple-icons.json")?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return null
        val list = (index as? JsonObject)?.get("icons")?.jsonArray ?: index as? kotlinx.serialization.json.JsonArray ?: return null
        val k = BrandLibrary.key(name)
        val hit = list.map { it.jsonObject }.firstOrNull { o ->
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            val aliases = o["aliases"]?.jsonObject?.get("aka")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            BrandLibrary.key(title) == k || aliases.any { BrandLibrary.key(it) == k } || o["slug"]?.jsonPrimitive?.contentOrNull == k
        } ?: return null
        val title = hit.getValue("title").jsonPrimitive.content
        val slug = hit["slug"]?.jsonPrimitive?.contentOrNull ?: slugOf(title)
        val svg = fetch("$SIMPLE_ICONS/icons/$slug.svg") ?: return null
        val d = Regex("""\sd="([^"]+)"""").find(svg)?.groupValues?.get(1) ?: return null
        return BrandLibrary.Entry(title, hit["hex"]?.jsonPrimitive?.contentOrNull ?: "111111", d)
    }

    override suspend fun icons(query: String, limit: Int): List<VectorIcon> {
        val q = query.trim().ifEmpty { return emptyList() }
        val found = fetch("$ICONIFY/search?query=${q.encodeURLQueryComponent()}&limit=32&prefixes=${iconSets.joinToString(",")}") ?: return emptyList()
        val names = runCatching { json.parseToJsonElement(found).jsonObject.getValue("icons").jsonArray.map { it.jsonPrimitive.content } }.getOrNull() ?: return emptyList()
        // Prefer the sets in the order given (colourful first), then Iconify's own relevance.
        val ordered = names.sortedBy { n -> iconSets.indexOf(n.substringBefore(':')).let { if (it < 0) iconSets.size else it } }
        val out = mutableListOf<VectorIcon>()
        for (full in ordered) {
            if (out.size >= limit) break
            val (set, name) = full.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val data = fetch("$ICONIFY/${set.encodeURLPathPart()}.json?icons=${name.encodeURLQueryComponent()}") ?: continue
            val icon = runCatching { iconOf(set, name, json.parseToJsonElement(data).jsonObject, q) }.getOrNull() ?: continue
            out += icon
        }
        return out
    }

    private fun iconOf(set: String, name: String, data: JsonObject, query: String): VectorIcon? {
        val body = data["icons"]?.jsonObject?.get(name)?.jsonObject ?: return null
        val w = (body["width"] ?: data["width"])?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 16f
        val h = (body["height"] ?: data["height"])?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 16f
        val layers = SvgLayers.parse(body.getValue("body").jsonPrimitive.content) ?: return null
        if (layers.isEmpty()) return null
        return VectorIcon(name, "web-$set", w, h, layers, listOf(query) + name.split('-'))
    }

    override suspend fun describe(name: String, language: String): String? {
        val text = fetch("https://$language.wikipedia.org/api/rest_v1/page/summary/${name.replace(' ', '_').encodeURLPathPart()}") ?: return null
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (o["type"]?.jsonPrimitive?.contentOrNull == "disambiguation") return null
        return o["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 3..60 }
    }

    override suspend fun price(name: String): Knowledge.Price? {
        val search = fetch("$COINGECKO/search?query=${name.encodeURLQueryComponent()}") ?: return null
        val coin = runCatching { json.parseToJsonElement(search).jsonObject.getValue("coins").jsonArray.firstOrNull()?.jsonObject }.getOrNull() ?: return null
        val id = coin["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val symbol = coin["symbol"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: return null
        val prices = fetch("$COINGECKO/simple/price?ids=$id&vs_currencies=usd&include_24hr_change=true") ?: return null
        val p = runCatching { json.parseToJsonElement(prices).jsonObject.getValue(id).jsonObject }.getOrNull() ?: return null
        val usd = p["usd"]?.jsonPrimitive?.doubleOrNull ?: return null
        return Knowledge.Price(symbol, usd, p["usd_24h_change"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
    }

    private companion object {
        const val SIMPLE_ICONS = "https://cdn.jsdelivr.net/npm/simple-icons@13.21.0"
        const val ICONIFY = "https://api.iconify.design"
        const val COINGECKO = "https://api.coingecko.com/api/v3"

        fun slugOf(title: String) = title.lowercase().replace("+", "plus").replace(".", "dot").replace("&", "and").filter { it.isLetterOrDigit() }
    }
}

/**
 * A minimal SVG body reader for icon-set glyphs: paths and basic shapes with solid fills or
 * strokes, `currentColor`, opacity and group inheritance. Gradients become their middle colour;
 * glyphs that need transforms, masks, clips or filters are refused (null) rather than drawn wrong.
 */
object SvgLayers {
    private val element = Regex("""<(path|circle|ellipse|rect|polygon|polyline|line|g|/g)\b([^>]*?)/?>""")
    private val attribute = Regex("""([a-zA-Z-]+)="([^"]*)"""")

    fun parse(body: String): List<VectorLayer>? {
        if (listOf("transform", "<mask", "clip-path", "<filter", "<use", "<image", "<text").any { it in body }) return null
        val gradients = gradientColours(body)
        val stack = ArrayDeque<Map<String, String>>().apply { addLast(emptyMap()) }
        val out = mutableListOf<VectorLayer>()
        for (m in element.findAll(body.substringBefore("<defs").let { pre -> pre + body.substringAfter("</defs>", "") })) {
            val tag = m.groupValues[1]
            val attrs = attribute.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] } + style(m.groupValues[2])
            when (tag) {
                "g" -> if (!m.value.endsWith("/>")) stack.addLast(stack.last() + attrs.filterKeys { it in INHERITED })
                "/g" -> if (stack.size > 1) stack.removeLast()
                else -> {
                    val a = stack.last() + attrs
                    val d = shapePath(tag, a) ?: continue
                    val opacity = (a["opacity"]?.toFloatOrNull() ?: 1f)
                    val fill = a["fill"] ?: if (tag == "line" || tag == "polyline") "none" else "black"
                    paint(fill, gradients)?.let { out += VectorLayer(d, it, opacity * (a["fill-opacity"]?.toFloatOrNull() ?: 1f), a["fill-rule"] == "evenodd", 0f) }
                    val stroke = a["stroke"]
                    if (stroke != null && stroke != "none") {
                        val sw = a["stroke-width"]?.toFloatOrNull() ?: 1f
                        paint(stroke, gradients)?.let { out += VectorLayer(d, it, opacity * (a["stroke-opacity"]?.toFloatOrNull() ?: 1f), false, sw) }
                    }
                }
            }
        }
        return out
    }

    private val INHERITED = setOf("fill", "stroke", "stroke-width", "fill-rule", "opacity", "stroke-linecap", "stroke-linejoin")

    private fun style(attrs: String): Map<String, String> {
        val s = Regex("""style="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: return emptyMap()
        return s.split(';').mapNotNull { kv -> kv.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
    }

    private fun paint(value: String, gradients: Map<String, Color>): Paint? = when {
        value == "none" || value == "transparent" -> null
        value == "currentColor" -> Paint.Current
        value.startsWith("url(") -> gradients[value.substringAfter('#').substringBefore(')')]?.let { Paint.Solid(it) }
        else -> colour(value)?.let { Paint.Solid(it) }
    }

    private fun gradientColours(body: String): Map<String, Color> = Regex("""<(linearGradient|radialGradient)[^>]*id="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(body).mapNotNull { m ->
            val stops = Regex("""stop-color="([^"]+)"""").findAll(m.groupValues[3]).mapNotNull { colour(it.groupValues[1]) }.toList()
            stops.getOrNull(stops.size / 2)?.let { m.groupValues[2] to it }
        }.toMap()

    private fun colour(v: String): Color? {
        val h = v.trim().removePrefix("#")
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            6 -> h
            else -> return NAMED[v.trim().lowercase()]
        }
        return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    private val NAMED = mapOf("black" to Color.Black, "white" to Color.White, "red" to Color.Red, "#000" to Color.Black)

    private fun f(a: Map<String, String>, k: String) = a[k]?.removeSuffix("px")?.toFloatOrNull() ?: 0f

    private fun shapePath(tag: String, a: Map<String, String>): String? = when (tag) {
        "path" -> a["d"]?.takeIf { it.isNotBlank() }
        "circle" -> ellipse(f(a, "cx"), f(a, "cy"), f(a, "r"), f(a, "r"))
        "ellipse" -> ellipse(f(a, "cx"), f(a, "cy"), f(a, "rx"), f(a, "ry"))
        "rect" -> {
            val x = f(a, "x"); val y = f(a, "y"); val w = f(a, "width"); val h = f(a, "height")
            val rx = (a["rx"] ?: a["ry"])?.toFloatOrNull()?.coerceAtMost(minOf(w, h) / 2) ?: 0f
            if (rx > 0f) {
                "M${x + rx} ${y}H${x + w - rx}A$rx $rx 0 0 1 ${x + w} ${y + rx}V${y + h - rx}A$rx $rx 0 0 1 ${x + w - rx} ${y + h}" +
                    "H${x + rx}A$rx $rx 0 0 1 $x ${y + h - rx}V${y + rx}A$rx $rx 0 0 1 ${x + rx} ${y}Z"
            } else {
                "M$x ${y}H${x + w}V${y + h}H${x}Z"
            }
        }
        "polygon", "polyline" -> a["points"]?.trim()?.split(Regex("[\\s,]+"))?.chunked(2)?.filter { it.size == 2 }
            ?.joinToString("L", "M", if (tag == "polygon") "Z" else "") { "${it[0]} ${it[1]}" }
        "line" -> "M${f(a, "x1")} ${f(a, "y1")}L${f(a, "x2")} ${f(a, "y2")}"
        else -> null
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = if (rx <= 0f || ry <= 0f) null else
        "M${cx - rx} ${cy}A$rx $ry 0 1 0 ${cx + rx} ${cy}A$rx $ry 0 1 0 ${cx - rx} ${cy}Z"
}
