package io.trimio.engine.perception.face

import io.trimio.engine.perception.Box
import kotlin.math.abs
import kotlin.math.max

/** What was seen at one sampled time: faces (by the model, or guessed) and how busy each part of the picture is. */
data class ZoneSample(val time: Float, val faces: List<Face>, val busy: BusyMap?, val guessed: Boolean = false)

/** Where a text block may go: its box, and how much it costs (lower is better; ≥ [FaceZones.ON_FACE] covers a face). */
data class Placement(val box: Box, val cost: Float)

/** Vertical anchors a layout prefers, best first. */
enum class Anchor(val y: Float) { Top(0.12f), Upper(0.3f), Middle(0.5f), Lower(0.68f), Bottom(0.84f) }

/**
 * Layout safe zones for one shot: text and graphics must never cover a face, should avoid the
 * speaker's body and busy parts of the picture (props, text already on screen), and should sit
 * inside the platform's safe area. Built from frames sampled across the video; a span of time
 * takes the union of every face seen in it, so a speaker who leans still stays clear.
 *
 * Consumers: layout and placement of captions, cards and callouts (wiring pending).
 */
class FaceZones(
    val samples: List<ZoneSample>,
    /** The area platforms leave free of their own UI (Reels/Shorts buttons and captions). */
    val safe: Box = DefaultSafe,
) {
    /** Whether the faces came from the model (false: skin-tone guesses or the talking-head prior). */
    val measured: Boolean get() = samples.any { !it.guessed && it.faces.isNotEmpty() }

    private fun within(from: Float, until: Float): List<ZoneSample> {
        val inside = samples.filter { it.time in from..until }
        if (inside.isNotEmpty()) return inside
        // No sample inside the span: the nearest one stands for it.
        val mid = (from + until) / 2f
        return listOfNotNull(samples.minByOrNull { abs(it.time - mid) })
    }

    /** Padded face boxes over [from]..[until]: nothing may overlap them. */
    fun faces(from: Float = 0f, until: Float = Float.MAX_VALUE): List<Box> =
        within(from, until).flatMap { s -> s.faces.map { it.box.padded(it.box.w * FACE_PAD, it.box.h * FACE_PAD) } }
            .let(::merge)

    /** The speaker's body below each face (shoulders down to the frame edge): better avoided. */
    fun bodies(from: Float = 0f, until: Float = Float.MAX_VALUE): List<Box> =
        faces(from, until).map { f -> Box.of(f.centerX - f.w * BODY_WIDTH / 2f, f.bottom, f.centerX + f.w * BODY_WIDTH / 2f, 1f) }

    /** The eye line over a span (frame fraction), when a face was seen. */
    fun eyeLine(from: Float = 0f, until: Float = Float.MAX_VALUE): Float? =
        within(from, until).flatMap { it.faces }.takeIf { it.isNotEmpty() }?.map { it.eyeY }?.average()?.toFloat()

    /**
     * The best place for a [w]×[h] block (frame fractions) shown over [from]..[until], preferring
     * [prefer] in order and keeping clear of [avoid] (for example text already in the footage).
     * Null when every position inside the safe area covers a face: shrink the block or drop it.
     */
    fun place(
        w: Float,
        h: Float,
        from: Float = 0f,
        until: Float = Float.MAX_VALUE,
        prefer: List<Anchor> = listOf(Anchor.Bottom, Anchor.Top, Anchor.Lower, Anchor.Upper),
        avoid: List<Box> = emptyList(),
    ): Placement? {
        val faces = faces(from, until)
        val bodies = bodies(from, until)
        val busy = within(from, until).mapNotNull { it.busy }.reduceOrNull { a, b -> a.max(b) }
        val xs = listOf(safe.centerX - w / 2f, safe.x, safe.right - w).filter { it >= safe.x - EPS && it + w <= safe.right + EPS }.distinct()
        var best: Placement? = null
        var y = safe.y
        while (y + h <= safe.bottom + EPS) {
            for (x in xs) {
                val box = Box(x, y, w, h)
                val cost = cost(box, faces, bodies, busy, avoid, prefer)
                if (best == null || cost < best.cost) best = Placement(box, cost)
            }
            y += STEP
        }
        return best?.takeIf { it.cost < ON_FACE }
    }

    private fun cost(box: Box, faces: List<Box>, bodies: List<Box>, busy: BusyMap?, avoid: List<Box>, prefer: List<Anchor>): Float {
        val onFace = faces.sumOf { box.intersection(it).toDouble() }.toFloat()
        if (onFace > 0f) return ON_FACE + onFace
        val onBody = bodies.sumOf { box.intersection(it).toDouble() }.toFloat() / max(box.area, EPS)
        val onText = avoid.sumOf { box.intersection(it).toDouble() }.toFloat() / max(box.area, EPS)
        val clutter = busy?.mean(box) ?: 0f
        val anchor = prefer.withIndex().minOf { (rank, a) -> abs(box.centerY - a.y) + rank * RANK_STEP }
        // Centred blocks read best; side placements only when the centre is taken.
        val side = abs(box.centerX - safe.centerX) * SIDE_WEIGHT
        return onBody * BODY_WEIGHT + onText * TEXT_WEIGHT + clutter * BUSY_WEIGHT + anchor * ANCHOR_WEIGHT + side
    }

    companion object {
        /** Reels/Shorts: keep 6% from the sides, 8% from the top and 14% from the bottom (their own buttons and captions). */
        val DefaultSafe = Box(0.06f, 0.08f, 0.88f, 0.78f)

        /** Any cost at or above this covers a face. */
        const val ON_FACE = 1000f
        private const val FACE_PAD = 0.15f
        private const val BODY_WIDTH = 2.6f
        private const val STEP = 0.01f
        private const val EPS = 1e-4f
        private const val RANK_STEP = 0.2f
        private const val BODY_WEIGHT = 0.15f
        private const val TEXT_WEIGHT = 3f
        private const val BUSY_WEIGHT = 0.8f
        private const val ANCHOR_WEIGHT = 1.2f
        private const val SIDE_WEIGHT = 0.5f

        /** Merges boxes that overlap a lot (the same face over many samples) to keep the list short. */
        internal fun merge(boxes: List<Box>): List<Box> {
            val out = ArrayList<Box>()
            for (b in boxes) {
                val i = out.indexOfFirst { it.iou(b) > MERGE_IOU }
                if (i >= 0) out[i] = out[i].union(b) else out += b
            }
            return out
        }

        private const val MERGE_IOU = 0.3f
    }
}
