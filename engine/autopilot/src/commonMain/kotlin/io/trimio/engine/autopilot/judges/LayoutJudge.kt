package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score

/**
 * The layout supervisor works on the boxes the compiler placed. No two graphics share space while
 * both are up; nothing over the speaker covers more than [faceShare] of the face (the subject box,
 * or the face the eyes found); every box keeps out of the platform's interface — the buttons down
 * the right of a vertical video, the caption and handle at the bottom, the status bar at the top;
 * and captions never run under another graphic.
 */
class LayoutJudge(private val faceShare: Float = 0.15f) : Judge {
    override val name = "layout"
    override val dimension = "placement"

    override fun score(c: Jury.Context): Verdict {
        val graphics = Bench.graphics(c)
        val f = mutableListOf<Finding>()
        f += collisions(graphics).map { (a, b) -> Bench.finding(b.at, Severity.Major, "${b.recipe} collides with ${a.recipe}", "move it to a free band") }.capped()
        f += onFace(c, graphics).map { g -> Bench.finding(g.at, Severity.Major, "${g.recipe} covers the speaker's face", "move it above the head or below the chin") }.capped()
        f += unsafe(c).map { (p, where) -> Bench.finding(p.at, Severity.Minor, "${p.recipe} reaches under the platform's $where", "keep inside the safe area") }.distinctBy { it.what }.capped()
        f += under(c, graphics).map { (cap, g) -> Bench.finding(cap.at, Severity.Major, "a caption runs under ${g.recipe}", "captions yield while the graphic is up") }.capped()
        return Verdict.of(this, f)
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

    private fun onFace(c: Jury.Context, graphics: List<Compiler.Placed>): List<Compiler.Placed> {
        val w = c.compiled.composition.width.toFloat()
        val h = c.compiled.composition.height.toFloat()
        val seen = c.looks.filter { it.coversFace }.map { it.beat }
        val s = c.subject ?: return seen
        val face = listOf(s.left * w, s.top * h, s.right * w, s.bottom * h)
        val area = (face[2] - face[0]) * (face[3] - face[1])
        val covering = graphics.filter { g ->
            val share = Bench.overlap(face[0], face[2], g.left, g.right) * Bench.overlap(face[1], face[3], g.top, g.bottom) / area
            g.overFootage && area > 0f && share > faceShare
        }
        return (covering + seen).distinctBy { it.recipe to it.at }
    }

    /** Boxes reaching into the platform's interface, with which part. */
    private fun unsafe(c: Jury.Context): List<Pair<Compiler.Placed, String>> {
        val w = c.compiled.composition.width.toFloat()
        val h = c.compiled.composition.height.toFloat()
        val tall = h > w
        return c.compiled.beats.filter { Bench.area(it) > 0f }.mapNotNull { p ->
            when {
                tall && p.right > w * RIGHT_UI && p.bottom > h * RIGHT_UI_FROM -> p to "buttons on the right"
                p.bottom > h * (if (tall) BOTTOM_UI else EDGE_FAR) -> p to "bottom bar"
                p.top < h * (if (tall) TOP_UI else EDGE) -> p to "top bar"
                p.left < w * EDGE || p.right > w * EDGE_FAR -> p to "frame edge"
                else -> null
            }
        }
    }

    private fun under(c: Jury.Context, graphics: List<Compiler.Placed>) = Bench.captions(c).mapNotNull { cap ->
        graphics.firstOrNull { g -> Bench.overlap(cap.at, cap.out, g.at, g.out) > TIME_TOL && Bench.shared(cap, g) > AREA_TOL * minOf(Bench.area(cap), Bench.area(g)) }?.let { cap to it }
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val graphics = Bench.graphics(c)
        val movers = collisions(graphics).map { it.second } + onFace(c, graphics)
        for (g in movers.distinct()) {
            val at = Bench.locate(score, c.words, g) ?: continue
            score = when {
                // Over the face: pictures go, other graphics move to the other band.
                g.recipe in Bench.PICTURES && g in onFace(c, graphics) -> Bench.mapBeat(score, at) { null }
                g.overFootage -> Bench.mapBeat(score, at) { it.copy(place = Bench.otherBand(g.zone)) }
                else -> score
            }
        }
        return score.takeIf { it != c.score }
    }

    private companion object {
        const val TIME_TOL = 0.12f
        /** Slots are generous (a text's ink is smaller than its slot): a sliver of shared slot is not a collision. */
        const val AREA_TOL = 0.08f
        const val RIGHT_UI = 0.92f
        const val RIGHT_UI_FROM = 0.4f
        const val BOTTOM_UI = 0.89f
        const val TOP_UI = 0.045f
        const val EDGE = 0.03f
        const val EDGE_FAR = 0.97f
    }
}
