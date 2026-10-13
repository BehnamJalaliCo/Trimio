package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.recipe.Icons
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VisualLibrary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finds a picture for a `visual` keyword: the art library (a still or animated icon), injected so
 * the stage stays free of how the library is loaded. [None] leaves every keyword to the stage's
 * own line glyphs.
 */
fun interface IconResolver {
    fun find(query: String): VectorIcon?

    companion object {
        val None = IconResolver { null }

        /** The visual vocabulary, single-colour sets first: tiles and chips tint them in the chapter's accent. */
        fun of(library: VisualLibrary, prefer: List<String> = listOf("tabler", "mdi", "health", "fluent")): IconResolver =
            IconResolver { q -> library.find(q, prefer, colourful = false) ?: library.find(q, prefer) }
    }
}

/** What a keyword resolved to: a library picture, or a line glyph on the 24-unit grid. */
internal sealed interface Glyph {
    data class Picture(val icon: VectorIcon) : Glyph
    data class Line(val path: String) : Glyph
}

/**
 * Line glyphs for the explainer's everyday metaphors (writing, analysis, a task, a graduate's
 * cap…), on the same 24-unit grid as [Icons], and clean geometric shapes when nothing matches.
 */
internal object Glyphs {
    private val paths = mapOf(
        "pencil" to "M4 20l4-1 11-11-3-3L5 16l-1 4zM14 6l3 3",
        "doc" to "M7 3h7l4 4v14H7zM14 3v4h4M10 12h5M10 16h5",
        "bulb" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z",
        "sparkles" to "M11 3l1.8 4.7 4.7 1.8-4.7 1.8L11 16l-1.8-4.7L4.5 9.5l4.7-1.8zM18 14l.8 2.2 2.2.8-2.2.8L18 20l-.8-2.2-2.2-.8 2.2-.8z",
        "nodes" to "M12 3a2.2 2.2 0 1 1 0 4.4A2.2 2.2 0 0 1 12 3zM5 16.6A2.2 2.2 0 1 1 5 21a2.2 2.2 0 0 1 0-4.4z" +
            "M19 16.6a2.2 2.2 0 1 1 0 4.4 2.2 2.2 0 0 1 0-4.4zM11 7.3l-5 9.4M13 7.3l5 9.4M7.2 18.8h9.6",
        "bars" to "M5 20v-7M10 20V6M15 20v-9M20 20V9M3 20h18",
        "book" to "M5 5a2 2 0 0 1 2-2h12v15H7a2 2 0 0 0-2 2zM5 20V5M9 7h6",
        "layers" to "M12 4l8 4.5-8 4.5-8-4.5zM4 12.5l8 4.5 8-4.5M4 16.5l8 4.5 8-4.5",
        "gauge" to "M4 17a8 8 0 1 1 16 0M12 17l4-5",
        "globe" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18",
        "flag" to "M5 21V4M5 4h12l-2.5 4 2.5 4H5",
        "compass" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15.5 8.5l-2 5-5 2 2-5z",
        "chip" to "M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4",
        "list" to "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
        "trophy" to "M8 4h8v5a4 4 0 0 1-8 0zM8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4M12 13v4M8 20h8M10 17h4",
        "cap" to "M12 5l10 4-10 4L2 9zM6 11v5c3 2 9 2 12 0v-5M22 9v5",
        "briefcase" to "M4 8h16v11H4zM9 8V5h6v3M4 13h16",
        "wand" to "M4 20L15 9M13 5v2M18 9h2M16.5 6.5L18 5M11 7l6 6",
        "plus" to "M12 5v14M5 12h14",
        "hourglass" to "M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9",
        "path" to "M5 19c4 0 4-7 7-7s3-7 7-7M17 3l2 2-2 2",
        "puzzle" to "M5 8h3a2 2 0 1 1 4 0h3v3a2 2 0 1 1 0 4v3h-3a2 2 0 1 0-4 0H5v-3a2 2 0 1 0 0-4z",
        "scale" to "M12 4v16M8 20h8M4 8h16M4 8l-2.5 6h5zM20 8l-2.5 6h5z",
        "tool" to "M14.5 6.5a4 4 0 0 0 5 5L12 19a2.1 2.1 0 0 1-3-3zM14.5 6.5L18 3",
    )

