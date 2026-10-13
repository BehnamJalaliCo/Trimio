package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.Transform

/**
 * Renders an [ExplainerFilm] as a finished [Composition] for the engine's own renderer and
 * exporter: chapter by chapter, a heading arrives under the chapter mark, the stage builds up
 * beat by beat (elements added, built, ticked, filled, picked out, dimmed, morphed, replaced and
 * cleared, the layout reflowing around them), captions run on their band, and between chapters
 * the stage clears. The same film and seed always give the same frames.
 */
class ExplainerStage(private val text: TextLayoutEngine, private val icons: IconResolver = IconResolver.None) {

    /** What a run made: the composition, and where everything rested (for tests and the jury). */
    class Output(val composition: Composition, val report: StageReport)

    /** The frame's plan, every rest of every chapter's stage, each element's life, and notes on skipped beats. */
    class StageReport(val frame: StageFrame, val rests: List<Rest>, val elements: List<Life>, val notes: List<String>)

    /** One element version's life on stage. */
    data class Life(val chapter: Int, val element: String, val primitive: Primitive, val enter: Float, val leave: Float, val overlay: Boolean)

    fun compose(film: ExplainerFilm, width: Int = 1080, height: Int = 1920, fps: Int = 30): Output {
        val pal = PaletteBook.named(film.art.palette)
        val rtl = film.language.lowercase().let { it.startsWith("fa") || it.startsWith("ar") || it.startsWith("ur") }
        val persian = film.language.lowercase().startsWith("fa")
        val captions = CaptionStyle.named(film.art.captionStyle)
        val frame = StageFrame(width.toFloat(), height.toFloat(), StageGrammar.named(film.art.layout), rtl, captions)
        val chapters = film.chapters.filter { it.end > it.start }.sortedBy { it.start }
        val duration = (listOf(chapters.maxOfOrNull { it.end } ?: 0.0) + film.captions.map { it.end }).max().toFloat() + TAIL
        val notes = ArrayList<String>()
        val rests = ArrayList<Rest>()
        val lives = ArrayList<Life>()
        val nodes = ArrayList<Node>()
        // The paper itself, as a layer: it covers the frame at any export scale.
        nodes += ShapeNode(
            ShapeSpec.Rect(width.toFloat().let { Anim.Const(it) }, Anim.Const(height.toFloat())), fill = Fill.Solid(ColorAnim.of(pal.background)),
            transform = Transform(anchorX = 0f, anchorY = 0f), name = "paper",
        )
        val header = Header(text, pal, frame, rtl, chapters, duration, ChapterMark.named(film.art.chapterMark), persian)
        nodes += header.marks()
        for ((ci, ch) in chapters.withIndex()) {
            nodes += header.title(ci)
            val (chapterNodes, chapterRests) = chapter(ci, ch, film, pal, frame, rtl, notes, lives)
            nodes += chapterNodes
            rests += chapterRests
        }
        nodes += Captions(text, pal, frame, rtl, persian).nodes(captions, film.captions, duration)
        val composition = Composition(width, height, fps, duration, pal.background, Group(nodes, name = "explainer"), shutterAngle = 0f)
        return Output(composition, StageReport(frame, rests, lives, notes))
    }

    private fun hand(pal: ExplainerPalette, ch: Chapter, frame: StageFrame, rtl: Boolean, persian: Boolean, seed: Long) =
        Hand(
            text, pal, ch.accent, rtl, frame.u * if (frame.portrait) PORTRAIT_SCALE else LANDSCAPE_SCALE, icons,
            frame.stage.width * ROOM, frame.stage.height * ROOM, seed,
        ).also { it.persian = persian }

