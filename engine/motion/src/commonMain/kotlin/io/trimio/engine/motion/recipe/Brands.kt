package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.resources.Res
import io.trimio.engine.motion.score.NumberWords
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** A brand's mark: its official glyph (24-unit SVG path) and colour, or a monogram when none is known. */
data class BrandMark(val slug: String, val title: String, val color: Color, val path: String?) {
    val isMonogram: Boolean get() = path == null

    /** Up to three letters for a monogram tile ("ChatGPT" → "GPT", "Open Code" → "OC"). */
    val monogram: String
        get() {
            val words = title.split(' ', '-').filter { it.isNotEmpty() }
            val caps = title.filter { it.isUpperCase() }
            return when {
                words.size > 1 -> words.take(3).joinToString("") { it.first().uppercase() }
                caps.length in 2..3 -> caps
                caps.length > 3 -> caps.takeLast(3)
                else -> title.take(1).uppercase()
            }
        }
}

/**
 * Brand marks by name. The bundled core pack (from Simple Icons, CC0) covers AI and developer
 * tools, crypto and trading, social platforms and big tech; Persian and common aliases resolve
 * too ("کلاد کد", "btc"). Brands without a free mark get a monogram in their known colour; the
 * knowledge layer can add marks found online at run time with [withMarks].
 */
class BrandLibrary(private val marks: Map<String, Entry>) {

    @Serializable
    data class Entry(val t: String, val c: String, val d: String, val a: List<String> = emptyList())

    private val index: Map<String, String> = buildMap {
        for ((slug, e) in marks) {
            put(key(slug), slug)
            put(key(e.t), slug)
            e.a.forEach { put(key(it), slug) }
        }
    }

    /** The mark for [name], if the library knows the brand (official glyph or known colour). */
    fun find(name: String): BrandMark? {
        val k = key(name)
        index[k]?.let { slug -> return marks.getValue(slug).let { BrandMark(slug, it.t, hex(it.c), it.d) } }
        return Known[k]
    }

    /** Always a mark: the official one, or a monogram in a colour derived from the name. */
    fun mark(name: String): BrandMark = find(name) ?: BrandMark(key(name), name.trim(), hashColor(name), null)

    fun withMarks(more: Map<String, Entry>) = BrandLibrary(marks + more)

    val size: Int get() = marks.size

    companion object {
        val Empty = BrandLibrary(emptyMap())

        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalResourceApi::class)
        suspend fun load(): BrandLibrary =
            BrandLibrary(json.decodeFromString<Map<String, Entry>>(Res.readBytes("files/brands/core.json").decodeToString()))

        fun key(s: String) = NumberWords.normalize(s).replace(" ", "").replace("-", "").replace(".", "")

        private fun hex(s: String) = Color(0xFF000000 or s.toLong(16))

        /** Brands whose marks are not free to bundle: shown as monograms in their own colours. */
        private val Known: Map<String, BrandMark> = listOf(
            BrandMark("chatgpt", "ChatGPT", Color(0xFF10A37F), null),
            BrandMark("openai", "OpenAI", Color(0xFF0D0D0D), null),
            BrandMark("codex", "Codex", Color(0xFF0D0D0D), PROMPT),
            BrandMark("visualstudiocode", "VS Code", Color(0xFF007ACC), null),
            BrandMark("linkedin", "LinkedIn", Color(0xFF0A66C2), null),
            BrandMark("bybit", "Bybit", Color(0xFFF7A600), null),
            BrandMark("metamask", "MetaMask", Color(0xFFF6851B), null),
            BrandMark("uniswap", "Uniswap", Color(0xFFFF007A), null),
        ).flatMap { m -> listOf(key(m.slug) to m, key(m.title) to m) }.toMap() + mapOf(
            key("چت جی پی تی") to BrandMark("chatgpt", "ChatGPT", Color(0xFF10A37F), null),
            key("کدکس") to BrandMark("codex", "Codex", Color(0xFF0D0D0D), PROMPT),
        )

        /** A generic command-prompt glyph (not any company's mark) for terminal-based tools. */
        private const val PROMPT = "M3 5.5L10.5 12L3 18.5V15.6L7.2 12L3 8.4ZM12 16.5H21V19H12Z"

        private fun hashColor(s: String): Color {
            val palette = listOf(0xFF5B3DE8, 0xFF1580B8, 0xFFC23577, 0xFF0F9D58, 0xFFE8710A, 0xFF111111)
            return Color(palette[(s.hashCode() and 0x7FFFFFFF) % palette.size])
        }
    }
}