    private val aliases = mapOf(
        "write" to "pencil", "writing" to "pencil", "text" to "pencil", "pen" to "pencil", "edit" to "pencil", "draft" to "pencil", "essay" to "pencil",
        "analysis" to "bars", "analyze" to "bars", "analyse" to "bars", "data" to "bars", "statistics" to "bars", "numbers" to "bars", "graph" to "bars",
        "conversation" to "chat", "talk" to "chat", "speak" to "chat", "reply" to "chat", "answer" to "chat", "question" to "chat",
        "idea" to "bulb", "smart" to "bulb", "intelligent" to "bulb", "intelligence" to "bulb", "insight" to "bulb", "clever" to "bulb", "think" to "bulb",
        "creative" to "sparkles", "creativity" to "sparkles", "magic" to "wand", "sparkle" to "sparkles", "imagine" to "sparkles", "art" to "sparkles",
        "reasoning" to "nodes", "logic" to "nodes", "network" to "nodes", "connect" to "nodes", "connections" to "nodes", "brain" to "nodes",
        "learn" to "book", "read" to "book", "study" to "book", "knowledge" to "book", "docs" to "book",
        "intern" to "cap", "student" to "cap", "beginner" to "cap", "junior" to "cap", "graduate" to "cap", "school" to "cap", "newcomer" to "cap",
        "work" to "briefcase", "job" to "briefcase", "professional" to "briefcase", "business" to "briefcase", "office" to "briefcase", "career" to "briefcase",
        "expert" to "trophy", "master" to "trophy", "best" to "trophy", "winner" to "trophy", "champion" to "trophy", "award" to "trophy",
        "task" to "doc", "document" to "doc", "file" to "doc", "report" to "doc", "paper" to "doc", "note" to "doc",
        "slow" to "hourglass", "wait" to "hourglass", "waiting" to "hourglass", "patience" to "hourglass", "later" to "hourglass",
        "generalist" to "layers", "versatile" to "layers", "all-round" to "layers", "stack" to "layers", "levels" to "layers",
        "world" to "globe", "global" to "globe", "language" to "globe", "languages" to "globe", "goal" to "flag", "finish" to "flag",
        "fix" to "tool", "repair" to "tool", "tools" to "tool", "hardware" to "chip", "compute" to "chip", "model" to "chip", "ai" to "chip", "processor" to "chip",
        "route" to "path", "way" to "path", "plan" to "path", "solve" to "puzzle", "problem" to "puzzle", "complex" to "puzzle",
        "balance" to "scale", "compare" to "scale", "limit" to "gauge", "usage" to "gauge", "speedometer" to "gauge", "power" to "gauge",
        "direction" to "compass", "explore" to "compass", "todo" to "list", "checklist" to "list", "steps" to "list",
        "assistant" to "user", "agent" to "user", "person" to "user", "helper" to "user", "add" to "plus", "more" to "plus",
    )

    /** Geometric glyphs, picked by a keyword's hash when nothing matches it. */
    private val shapes = listOf(
        "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z",
        "M5.5 5.5h13v13h-13z",
        "M12 4l8.5 15h-17z",
        "M12 3l9 9-9 9-9-9z",
        "M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z",
        "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
    )

    fun line(query: String): String? {
        val key = query.trim().lowercase().replace('_', '-')
        if (key.isEmpty()) return null
        val words = listOf(key) + key.split(' ', '-').filter { it.length > 1 }
        for (w in words) {
            paths[w]?.let { return it }
            aliases[w]?.let { a -> (paths[a] ?: Icons.path(a))?.let { return it } }
            Icons.path(w)?.let { return it }
        }
        return null
    }

