package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Geometry
import io.trimio.engine.motion.score.Score

/**
 * The layout supervisor. When the edit can be drawn ([Jury.Context.tracks]) it judges what the
 * viewer really sees, frame by frame — each graphic drawn alone over its whole life — and not the
 * slot the compiler meant it for:
 *
 * - nothing leaves the frame once it has landed: words, figures and icons never (a card or band
 *   may bleed off an edge only when it spans that edge as a design, a full-frame stage always may);
 * - nothing sits in the platform's interface — the buttons down the right of a vertical video, the
 *   caption and handle at the bottom, the status bar at the top;
 * - no two graphics share space while both are up, and no caption runs under a graphic;
 * - nothing over the speaker covers more than [faceShare] of the face.
 *
 * Its fixes, a step further each round for a beat still at fault: another layout of the same
 * device, the other band, a plain treatment of the same words, and last, leaving it out.
 */
class LayoutJudge(private val faceShare: Float = 0.08f) : Judge {
    override val name = "layout"
    override val dimension = "placement"

    /** A graphic at fault and what is wrong with it. */
    private data class Fault(val p: Compiler.Placed, val kind: Kind, val detail: String, val severity: Severity)

    private enum class Kind { Clipped, Unsafe, Collides, Caption, Face, Tiny, Stage }

    override fun score(c: Jury.Context): Verdict {
        val faults = faults(c)
        val f = Kind.entries.flatMap { k ->
            faults.filter { it.kind == k }.distinctBy { it.p.recipe to it.p.at }.map { Bench.finding(it.p.at, it.severity, it.detail, fixFor(it.kind)) }.capped(MAX_EACH)
        }
        return Verdict.of(this, f)
    }

    private fun fixFor(kind: Kind) = when (kind) {
        Kind.Clipped -> "keep it inside the frame"
        Kind.Unsafe -> "keep inside the safe area"
        Kind.Collides -> "move it to a free band"
        Kind.Caption -> "captions yield while the graphic is up"
        Kind.Face -> "move it above the head or below the chin"
        Kind.Tiny -> "a larger layout, or a plain title of its words"
        Kind.Stage -> "the graphic in the middle of its stage"
    }

    /**
     * The only graphic on a full-frame stage, small and up in the band meant for above a head that
     * is not there: the stage is an empty dark frame with a card in its corner.
     */
    private fun offStage(frame: Frame, g: Geometry.Track, graphics: List<Geometry.Track>): List<Fault> {
        if (g.placed.overFootage) return emptyList()
        val rest = g.rest?.takeIf { !frame.fills(it) } ?: return emptyList()
        val alone = graphics.none { o -> o !== g && !o.placed.overFootage && Bench.overlap(o.placed.at, o.placed.out, g.placed.at, g.placed.out) > TIME_TOL }
        val high = (rest.top + rest.bottom) / 2f < frame.h * HIGH && rest.height < frame.h * SMALL_ON_STAGE
        return if (alone && high) listOf(Fault(g.placed, Kind.Stage, "${g.placed.recipe} sits small at the top of an empty full-frame stage", Severity.Major)) else emptyList()
    }

    /** A graphic too small to read on a phone held at arm's length (a sticker of a few percent of the frame). */
    private fun tiny(frame: Frame, g: Geometry.Track): List<Fault> {
        if (g.placed.recipe in SMALL_BY_DESIGN) return emptyList()
        val area = g.rest?.area ?: return emptyList()
        val share = area / (frame.w * frame.h)
        return if (share < TINY) listOf(Fault(g.placed, Kind.Tiny, "${g.placed.recipe} takes ${pct(area, frame.w * frame.h)}% of the frame: too small to read on a phone", Severity.Major)) else emptyList()
    }

    private fun faults(c: Jury.Context): List<Fault> {
        val tracks = c.tracks?.takeIf { it.isNotEmpty() }
        return if (tracks == null) slotFaults(c) else inkFaults(c, tracks)
    }

    // ------------------------------------------------------------ what is drawn

    private fun inkFaults(c: Jury.Context, tracks: List<Geometry.Track>): List<Fault> {
        val frame = Frame(c)
        val graphics = tracks.filter { it.placed.recipe != Bench.CAPTIONS }
        val captions = tracks.filter { it.placed.recipe == Bench.CAPTIONS }
        val out = mutableListOf<Fault>()
        for (g in graphics) out += clipped(frame, g) + unsafe(frame, g) + tiny(frame, g) + offStage(frame, g, graphics)
        out += graphics.indices.flatMap { i -> (i + 1 until graphics.size).mapNotNull { j -> collision(frame, graphics[i], graphics[j]) } }
        out += captions.mapNotNull { cap -> graphics.firstOrNull { g -> under(frame, cap, g) }?.let { g -> Fault(g.placed, Kind.Caption, "a caption runs under ${g.placed.recipe}", Severity.Major) } }
        out += graphics.filter { g -> g.placed.overFootage && onFace(c, frame, g) }.map { Fault(it.placed, Kind.Face, "${it.placed.recipe} covers the speaker's face", Severity.Major) }
        out += c.looks.filter { it.coversFace }.map { Fault(it.beat, Kind.Face, "${it.beat.recipe} covers the speaker's face (seen)", Severity.Major) }
        return out
    }

