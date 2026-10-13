package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Transform

/**
 * What every grammar does with an element version: its group (placed by a solver, entering,
 * dimming, pulsing, leaving), marks drawn around targets, morph bodies, and the rule that keeps a
 * stage from crowding.
 */
internal object Staging {
    const val LOOP_ROOM = 0.62f
    const val RISE = 30f
    const val LIFT = 18f
    const val DIMMED = 0.3f
    private const val DIM_SPAN = 0.45f
    const val OUT_REPLACE = 0.25f

    /** How long a replaced element stays at full strength while its replacement comes up. */
    const val HAND_OVER = 0.2f
    const val OUT_MORPH = 0.18f
    const val MORPH_IN = 0.32f

    /** A version as one group: its piece, placed by the solver, entering, dimming and leaving. */
    fun element(v: Version, h: Hand, l: Laid, s: Solver.Solved): Node {
        val track = v.track()
        val children = ArrayList(l.draw(track))
        val whole = v.checks.filter { it.second.isEmpty() || (v.spec.items.isEmpty() && v.spec.primitive !in Pieces.counted) }.minOfOrNull { it.first }
        whole?.let { children += TextPieces.cornerTick(h, l.w, it) }
        val u = h.u
        val rise = when (v.entry) {
            Entry.Add -> Anim.tween(RISE * u, 0f, v.enter, v.enter + 0.7f, Easing.ExpoOut)
            else -> Anim.Zero
        } + if (v.exit == Entry.Add && v.leave.isFinite()) Anim.tween(0f, -LIFT * u, v.leave, v.gone, Easing.ExpoIn) else Anim.Zero
        val grow = if (v.entry == Entry.Replace) Anim.tween(0.96f, 1f, v.enter, v.enter + 0.5f, Easing.ExpoOut) else Anim.One
        val pulse = h.pulse((track.marks + track.picks.map { it.first }).filter { v.spec.primitive !in Pieces.wordy }, 0.035f)
        return Group(
            children,
            Transform(x = s.x, y = s.y + rise, scale = s.scale * grow * pulse, opacity = life(v) * dim(v)),
            width = l.w, height = l.h, start = v.enter - 0.02f, end = end(v), name = "el-${v.element}",
        )
    }

    /** A mark drawn around its target, moving with it. */
    fun overlay(v: Version, h: Hand, tl: Laid, ts: Solver.Solved): Node {
        val target = v.target!!
        // A loop needs room around its element; around a wide one it becomes an underline.
        val loop = v.loop && tl.w < h.maxW * LOOP_ROOM && tl.h < h.maxH * LOOP_ROOM
        val nodes = TextPieces.marking(h, tl.w, tl.h, loop, v.enter + 0.05f, h.seed)
        return Group(
            nodes, Transform(x = ts.x, y = ts.y, scale = ts.scale, opacity = life(v) * dim(target)), width = tl.w, height = tl.h,
            start = v.enter, end = end(v), name = "mark-${v.element}",
        )
    }

    /** Every version's node (morph bodies first, underneath), in reading order; and each one's life. */
    fun nodes(ci: Int, versions: List<Version>, laid: Map<Version, Laid>, solved: Map<Version, Solver.Solved>, hands: Map<Version, Hand>, lives: MutableList<ExplainerStage.Life>): List<Node> {
        val out = ArrayList<Node>()
        for (v in versions) {
            val a = v.from?.takeIf { v.entry == Entry.Morph }
            a?.let { out += Morphs.ghosts(laid[it], solved[it], laid[v], solved[v], v.enter) }
        }
        for (v in versions.sortedBy { it.order }) {
            lives += ExplainerStage.Life(ci, v.element, v.spec.primitive, v.enter, minOf(v.leave, v.gone), v.overlay)
            val h = hands.getValue(v)
            val target = v.target
            val node = if (target != null) {
                laid[target]?.let { tl -> solved[target]?.let { ts -> overlay(v, h, tl, ts) } }
            } else {
                solved[v]?.let { s -> laid[v]?.let { l -> element(v, h, l, s) } }
            }
            node?.let { out += it }
        }
        return out
    }

    fun end(v: Version): Float = (if (v.leave.isFinite()) maxOf(v.gone, v.leave + HAND_OVER + OUT_REPLACE) else Float.POSITIVE_INFINITY) + 0.05f

    /** Entrance and exit opacity by how the version came and how it goes. */
    fun life(v: Version): Anim {
        val keys = ArrayList<Anim.Key>()
        val (a, b) = when (v.entry) {
            Entry.Add -> v.enter to v.enter + 0.4f
            // A replacement is fully up before what it replaces has gone: the stage never passes through empty.
            Entry.Replace -> v.enter to v.enter + 0.3f
            Entry.Morph -> v.enter + MORPH_IN to v.enter + MORPH_IN + 0.35f
        }
        keys += Anim.Key(a, 0f, Easing.Hold)
        keys += Anim.Key(b, 1f, Easing.ExpoOut)
        if (v.leave.isFinite()) {
            val out = when (v.exit) {
                Entry.Add -> v.gone - v.leave
                Entry.Replace -> OUT_REPLACE
                Entry.Morph -> OUT_MORPH
            }
            val t0 = maxOf(v.leave + if (v.exit == Entry.Replace) HAND_OVER else 0f, b + 0.01f)
            keys += Anim.Key(t0, 1f, Easing.Hold)
            keys += Anim.Key(t0 + out, 0f, Easing.Standard)
        }
        return Anim.keys(keys)
    }