    @Suppress("LongParameterList")
    private fun chapter(
        ci: Int, ch: Chapter, film: ExplainerFilm, pal: ExplainerPalette, frame: StageFrame, rtl: Boolean, notes: MutableList<String>, lives: MutableList<Life>,
    ): Pair<List<Node>, List<Rest>> {
        val versions = Director(ch, notes).run()
        val seed = film.art.seed * 1_000L + ci * 97L
        val laid = HashMap<Version, Laid>()
        val hands = HashMap<Version, Hand>()
        for (v in versions) {
            val h = hand(pal, ch, frame, rtl, film.language.lowercase().startsWith("fa"), seed + v.serial * 13L + v.element.hashCode())
            hands[v] = h
            if (!v.overlay) laid[v] = Pieces.of(v.spec.primitive).lay(v.spec, h)
        }
        // Elements rest a little inside the stage: a highlight's pulse never pushes them past it.
        val room = frame.stage.let { b ->
            val dx = b.width * (1f - ROOM) / 2f
            val dy = b.height * (1f - ROOM) / 2f
            io.trimio.engine.motion.recipe.Box(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy)
        }
        val solver = Solver(room, frame.u * if (frame.portrait) GAP_TALL else GAP_WIDE, wide = !frame.portrait).also { it.rtlStart = rtl }
        val (solved, rests) = solver.solve(ci, versions, laid)
        val out = ArrayList<Node>()
        // Morph bodies glide underneath: the new version's own pieces fade in over them as they land.
        for (v in versions) {
            val a = v.from?.takeIf { v.entry == Entry.Morph }
            val ghosts = a?.let { Morphs.ghosts(laid[it], solved[it], laid[v], solved[v], v.enter) }
            ghosts?.let { out += it }
        }
        for (v in versions.sortedBy { it.order }) {
            lives += Life(ci, v.element, v.spec.primitive, v.enter, minOf(v.leave, v.gone), v.overlay)
            val h = hands.getValue(v)
            val target = v.target
            val node = if (target != null) {
                laid[target]?.let { tl -> solved[target]?.let { ts -> overlay(v, h, tl, ts) } }
            } else {
                solved[v]?.let { s -> element(v, h, laid.getValue(v), s) }
            }
            node?.let { out += it }
        }
        return out to rests
    }

    /** A version as one group: its piece, placed by the solver, entering, dimming and leaving. */
    private fun element(v: Version, h: Hand, l: Laid, s: Solver.Solved): Node {
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
    private fun overlay(v: Version, h: Hand, tl: Laid, ts: Solver.Solved): Node {
        val target = v.target!!
        // A loop needs room around its element; around a wide one it becomes an underline.
        val loop = v.loop && tl.w < h.maxW * LOOP_ROOM && tl.h < h.maxH * LOOP_ROOM
        val nodes = TextPieces.marking(h, tl.w, tl.h, loop, v.enter + 0.05f, h.seed)
        return Group(nodes, Transform(x = ts.x, y = ts.y, scale = ts.scale, opacity = life(v) * dim(target)), width = tl.w, height = tl.h, start = v.enter, end = end(v), name = "mark-${v.element}")
    }

    private fun end(v: Version): Float = (if (v.leave.isFinite()) maxOf(v.gone, v.leave + OUT_REPLACE) else Float.POSITIVE_INFINITY) + 0.05f

    /** Entrance and exit opacity by how the version came and how it goes. */
    private fun life(v: Version): Anim {
        val keys = ArrayList<Anim.Key>()
        val (a, b) = when (v.entry) {
            Entry.Add -> v.enter to v.enter + 0.4f
            Entry.Replace -> v.enter + 0.12f to v.enter + 0.5f
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
            val t0 = maxOf(v.leave, b + 0.01f)
            keys += Anim.Key(t0, 1f, Easing.Hold)
            keys += Anim.Key(t0 + out, 0f, Easing.Standard)
        }
        return Anim.keys(keys)
    }

    private fun dim(v: Version): Anim {
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

    companion object {
        const val TAIL = 0.6f
        /** Pieces are drawn a little larger on a tall frame: it has the room, and phones are held close. */
        const val PORTRAIT_SCALE = 1.16f
        const val LANDSCAPE_SCALE = 1.1f
        /** The share of the stage elements rest in (the rest is room for pulses). */
        const val ROOM = 0.95f
        const val LOOP_ROOM = 0.62f
        const val GAP_TALL = 64f
        const val GAP_WIDE = 52f
        const val RISE = 30f
        const val LIFT = 18f
        const val DIMMED = 0.3f
        const val OUT_REPLACE = 0.25f
        const val OUT_MORPH = 0.18f
        const val MORPH_IN = 0.32f
    }
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