    /** The frame and its safe area, px. */
    private class Frame(c: Jury.Context) {
        val w = c.compiled.composition.width.toFloat()
        val h = c.compiled.composition.height.toFloat()
        val tall = h > w

        /** A full-frame stage or backdrop: it fills the frame on purpose. */
        fun fills(i: Geometry.Ink) = i.width >= w * FULL && i.height >= h * FULL

        /** A band across the frame (it may run off the sides it spans). */
        fun spans(i: Geometry.Ink) = i.width >= w * FULL

        /** Which part of the platform's interface [i] reaches into, if any. */
        fun ui(i: Geometry.Ink): String? = when {
            tall && i.right > w * RIGHT_UI && i.bottom > h * RIGHT_UI_FROM && i.top < h * BOTTOM_UI -> "buttons on the right"
            i.bottom > h * (if (tall) BOTTOM_UI else EDGE_FAR) -> "bottom bar"
            i.top < h * (if (tall) TOP_UI else EDGE) -> "top bar"
            i.left < w * EDGE || i.right > w * EDGE_FAR -> "frame edge"
            else -> null
        }
    }

    /** Words or figures out of the frame once landed (never a design); a card off an edge it does not span. */
    private fun clipped(frame: Frame, g: Geometry.Track): List<Fault> {
        val rest = g.resting()
        val words = rest.mapNotNull { it.type }.maxOfOrNull { it.outside(frame.w, frame.h) } ?: 0f
        if (words > frame.w * TEXT_TOL) {
            val sev = if (words > frame.w * FAR_OUT) Severity.Critical else Severity.Major
            return listOf(Fault(g.placed, Kind.Clipped, "${g.placed.recipe}'s words run ${pct(words, frame.w)}% out of the frame", sev))
        }
        val body = rest.mapNotNull { it.ink }.filter { !frame.fills(it) && !frame.spans(it) }.maxOfOrNull { it.outside(frame.w, frame.h) } ?: 0f
        return if (body > frame.w * BODY_TOL) listOf(Fault(g.placed, Kind.Clipped, "${g.placed.recipe} is cut by the frame edge (${pct(body, frame.w)}% out)", Severity.Major)) else emptyList()
    }

    /** Words and figures inside the platform's interface while the graphic rests. */
    private fun unsafe(frame: Frame, g: Geometry.Track): List<Fault> {
        val where = g.resting().mapNotNull { it.type }.firstNotNullOfOrNull { frame.ui(it) } ?: return emptyList()
        return listOf(Fault(g.placed, Kind.Unsafe, "${g.placed.recipe}'s words reach under the platform's $where", Severity.Major))
    }

    /** Two graphics up together whose drawn boxes overlap (a full-frame stage counts by its words only). */
    private fun collision(frame: Frame, a: Geometry.Track, b: Geometry.Track): Fault? {
        if (Bench.overlap(a.placed.at, a.placed.out, b.placed.at, b.placed.out) <= TIME_TOL) return null
        val later = if (a.placed.at <= b.placed.at) b else a
        val earlier = if (later === a) b else a
        val hit = later.resting().any { s ->
            val x = box(frame, s) ?: return@any false
            val y = earlier.samples.minByOrNull { kotlin.math.abs(it.t - s.t) }?.takeIf { kotlin.math.abs(it.t - s.t) <= STEP_TOL }?.let { box(frame, it) } ?: return@any false
            x.shared(y) > AREA_TOL * minOf(x.area, y.area)
        }
        return if (hit) Fault(later.placed, Kind.Collides, "${later.placed.recipe} collides with ${earlier.placed.recipe}", Severity.Major) else null
    }

    private fun box(frame: Frame, s: Geometry.Sample): Geometry.Ink? = s.ink?.let { if (frame.fills(it)) s.type else it }

    private fun under(frame: Frame, cap: Geometry.Track, g: Geometry.Track): Boolean {
        val t = cap.samples.firstOrNull()?.t ?: return false
        if (t < g.placed.at + TIME_TOL || t > g.placed.out - TIME_TOL) return false
        val words = cap.samples.firstOrNull()?.type ?: cap.samples.firstOrNull()?.ink ?: return false
        val other = g.samples.minByOrNull { kotlin.math.abs(it.t - t) }?.let { box(frame, it) } ?: return false
        return words.shared(other) > AREA_TOL * minOf(words.area, other.area)
    }

    private fun onFace(c: Jury.Context, frame: Frame, g: Geometry.Track): Boolean {
        val s = c.subject?.face ?: return false
        val face = Geometry.Ink(s.left * frame.w, s.top * frame.h, s.right * frame.w, s.bottom * frame.h)
        if (face.area <= 0f) return false
        return g.resting().any { r -> r.ink?.let { !frame.fills(it) && it.shared(face) / face.area > faceShare } == true }
    }

    // ------------------------------------------------------------ slots (when nothing can be drawn)