    /** A curated line glyph first, then the art library, then a geometric shape (never nothing). */
    fun resolve(query: String, icons: IconResolver): Glyph {
        line(query)?.let { return Glyph.Line(it) }
        if (query.isNotBlank()) icons.find(query)?.let { return Glyph.Picture(it) }
        return Glyph.Line(shapes[query.hashCode().mod(shapes.size)])
    }
}

/** Deterministic randomness for hand-drawn strokes (a small LCG seeded per element). */
internal class Rng(seed: Long) {
    private var s = seed xor 0x5DEECE66DL

    fun next(): Float {
        s = (s * 6364136223846793005L + 1442695040888963407L)
        return ((s ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
    }

    /** Uniform in [a, b). */
    fun range(a: Float, b: Float) = a + (b - a) * next()
}

/** Hand-drawn strokes: a scribbled knot, an underline and a loop, as points to draw on. */
internal object Scribbles {
    /** A tangled knot in a [w] × [h] box: an entry tail, several drifting overlapping loops, an exit tail. */
    fun tangle(seed: Long, w: Float, h: Float): List<Pair<Float, Float>> {
        val r = Rng(seed)
        val pts = ArrayList<Pair<Float, Float>>()
        val cx = w * 0.52f
        val cy = h * 0.5f
        pts += w * 0.02f to h * r.range(0.62f, 0.8f)
        pts += w * 0.16f to h * r.range(0.5f, 0.66f)
        val turns = r.range(2.6f, 3.4f)
        val steps = 120
        val phase = r.range(0f, 6.28f)
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = phase + f * turns * 2f * PI.toFloat()
            val rx = w * (0.30f + 0.12f * sin(f * 7.1f + phase) + r.range(-0.012f, 0.012f))
            val ry = h * (0.26f + 0.12f * cos(f * 5.3f + phase * 0.7f) + r.range(-0.015f, 0.015f))
            val drift = w * 0.1f * sin(f * 3.4f + phase)
            pts += (cx + drift + rx * cos(a)) to (cy + ry * sin(a) * (if (i % 2 == 0) 1f else 0.985f))
        }
        pts += w * 0.86f to h * r.range(0.3f, 0.45f)
        pts += w * 0.98f to h * r.range(0.2f, 0.32f)
        return clamp(pts, w, h)
    }

    /** A quick underline across [w], a little wavy, with a short return flick. */
    fun underline(seed: Long, w: Float, amp: Float): List<Pair<Float, Float>> {
        val r = Rng(seed)
        val pts = ArrayList<Pair<Float, Float>>()
        val n = 24
        val tilt = r.range(-0.4f, 0.4f) * amp
        for (i in 0..n) {
            val f = i / n.toFloat()
            pts += (w * (0.01f + 0.97f * f)) to (amp * 1.6f + tilt * (f - 0.5f) + amp * 0.6f * sin(f * 5.2f + r.range(0f, 0.6f)))
        }
        pts += w * 0.9f to amp * 2.4f
        pts += w * 0.55f to amp * 2.9f
        return clamp(pts, w, amp * 4f)
    }

    /** A loop around a [w] × [h] box, a little more than one turn, wobbling like a pen. */
    fun loop(seed: Long, w: Float, h: Float): List<Pair<Float, Float>> {
        // (Points may run a little past the box: the caller sizes the box around what it circles.)
        val r = Rng(seed)
        val pts = ArrayList<Pair<Float, Float>>()
        val n = 72
        val start = r.range(-2.4f, -1.6f)
        for (i in 0..n) {
            val f = i / n.toFloat()
            val a = start + f * 1.14f * 2f * PI.toFloat()
            val wob = 1f + 0.04f * sin(a * 3.1f + r.range(0f, 0.3f)) + 0.03f * f
            pts += (w / 2f + cos(a) * w * 0.5f * wob) to (h / 2f + sin(a) * h * 0.5f * wob)
        }
        return pts
    }

    private fun clamp(pts: List<Pair<Float, Float>>, w: Float, h: Float) = pts.map { (x, y) -> x.coerceIn(0.5f, w - 0.5f) to y.coerceIn(0.5f, h - 0.5f) }
}
