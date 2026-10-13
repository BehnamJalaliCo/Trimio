package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.recipe.Box

/** Where an element rests: its centre and scale. */
internal data class Place(val cx: Float, val cy: Float, val scale: Float)

/** A stage at rest: from [from] to [to], these elements (by element name) in these boxes. */
data class Rest(val chapter: Int, val from: Float, val to: Float, val boxes: Map<String, Box>)

/**
 * The stage's layout solver. Live elements stack in reading order down the middle of the stage
 * (two columns on a wide stage when that keeps them larger), separated by a generous gap, scaled
 * down together only when they would not fit, and never closer than the gap to each other or to
 * the stage's edge. Whenever the live set changes the stage reflows: elements glide to their new
 * places — making room just before a newcomer lands, closing ranks just after one leaves.
 */
internal class Solver(private val stage: Box, private val gap: Float, private val wide: Boolean) {
    class Solved(val x: Anim, val y: Anim, val scale: Anim)

    fun solve(chapter: Int, versions: List<Version>, laid: Map<Version, Laid>): Pair<Map<Version, Solved>, List<Rest>> {
        val placed = versions.filter { !it.overlay }
        val bounds = (placed.map { it.enter } + placed.map { it.gone }.filter { it.isFinite() }).distinct().sorted()
        val keys = HashMap<Version, MutableList<Triple<Float, Float, Place>>>()
        val rests = ArrayList<Rest>()
        var lastEnd = Float.NEGATIVE_INFINITY
        for ((k, b) in bounds.withIndex()) {
            val next = bounds.getOrNull(k + 1) ?: Float.POSITIVE_INFINITY
            val up = placed.filter { it.live(b) }.sortedWith(compareBy({ it.order }, { it.enter }))
            if (up.isEmpty()) continue
            val places = arrange(up.map { laid.getValue(it) })
            // A newcomer gets room before it lands; after a removal the rest close ranks.
            val adds = placed.any { it.enter == b && it.entry == Entry.Add }
            var ws = if (adds) b - MAKE_ROOM else b
            ws = maxOf(ws, lastEnd)
            val we = maxOf(ws + 0.05f, if (adds) b else b + CLOSE_RANKS)
            lastEnd = we
            for ((v, p) in up.zip(places)) keys.getOrPut(v) { mutableListOf() } += Triple(ws, we, p)
            rests += Rest(chapter, we, next, up.zip(places).associate { (v, p) -> v.element to box(laid.getValue(v), p) })
        }
        val solved = keys.mapValues { (v, list) -> anims(v, list) }
        return solved to rests
    }

    fun box(l: Laid, p: Place) = Box.around(p.cx, p.cy, l.w * p.scale, l.h * p.scale)

    private fun anims(v: Version, list: List<Triple<Float, Float, Place>>): Solved {
        val first = list.first().third
        fun track(get: (Place) -> Float): Anim {
            val ks = ArrayList<Anim.Key>()
            ks += Anim.Key(v.enter, get(first), Easing.Hold)
            var last = get(first)
            for ((ws, we, p) in list.drop(1)) {
                val value = get(p)
                if (value == last) continue
                val t0 = maxOf(ws, ks.last().t)
                ks += Anim.Key(t0, last, Easing.Hold)
                ks += Anim.Key(maxOf(we, t0 + 0.05f), value, GLIDE)
                last = value
            }
            return Anim.keys(ks)
        }
        return Solved(track { it.cx }, track { it.cy }, track { it.scale })
    }

    /** Rest places for [items] (in reading order) on the stage. */
    fun arrange(items: List<Laid>): List<Place> {
        val single = column(items, stage.centerX)
        if (!wide || items.size < 2) return single.second
        val split = (items.size + 1) / 2
        val a = items.subList(0, split)
        val b = items.subList(split, items.size)
        val colGap = gap * 1.6f
        val wa = a.maxOf { it.w }
        val wb = b.maxOf { it.w }
        val scale = minOf(1f, stage.width / (wa + wb + colGap), stage.height / maxOf(height(a), height(b)))
        if (scale <= single.first + 0.08f) return single.second
        val total = (wa + wb) * scale + colGap * scale
        // The first column sits on the reading-start side; [rtlStart] mirrors it.
        val left = stage.centerX - total / 2f
        val right = stage.centerX + total / 2f
        val ca = if (rtlStart) right - wa * scale / 2f else left + wa * scale / 2f
        val cb = if (rtlStart) left + wb * scale / 2f else right - wb * scale / 2f
        return columnAt(a, ca, scale) + columnAt(b, cb, scale)
    }

    /** Set by the stage: the reading start is on the right. */
    var rtlStart: Boolean = false

    private fun height(items: List<Laid>) = items.sumOf { it.h.toDouble() }.toFloat() + gap * (items.size - 1)

    private fun column(items: List<Laid>, cx: Float): Pair<Float, List<Place>> {
        val scale = minOf(1f, stage.height / height(items), stage.width / items.maxOf { it.w })
        return scale to columnAt(items, cx, scale)
    }

    private fun columnAt(items: List<Laid>, cx: Float, scale: Float): List<Place> {
        val total = height(items) * scale
        // Optically a little above centre, never past the stage's top.
        val top = maxOf(stage.top, stage.centerY - total / 2f - stage.height * LIFT)
        var y = top
        return items.map { l ->
            val p = Place(cx, y + l.h * scale / 2f, scale)
            y += (l.h + gap) * scale
            p
        }
    }

    companion object {
        const val MAKE_ROOM = 0.5f
        const val CLOSE_RANKS = 0.6f
        const val LIFT = 0.04f
        val GLIDE: Easing = Easing.Bezier(0.45f, 0f, 0.15f, 1f)
    }
}