    private fun slotFaults(c: Jury.Context): List<Fault> {
        val graphics = Bench.graphics(c)
        val out = mutableListOf<Fault>()
        out += collisions(graphics).map { (a, b) -> Fault(b, Kind.Collides, "${b.recipe} collides with ${a.recipe}", Severity.Major) }
        out += slotsOnFace(c, graphics).map { g -> Fault(g, Kind.Face, "${g.recipe} covers the speaker's face", Severity.Major) }
        val frame = Frame(c)
        out += c.compiled.beats.filter { Bench.area(it) > 0f }.mapNotNull { p ->
            frame.ui(Geometry.Ink(p.left, p.top, p.right, p.bottom))?.let { Fault(p, Kind.Unsafe, "${p.recipe} reaches under the platform's $it", Severity.Minor) }
        }
        out += Bench.captions(c).mapNotNull { cap ->
            graphics.firstOrNull { g -> Bench.overlap(cap.at, cap.out, g.at, g.out) > TIME_TOL && Bench.shared(cap, g) > AREA_TOL * minOf(Bench.area(cap), Bench.area(g)) }
                ?.let { Fault(it, Kind.Caption, "a caption runs under ${it.recipe}", Severity.Major) }
        }
        return out
    }

    private fun collisions(graphics: List<Compiler.Placed>) = graphics.indices.flatMap { i ->
        (i + 1 until graphics.size).mapNotNull { j ->
            val a = graphics[i]
            val b = graphics[j]
            val together = Bench.overlap(a.at, a.out, b.at, b.out) > TIME_TOL
            val touch = Bench.shared(a, b) > AREA_TOL * minOf(Bench.area(a), Bench.area(b))
            (if (a.at <= b.at) a to b else b to a).takeIf { together && touch }
        }
    }

    private fun slotsOnFace(c: Jury.Context, graphics: List<Compiler.Placed>): List<Compiler.Placed> {
        val w = c.compiled.composition.width.toFloat()
        val h = c.compiled.composition.height.toFloat()
        val seen = c.looks.filter { it.coversFace }.map { it.beat }
        val s = c.subject?.face ?: return seen
        val face = listOf(s.left * w, s.top * h, s.right * w, s.bottom * h)
        val area = (face[2] - face[0]) * (face[3] - face[1])
        val covering = graphics.filter { g ->
            val share = Bench.overlap(face[0], face[2], g.left, g.right) * Bench.overlap(face[1], face[3], g.top, g.bottom) / area
            g.overFootage && area > 0f && share > faceShare
        }
        return (covering + seen).distinctBy { it.recipe to it.at }
    }

    // ------------------------------------------------------------ fixes

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val faults = faults(c).filter { it.p.recipe != Bench.CAPTIONS }
        for ((p, kinds) in faults.groupBy { it.p }.mapValues { (_, v) -> v.map { it.kind }.toSet() }) {
            val at = Bench.locate(score, c.words, p) ?: continue
            // On its own stage a graphic goes to the middle first. Out of place (on a caption, the face, another
            // graphic): the other band first; badly drawn: another layout first.
            val centre = Kind.Stage in kinds && score.scenes[at.first].beats[at.second].place != CENTER
            score = Bench.mapBeat(score, at) { b ->
                if (centre) b.copy(place = CENTER) else Bench.Ladder.next(b, p, moveFirst = kinds.all { it in MOVABLE }, gentle = kinds == setOf(Kind.Tiny))
            }
        }
        return score.takeIf { it != c.score }
    }

    private companion object {
        const val MAX_EACH = 3
        const val TIME_TOL = 0.12f
        /** Two looks this close in time are the same moment. */
        const val STEP_TOL = 0.2f
        /** A sliver of shared ink (anti-aliased edges, a shadow) is not a collision. */
        const val AREA_TOL = 0.04f
        /** Words may graze the edge by 1% of the frame width (a glyph's overhang); a body by 2%. */
        const val TEXT_TOL = 0.003f
        const val BODY_TOL = 0.01f
        const val FAR_OUT = 0.05f
        const val FULL = 0.97f
        const val RIGHT_UI = 0.92f
        const val RIGHT_UI_FROM = 0.55f
        const val BOTTOM_UI = 0.89f
        const val TOP_UI = 0.045f
        const val EDGE = 0.03f
        const val EDGE_FAR = 0.97f
        const val PERCENT = 100f
        const val CENTER = "center"
        val MOVABLE = setOf(Kind.Collides, Kind.Face, Kind.Caption)
        /** A stage graphic centred above this share of the frame and shorter than [SMALL_ON_STAGE] of it leaves the stage empty. */
        const val HIGH = 0.3f
        const val SMALL_ON_STAGE = 0.3f

        /** Under 2% of the frame a graphic is a speck on a phone. */
        const val TINY = 0.02f
        /** Small on purpose: a stamp, a site's address pill, an icon, the speaker's name. */
        val SMALL_BY_DESIGN = setOf("stamp", "url", "icon", "lower-third", "ticker")

        fun pct(v: Float, of: Float) = kotlin.math.round(v / of * PERCENT).toInt()
    }
}
