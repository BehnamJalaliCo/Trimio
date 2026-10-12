package io.trimio.engine.motion.score

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import io.trimio.engine.motion.Node
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where every graphic of a compiled edit really is, frame by frame: each beat's own layers are
 * drawn alone (no footage, no other graphic, the scene's moves and the camera as they are) by the
 * same [MotionRenderer] the export uses, small and on a canvas wider than the frame, and the
 * drawn pixels' box is read back. What the slot said a graphic would take is a promise; this is
 * what the viewer sees — a card that slid half out of the frame, a list taller than its slot, a
 * label that ran into the face. Pure drawing, no model: the jury's placement judge reads it.
 */
class Geometry(
    private val measurer: TextMeasurer,
    private val fonts: MotionFonts,
    /** Drawing scale (a 1080-wide frame is probed 270 px wide). */
    private val scale: Float = 0.25f,
    /** Room drawn around the frame on every side, as a share of it: ink out there is out of frame. */
    private val pad: Float = 0.25f,
) {
    /** A rectangle in frame px. */
    data class Ink(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

        fun shared(o: Ink): Float = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0f) * (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0f)

        /** How far it reaches past a [w] × [h] frame on its worst side, px (0 inside). */
        fun outside(w: Float, h: Float): Float = maxOf(-left, -top, right - w, bottom - h, 0f)
    }

    /**
     * One look at a beat: its ink at [t] (null when nothing of it is drawn), and the ink of its
     * words, figures and icons alone ([type]; looked at once it rests, null otherwise or when it has none).
     */
    data class Sample(val t: Float, val ink: Ink?, val type: Ink? = null)

    /** A placed beat ([Compiler.Output.beats] index [index]) and its looks over its life. */
    data class Track(val index: Int, val placed: Compiler.Placed, val samples: List<Sample>) {
        /** Looks once it has landed and before it leaves: what the viewer reads at rest. */
        fun resting(entrance: Float = ENTRANCE, exit: Float = EXIT): List<Sample> =
            samples.filter { it.ink != null && it.t >= placed.at + entrance && it.t <= placed.out - exit }
                .ifEmpty { samples.filter { it.ink != null }.let { s -> listOfNotNull(s.getOrNull(s.size / 2)) } }

        /** The union of its resting ink. */
        val rest: Ink? get() = resting().mapNotNull { it.ink }.reduceOrNull { a, b -> Ink(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

        /** Its resting ink at [t], or its nearest resting look. */
        fun at(t: Float): Ink? = resting().minByOrNull { kotlin.math.abs(it.t - t) }?.ink
    }

    /**
     * Every placed beat of [output] traced: graphics every [step] s over their life, captions once
     * they have landed (they hold still). Beats with no layers of their own are left out.
     */
    fun trace(output: Compiler.Output, step: Float = STEP): List<Track> {
        val c = output.composition
        return output.beats.indices.mapNotNull { i ->
            val p = output.beats[i]
            val nodes = output.layers.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val times = if (p.recipe == CAPTIONS) listOf(minOf(p.at + CAPTION_LAND, (p.at + p.out) / 2f)) else times(p, step, c.duration)
            val keep = nodes.toIdentitySet()
            val all = look(c, isolate(c.root, keep), times)
            // The words alone, where it rests (every other look): text out of the frame is never a design.
            val resting = times.filterIndexed { k, t -> (k % 2 == 0 || p.recipe == CAPTIONS) && t >= p.at + ENTRANCE && t <= p.out - EXIT }
                .ifEmpty { listOf(times[times.size / 2]) }
            val type = look(c, letters(c.root, keep, false), resting).associate { it.t to it.ink }
            Track(i, p, all.map { s -> s.copy(type = type[s.t]) })
        }
    }

    /**
     * [nodes] alone in a [width] × [height] frame (no scene moves, no camera, no footage), looked at
     * at [times]: the union of their ink, and of their letters' ink — where a graphic rests.
     */
    fun rest(nodes: List<Node>, width: Int, height: Int, times: List<Float>): Pair<Ink?, Ink?> {
        if (nodes.isEmpty() || times.isEmpty()) return null to null
        val root = Group(nodes, name = "probe")
        val c = Composition(width, height, 30, times.max() + 1f, Color.Transparent, root, shutterAngle = 0f)
        val all = look(c, root, times).mapNotNull { it.ink }.reduceOrNull(::union)
        val type = look(c, letters(root, nodes.toIdentitySet(), false), times).mapNotNull { it.ink }.reduceOrNull(::union)
        return all to type
    }

    private fun union(a: Ink, b: Ink) = Ink(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    /** The last few edits traced, by identity: the judges of one panel share one tracing. */
    private val memo = ArrayDeque<Pair<Compiler.Output, List<Track>>>()

    /** [trace] of [output], drawn once however many judges ask. */
    fun traced(output: Compiler.Output): List<Track> {
        memo.firstOrNull { it.first === output }?.let { return it.second }
        val tracks = trace(output)
        memo.addFirst(output to tracks)
        while (memo.size > MEMO) memo.removeLast()
        return tracks
    }

    private fun times(p: Compiler.Placed, step: Float, duration: Float): List<Float> {
        val end = minOf(p.out, duration) - END_GUARD
        if (end <= p.at) return listOf(p.at.coerceIn(0f, duration))
        val n = ceil((end - p.at) / step).toInt().coerceAtLeast(1)
        return (0..n).map { k -> minOf(end, p.at + k * step) }.distinct()
    }

    /** [isolated] (the composition's root with only some layers left) drawn at each of [times]: its ink boxes. */
    private fun look(c: Composition, isolated: Node?, times: List<Float>): List<Sample> {
        val root = isolated as? Group ?: return times.map { Sample(it, null) }
        val alone = Composition(c.width, c.height, c.fps, c.duration, Color.Transparent, root, c.camera, shutterAngle = 0f)
        val renderer = MotionRenderer(alone, measurer, fonts).also { it.runtimeShaders = false }
        val fw = c.width * (1f + 2f * pad)
        val fh = c.height * (1f + 2f * pad)
        val pw = (fw * scale).roundToInt().coerceAtLeast(1)
        val ph = (fh * scale).roundToInt().coerceAtLeast(1)
        val image = ImageBitmap(pw, ph)
        val pixels = IntArray(pw * ph)
        val scope = CanvasDrawScope()
        val canvas = Canvas(image)
        return times.map { t ->
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(pw.toFloat(), ph.toFloat())) {
                drawRect(Color.Transparent, size = size, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                scale(scale, scale, pivot = Offset.Zero) { translate(c.width * pad, c.height * pad) { renderer.render(this, t) } }
            }
            image.readPixels(pixels, 0, 0, pw, ph)
            Sample(t, ink(pixels, pw, ph)?.let { (l, tp, r, b) -> Ink(l / scale - c.width * pad, tp / scale - c.height * pad, r / scale - c.width * pad, b / scale - c.height * pad) })
        }
    }

    /** The box of pixels drawn clearly enough to be seen (soft glows and shadows aside). */
    private fun ink(pixels: IntArray, w: Int, h: Int): List<Float>? {
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr ALPHA_SHIFT) < SEEN) continue
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return if (r < 0) null else listOf(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
    }

    private fun List<Node>.toIdentitySet(): Set<Node> = HashSet<Node>(size).also { s -> forEach { s += it } }

    companion object {
        const val CAPTIONS = "pop-captions"

        /** A graphic has landed this long after it starts and starts leaving this long before it ends. */
        const val ENTRANCE = 0.7f
        const val EXIT = 0.35f
        private const val STEP = 0.25f
        private const val MEMO = 4
        private const val CAPTION_LAND = 0.25f
        private const val END_GUARD = 0.02f
        private const val ALPHA_SHIFT = 24
        /** Alpha a pixel needs to count as drawn (a soft shadow or glow does not). */
        private const val SEEN = 96

        /**
         * [node] with only [keep] (and the groups above them, transforms as they are) left in it;
         * footage, stages and full-frame effects of other beats are gone. Null when nothing is kept.
         */
        fun isolate(node: Node, keep: Set<Node>): Node? = when {
            node in keep -> node
            node is Group -> node.children.mapNotNull { isolate(it, keep) }.takeIf { it.isNotEmpty() }?.let { kids ->
                Group(kids, node.transform, node.width, node.height, node.start, node.end, node.mask, node.blend, node.name, node.filter)
            }
            node is MediaNode || node is EffectNode -> null
            else -> null
        }

        /** Like [isolate], keeping of [keep] only its words, figures and icons (no cards, bars or shapes). */
        fun letters(node: Node, keep: Set<Node>, inside: Boolean): Node? {
            val kept = inside || node in keep
            return when (node) {
                is Group -> node.children.mapNotNull { letters(it, keep, kept) }.takeIf { it.isNotEmpty() }?.let { kids ->
                    Group(kids, node.transform, node.width, node.height, node.start, node.end, node.mask, node.blend, node.name, node.filter)
                }
                is io.trimio.engine.motion.TextNode, is io.trimio.engine.motion.CounterNode, is io.trimio.engine.motion.VectorNode -> node.takeIf { kept }
                else -> null
            }
        }
    }
}