    /**
     * Dimming is contrast, never the whole stage: a dim is dropped when nothing else on stage would stay
     * at full strength at that moment (the newest element keeps its light first).
     */
    fun keepLit(versions: List<Version>) {
        val stage = versions.filter { !it.overlay }
        for (v in stage.sortedByDescending { it.enter }) {
            v.dims.removeAll { (t, on) -> on && stage.none { o -> o !== v && o.live(t + DIM_SPAN) && !dimmed(o, t + DIM_SPAN) } }
        }
    }

    /**
     * The stage never empties inside a chapter: an element cleared while nothing else is up stays until
     * the next one arrives (it then leaves as that one lands).
     */
    fun holdTillNext(versions: List<Version>) {
        val stage = versions.filter { !it.overlay }
        for (v in stage.sortedBy { it.leave }) {
            if (!v.leave.isFinite()) continue
            val alone = stage.none { o -> o !== v && o.enter <= v.gone && o.gone > v.gone }
            val next = stage.filter { it.enter > v.leave }.minByOrNull { it.enter } ?: continue
            if (!alone) continue
            v.leave = next.enter + HAND_OVER
            v.gone = v.leave + OUT_REPLACE
            for (m in versions) if (m.target === v) {
                m.leave = v.leave
                m.gone = v.gone
            }
        }
    }

    private fun dimmed(v: Version, t: Float): Boolean = v.dims.filter { it.first <= t }.maxByOrNull { it.first }?.second == true

    fun dim(v: Version): Anim {
        if (v.dims.isEmpty()) return Anim.One
        val keys = ArrayList<Anim.Key>()
        keys += Anim.Key(v.dims.first().first - 0.001f, 1f, Easing.Hold)
        for ((t, on) in v.dims.sortedBy { it.first }) {
            val start = maxOf(t, keys.last().t + 0.001f)
            keys += Anim.Key(start, keys.last().v, Easing.Hold)
            keys += Anim.Key(start + 0.45f, if (on) DIMMED else 1f, Easing.Standard)
        }
        return Anim.keys(keys)
    }

    /**
     * The crowding rule: when a version arrives while [max] of those [counted] are already up, the oldest
     * of them steps off just before it lands (so the stage never holds more than the grammar allows).
     */
    fun limit(versions: List<Version>, max: Int, counted: (Version) -> Boolean, notes: MutableList<String>) {
        for (v in versions.filter { counted(it) && !it.overlay }.sortedBy { it.enter }) {
            val up = versions.filter { it !== v && counted(it) && !it.overlay && it.enter < v.enter && it.gone > v.enter && it.element != v.element }
            if (up.size < max) continue
            val oldest = up.minBy { it.enter }
            oldest.leave = maxOf(oldest.enter + Director.MIN_LIFE * 0.5f, v.enter - STEP_OFF)
            oldest.gone = maxOf(oldest.leave + 0.05f, v.enter)
            oldest.exit = Entry.Add
            notes += "'${oldest.element}' stepped off for '${v.element}' at ${v.enter}s (at most $max on stage)"
            for (m in versions) if (m.target === oldest && m.gone > oldest.gone) { m.leave = oldest.leave; m.gone = oldest.gone }
        }
    }

    private const val STEP_OFF = 0.32f
}

/** The shape morph between two versions: each body glides, resizes and recolours into its successor. */
internal object Morphs {
    private const val SPAN = 0.62f
    private const val MAX = 48

    fun ghosts(la: Laid?, sa: Solver.Solved?, lb: Laid?, sb: Solver.Solved?, at: Float): List<Node> {
        if (la == null || sa == null) return emptyList()
        if (lb == null || sb == null) return emptyList()
        if (la.slots.isEmpty() || lb.slots.isEmpty()) return emptyList()
        val n = minOf(MAX, maxOf(la.slots.size, lb.slots.size))
        val ease = Easing.Bezier(0.5f, 0f, 0.2f, 1f)
        return (0 until n).map { i ->
            val a = frame(la.slots.getOrNull(i) ?: collapse(la.slots.last()), la, sa, at)
            val b = frame(lb.slots.getOrNull(i) ?: collapse(lb.slots.last()), lb, sb, at + SPAN)
            val opacity = Anim.keys(
                Anim.Key(at, 0f, Easing.Hold), Anim.Key(at + 0.1f, 1f, Easing.Standard),
                Anim.Key(at + SPAN, 1f, Easing.Hold), Anim.Key(at + SPAN + 0.25f, 0f, Easing.Standard),
            )
            ShapeNode(
                ShapeSpec.Rect(Anim.tween(a.w, b.w, at, at + SPAN, ease), Anim.tween(a.h, b.h, at, at + SPAN, ease), Anim.tween(a.r, b.r, at, at + SPAN, ease)),
                fill = Fill.Solid(ColorAnim.tween(a.color, b.color, at, at + SPAN, ease)),
                transform = Transform(x = Anim.tween(a.cx, b.cx, at, at + SPAN, ease), y = Anim.tween(a.cy, b.cy, at, at + SPAN, ease), opacity = opacity),
                start = at, end = at + SPAN + 0.3f, name = "morph",
            )
        }
    }

    private fun collapse(s: Slot) = Slot(s.cx, s.cy, 0f, 0f, 0f, s.color.copy(alpha = 0f))

    /** A slot in frame px at time [t] (its element's place then). */
    private fun frame(s: Slot, l: Laid, p: Solver.Solved, t: Float): Slot {
        val k = p.scale.at(t)
        val x = p.x.at(t) + (s.x - l.w / 2f) * k
        val y = p.y.at(t) + (s.y - l.h / 2f) * k
        return Slot(x, y, s.w * k, s.h * k, s.r * k, s.color)
    }
}
