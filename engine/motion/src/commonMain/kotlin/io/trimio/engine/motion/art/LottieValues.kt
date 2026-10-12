package io.trimio.engine.motion.art

import io.trimio.engine.motion.Easing
import kotlin.math.sqrt

/**
 * Animated values of a Lottie document, evaluated at a frame (layer time, in frames). Numbers,
 * points, colours and gradient stop lists are all float arrays; shapes are [ShapeData]. Every
 * value is a pure function of the frame, so any frame of an animation renders on its own.
 */
internal sealed interface Value<T> {
    fun at(frame: Float): T

    class Static<T>(val value: T) : Value<T> {
        override fun at(frame: Float): T = value
    }

    /** Keyframes in time order; [lerp] mixes two values by a per-dimension eased fraction. */
    class Keyed<T>(val keys: List<Key<T>>, private val lerp: (T, T, Key<T>, Float) -> T) : Value<T> {
        override fun at(frame: Float): T {
            val first = keys.first()
            if (keys.size == 1 || frame <= first.t) return first.start
            val last = keys.last()
            if (frame >= last.t) return last.startOrNull ?: keys[keys.size - 2].end ?: keys[keys.size - 2].start
            var i = 1
            while (keys[i].t <= frame) i++
            val a = keys[i - 1]
            val b = keys[i]
            if (a.hold) return a.start
            val end = a.end ?: b.startOrNull ?: return a.start
            val span = b.t - a.t
            val x = if (span <= 0f) 1f else ((frame - a.t) / span).coerceIn(0f, 1f)
            return lerp(a.start, end, a, x)
        }
    }

    /**
     * One keyframe: its value from [t] ([start]; an old-style [end] is where it goes), held or
     * eased with the cubic curve of its out tangent ([ox], [oy]) and the next key's in tangent
     * ([ix], [iy]) — one curve per dimension when the document gives several. A position's
     * spatial tangents ([to], [ti]) bend the path it travels.
     */
    class Key<T>(
        val t: Float,
        val startOrNull: T?,
        val end: T?,
        val hold: Boolean,
        val ox: FloatArray,
        val oy: FloatArray,
        val ix: FloatArray,
        val iy: FloatArray,
        val to: FloatArray? = null,
        val ti: FloatArray? = null,
    ) {
        val start: T get() = startOrNull ?: end ?: error("keyframe without a value")

        // Built once when read: drawing never mutates a key, so threads may share an animation.
        private val curves: List<Easing> = if (ox.isEmpty() || ix.isEmpty() || oy.isEmpty() || iy.isEmpty()) emptyList() else
            List(maxOf(ox.size, ix.size, oy.size, iy.size)) { d ->
                val k = { a: FloatArray -> a[minOf(d, a.size - 1)] }
                val x1 = k(ox).coerceIn(0f, 1f)
                val y1 = k(oy)
                val x2 = k(ix).coerceIn(0f, 1f)
                val y2 = k(iy)
                if (x1 == y1 && x2 == y2) Easing.Linear else Easing.Bezier(x1, y1, x2, y2)
            }

        /** The eased fraction for dimension [d]. */
        fun ease(d: Int, x: Float): Float = if (curves.isEmpty()) x else curves[minOf(d, curves.size - 1)].at(x)

        /** Arc-length table of the spatial curve to the next key (set when the document is read). */
        internal var arc: FloatArray? = null
    }
}

/** A Bézier contour in Lottie's form: vertices with in and out tangents relative to them. */
internal class ShapeData(val closed: Boolean, val v: FloatArray, val i: FloatArray, val o: FloatArray) {
    val points: Int get() = v.size / 2
}

internal object Lerp {
    fun floats(a: FloatArray, b: FloatArray, key: Value.Key<FloatArray>, x: Float): FloatArray {
        val n = minOf(a.size, b.size)
        val to = key.to
        val ti = key.ti
        // A travelling point on a curved path: the eased fraction is a share of the arc length.
        if (to != null && ti != null && n >= 2 && (to.any { it != 0f } || ti.any { it != 0f })) return spatial(a, b, key, x)
        return FloatArray(n) { d -> a[d] + (b[d] - a[d]) * key.ease(d, x) }
    }

    fun shape(a: ShapeData, b: ShapeData, key: Value.Key<ShapeData>, x: Float): ShapeData {
        if (a.v.size != b.v.size) return if (x < 1f) a else b
        val e = key.ease(0, x)
        fun mix(p: FloatArray, q: FloatArray) = FloatArray(p.size) { k -> p[k] + (q[k] - p[k]) * e }
        return ShapeData(a.closed, mix(a.v, b.v), mix(a.i, b.i), mix(a.o, b.o))
    }

    /** Precomputes the arc-length tables of every curved segment of a position (when read). */
    fun prepareArcs(keys: List<Value.Key<FloatArray>>) {
        for (k in 0 until keys.size - 1) {
            val key = keys[k]
            val a = key.start
            val b = key.end ?: keys[k + 1].startOrNull ?: continue
            val to = key.to
            val ti = key.ti
            if (to != null && ti != null && a.size >= 2 && b.size >= 2) key.arc = arcTable(a, b, to, ti)
        }
    }

    private fun arcTable(a: FloatArray, b: FloatArray, to: FloatArray, ti: FloatArray): FloatArray {
        val p1x = a[0] + to[0]
        val p1y = a[1] + to.getOrElse(1) { 0f }
        val p2x = b[0] + ti[0]
        val p2y = b[1] + ti.getOrElse(1) { 0f }
        val arc = FloatArray(ARC + 1)
        var px = a[0]
        var py = a[1]
        for (k in 1..ARC) {
            val u = k / ARC.toFloat()
            val qx = cubic(a[0], p1x, p2x, b[0], u)
            val qy = cubic(a[1], p1y, p2y, b[1], u)
            arc[k] = arc[k - 1] + sqrt((qx - px) * (qx - px) + (qy - py) * (qy - py))
            px = qx
            py = qy
        }
        return arc
    }

    private fun spatial(a: FloatArray, b: FloatArray, key: Value.Key<FloatArray>, x: Float): FloatArray {
        val to = key.to!!
        val ti = key.ti!!
        val p1x = a[0] + to[0]
        val p1y = a[1] + to.getOrElse(1) { 0f }
        val p2x = b[0] + ti[0]
        val p2y = b[1] + ti.getOrElse(1) { 0f }
        val table = key.arc ?: arcTable(a, b, to, ti)
        val target = key.ease(0, x) * table[ARC]
        var k = 1
        while (k < ARC && table[k] < target) k++
        val seg = table[k] - table[k - 1]
        val u = ((k - 1) + if (seg > 0f) (target - table[k - 1]) / seg else 0f) / ARC
        val out = FloatArray(minOf(a.size, b.size)) { d -> a[d] + (b[d] - a[d]) * key.ease(d, x) }
        out[0] = cubic(a[0], p1x, p2x, b[0], u)
        out[1] = cubic(a[1], p1y, p2y, b[1], u)
        return out
    }

    private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
        val m = 1f - u
        return m * m * m * p0 + 3f * m * m * u * p1 + 3f * m * u * u * p2 + u * u * u * p3
    }

    private const val ARC = 24
}
