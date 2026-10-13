package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Node

/**
 * What one version of a stage element shows: its primitive and the content gathered from every
 * beat that builds on it (a later "build" adds items, a "fill" brings its value…).
 */
internal data class Spec(
    val primitive: Primitive,
    val text: String,
    val caption: String,
    val items: List<Item>,
    val value: Double?,
    val unit: String,
    val visual: String,
    /** Every op this version will go through (so it can keep room for a tick or an underline). */
    val ops: Set<StageOp> = emptySet(),
) {
    /** Items, or the text split on "|" or new lines when the beat gave none. */
    val labels: List<String> get() = items.map { it.label }

    companion object {
        fun of(p: Primitive, c: BeatContent): Spec {
            val items = c.items.ifEmpty {
                c.text.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }?.map { Item(it) }.orEmpty()
            }
            val text = if (c.items.isEmpty() && items.isNotEmpty()) "" else c.text
            return Spec(p, text.trim(), c.caption.trim(), items, c.value, c.unit.trim(), c.visual.trim())
        }
    }
}

/** A value change: [to] (or new item values) reached over [dur] from [at]. */
internal class FillStep(val at: Float, val dur: Float, val to: Double?, val items: List<Item>, val picks: List<Int>)

/**
 * The life of one version: when it arrives and starts to leave, when each item is revealed,
 * which items are checked or picked when, and how its value moves. Times are seconds.
 */
internal class Track(
    val enter: Float,
    val leave: Float,
    val reveals: List<Float>,
    val checks: List<Pair<Float, List<Int>>>,
    /** Highlight steps; an empty pick list means the whole element. */
    val picks: List<Pair<Float, List<Int>>>,
    val fills: List<FillStep>,
    /** Whole-element marks (a highlight with no items, or an op the primitive draws as one). */
    val marks: List<Float>,
    /** A "build" of the element's own words: revealed one by one across this window. */
    val build: Pair<Float, Float>? = null,
) {
    fun revealAt(i: Int): Float = reveals.getOrElse(i) { enter + i * STAGGER }

    /** When item [i] is first checked, or null. */
    fun checkedAt(i: Int): Float? = checks.firstOrNull { i in it.second }?.first

    /**
     * When item [i] of [n] is done: checked, or reached by a fill (a value ≤ 1 is the share of
     * the items, a larger one their count), staggered along the row.
     */
    fun doneAt(i: Int, n: Int): Float? {
        checkedAt(i)?.let { return it }
        val step = fills.firstOrNull { f -> f.to?.let { count(it, n) > i } == true } ?: return null
        return step.at + i * FILL_STAGGER * (step.dur / FILL_SPAN).coerceIn(0.5f, 1.5f)
    }

    /** When the element as a whole is first checked (a check with no items), or null. */
    val wholeCheck: Float? get() = checks.firstOrNull { it.second.isEmpty() }?.first

    /** When the element is first marked as a whole, or null. */
    val firstMark: Float? get() = marks.minOrNull()

    /** Steps where item [i] becomes picked (true) or loses the pick to others (false). */
    fun pickSteps(i: Int): List<Pair<Float, Boolean>> {
        val out = ArrayList<Pair<Float, Boolean>>()
        var on = false
        for ((t, p) in picks) {
            if (p.isEmpty()) continue
            val now = i in p
            if (now != on) out += t to now
            on = now
        }
        return out
    }

    /** A colour that steps from [base] to [hi] while item [i] is picked. */
    fun pickColor(i: Int, base: Color, hi: Color): ColorAnim = steps(base, pickSteps(i).map { (t, on) -> t to if (on) hi else base })

    /** A colour that turns [hi] when item [i] is checked. */
    fun checkColor(i: Int, base: Color, hi: Color): ColorAnim = checkedAt(i)?.let { ColorAnim.tween(base, hi, it, it + TURN) } ?: ColorAnim.of(base)

    /**
     * The element's value over time: [initial] at entry (rolling up from [from] when given), then
     * every fill step, each mapped through [map].
     */
    fun value(initial: Double?, from: Double? = null, map: (Double) -> Float): Anim {
        val keys = ArrayList<Anim.Key>()
        val start = initial ?: 0.0
        if (from != null) {
            keys += Anim.Key(enter + ROLL_DELAY, map(from), Easing.Hold)
            keys += Anim.Key(enter + ROLL_DELAY + ROLL, map(start), Easing.ExpoOut)
        } else {
            keys += Anim.Key(enter, map(start), Easing.Hold)
        }
        for (f in fills) {
            val v = f.to ?: continue
            val last = keys.last()
            val t0 = maxOf(f.at, last.t)
            keys += Anim.Key(t0, last.v, Easing.Hold)
            keys += Anim.Key(t0 + f.dur, map(v), Easing.Bezier(0.3f, 0f, 0.2f, 1f))
        }
        return Anim.keys(keys)
    }

    /** The last value a fill gives (or [initial]). */
    fun finalValue(initial: Double?): Double? = fills.lastOrNull { it.to != null }?.to ?: initial

    companion object {
        const val STAGGER = 0.12f
        const val TURN = 0.28f
        const val ROLL = 1.1f
        const val ROLL_DELAY = 0.15f
        const val FILL_STAGGER = 0.09f
        const val FILL_SPAN = 0.9f

        /** A fill value as an item count of [n]: a share when ≤ 1, a count above. */
        fun count(v: Double, n: Int): Int = if (v <= 1.0) kotlin.math.round(v * n).toInt() else v.toInt()

        fun steps(base: Color, events: List<Pair<Float, Color>>): ColorAnim {
            if (events.isEmpty()) return ColorAnim.of(base)
            val keys = ArrayList<Triple<Float, Color, Easing>>()
            keys += Triple(events.first().first - 0.001f, base, Easing.Hold)
            var last = base
            for ((t, c) in events) {
                keys += Triple(t, last, Easing.Hold)
                keys += Triple(t + TURN, c, Easing.Standard)
                last = c
            }
            return ColorAnim.keys(*dedupe(keys).toTypedArray())
        }

        private fun dedupe(keys: List<Triple<Float, Color, Easing>>): List<Triple<Float, Color, Easing>> {
            val out = ArrayList<Triple<Float, Color, Easing>>()
            for (k in keys) {
                val t = if (out.isNotEmpty() && k.first <= out.last().first) out.last().first + 0.001f else k.first
                out += Triple(t, k.second, k.third)
            }
            return out
        }
    }
}

/** A body inside a laid-out element, in its local px (top-left origin): what a morph moves. */
internal data class Slot(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float, val color: Color) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

/**
 * An element laid out at its final size (every item it will ever show has its place), its
 * bodies for morphs, and how to draw it for a [Track].
 */
internal class Laid(val w: Float, val h: Float, val slots: List<Slot>, val draw: (Track) -> List<Node>)

/** One of the 36 primitives: lays out a [Spec] with a [Hand]. */
internal fun interface Piece {
    fun lay(s: Spec, h: Hand): Laid
}
