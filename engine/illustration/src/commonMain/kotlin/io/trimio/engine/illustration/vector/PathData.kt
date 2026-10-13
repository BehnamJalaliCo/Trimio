package io.trimio.engine.illustration.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A flattened subpath: x,y pairs; [closed] when it ends with Z (strokes join back to the start). */
class Polyline(val points: FloatArray, val closed: Boolean) {
    val size: Int get() = points.size / 2

    fun transformed(m: Affine): Polyline {
        if (m == Affine.Identity) return this
        val out = FloatArray(points.size)
        for (i in 0 until size) {
            val x = points[i * 2]
            val y = points[i * 2 + 1]
            out[i * 2] = m.x(x, y)
            out[i * 2 + 1] = m.y(x, y)
        }
        return Polyline(out, closed)
    }
}

/**
 * SVG path data (`d`) parsed and flattened to [Polyline]s: M L H V C S Q T A Z in absolute and relative
 * forms, curves subdivided until they deviate less than [tolerance] user units from the straight line.
 */
class PathData(private val tolerance: Float) {
    private val out = mutableListOf<Polyline>()
    private val pts = ArrayList<Float>()
    private var cx = 0f
    private var cy = 0f
    private var sx = 0f
    private var sy = 0f
    private var lastCtrlX = 0f
    private var lastCtrlY = 0f
    private var lastCmd = ' '

    fun parse(d: String): List<Polyline> {
        val tokens = tokenize(d)
        var i = 0
        var cmd = ' '
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Char) {
                cmd = t
                i++
            }
            i = if (cmd == 'Z' || cmd == 'z') closed(cmd, i, t is Char) else applied(cmd, tokens, i)
            // A moveto followed by bare pairs continues as lineto.
            if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
        }
        flush(false)
        return out
    }

    /** Closes the subpath; a stray number after Z is skipped. */
    private fun closed(cmd: Char, i: Int, fromLetter: Boolean): Int {
        if (fromLetter) {
            close()
            lastCmd = cmd
        }
        return if (fromLetter) i else i + 1
    }

    /** Runs one command with its arguments from [i]; the end of the tokens when they are malformed. */
    private fun applied(cmd: Char, tokens: List<Any>, i: Int): Int {
        val n = ARGS[cmd.uppercaseChar()] ?: return tokens.size
        if (i + n > tokens.size || (0 until n).any { tokens[i + it] !is Float }) return tokens.size
        command(cmd, FloatArray(n) { tokens[i + it] as Float })
        lastCmd = cmd
        return i + n
    }

    private fun command(cmd: Char, a: FloatArray) {
        val rel = cmd.isLowerCase()
        val ox = if (rel) cx else 0f
        val oy = if (rel) cy else 0f
        when (cmd.uppercaseChar()) {
            'M' -> {
                flush(false)
                cx = a[0] + ox; cy = a[1] + oy; sx = cx; sy = cy
                add(cx, cy)
            }
            'L' -> lineTo(a[0] + ox, a[1] + oy)
            'H' -> lineTo(a[0] + ox, cy)
            'V' -> lineTo(cx, a[0] + oy)
            'C' -> cubic(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy, a[4] + ox, a[5] + oy)
            'S' -> {
                val (rx, ry) = reflected("CcSs")
                cubic(rx, ry, a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy)
            }
            'Q' -> quad(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy)
            'T' -> {
                val (rx, ry) = reflected("QqTt")
                quad(rx, ry, a[0] + ox, a[1] + oy)
            }
            'A' -> arc(a[0], a[1], a[2], a[3] != 0f, a[4] != 0f, a[5] + ox, a[6] + oy)
        }
    }

    private fun reflected(kinds: String): Pair<Float, Float> =
        if (lastCmd in kinds) Pair(2 * cx - lastCtrlX, 2 * cy - lastCtrlY) else Pair(cx, cy)

    private fun add(x: Float, y: Float) {
        pts.add(x); pts.add(y)
    }

    private fun lineTo(x: Float, y: Float) {
        if (pts.isEmpty()) add(cx, cy)
        add(x, y)
        cx = x; cy = y
        lastCtrlX = x; lastCtrlY = y
    }

    private fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
        if (pts.isEmpty()) add(cx, cy)
        val len = hypot(x1 - cx, y1 - cy) + hypot(x2 - x1, y2 - y1) + hypot(x - x2, y - y2)
        val n = segments(len)
        val x0 = cx
        val y0 = cy
        for (k in 1..n) {
            val t = k.toFloat() / n
            val u = 1 - t
            add(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x, u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y)
        }
        cx = x; cy = y; lastCtrlX = x2; lastCtrlY = y2
    }

    private fun quad(x1: Float, y1: Float, x: Float, y: Float) {
        if (pts.isEmpty()) add(cx, cy)
        val n = segments(hypot(x1 - cx, y1 - cy) + hypot(x - x1, y - y1))
        val x0 = cx
        val y0 = cy
        for (k in 1..n) {
            val t = k.toFloat() / n
            val u = 1 - t
            add(u * u * x0 + 2 * u * t * x1 + t * t * x, u * u * y0 + 2 * u * t * y1 + t * t * y)
        }
        cx = x; cy = y; lastCtrlX = x1; lastCtrlY = y1
    }

    /** Endpoint arc to centre form (SVG implementation notes F.6.5), then flattened. */
    @Suppress("LongParameterList")
    private fun arc(rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
        if (pts.isEmpty()) add(cx, cy)
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx == 0f || ry == 0f || samePoint(x, y)) {
            lineTo(x, y)
            return
        }
        val phi = angle * PI.toFloat() / HALF_TURN
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (cx - x) / 2
        val dy = (cy - y) / 2
        val x1 = cp * dx + sp * dy
        val y1 = -sp * dx + cp * dy
        val lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
        if (lambda > 1f) {
            rx *= sqrt(lambda); ry *= sqrt(lambda)
        }
        val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val coef = (if (large == sweep) -1f else 1f) * sqrt(max(0f, num / den))
        val cxp = coef * rx * y1 / ry
        val cyp = -coef * ry * x1 / rx
        val ccx = cp * cxp - sp * cyp + (cx + x) / 2
        val ccy = sp * cxp + cp * cyp + (cy + y) / 2
        val t1 = vectorAngle(1f, 0f, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var dt = vectorAngle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI.toFloat() else if (sweep && dt < 0) dt += 2 * PI.toFloat()
        val n = segments(abs(dt) * max(rx, ry))
        for (k in 1..n) {
            val t = t1 + dt * k / n
            val ex = rx * cos(t)
            val ey = ry * sin(t)
            add(cp * ex - sp * ey + ccx, sp * ex + cp * ey + ccy)
        }
        cx = x; cy = y; lastCtrlX = x; lastCtrlY = y
    }

    private fun samePoint(x: Float, y: Float): Boolean = x == cx && y == cy

    private fun vectorAngle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
        val dot = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))
        val a = acos(dot.coerceIn(-1f, 1f))
        return if (ux * vy - uy * vx < 0) -a else a
    }

    /** Pieces for a curve of control length [len]: the chord error shrinks with n², so n grows with √(len/tolerance). */
    private fun segments(len: Float): Int = ceil(sqrt(len / tolerance) * SEGMENT_FACTOR).toInt().coerceIn(2, MAX_SEGMENTS)

    private fun close() {
        flush(true)
        cx = sx; cy = sy
    }

    private fun flush(closed: Boolean) {
        if (pts.size >= 4) out.add(Polyline(pts.toFloatArray(), closed))
        pts.clear()
    }

    companion object {
        private const val MAX_SEGMENTS = 96
        private const val SEGMENT_FACTOR = 0.6f
        private const val HALF_TURN = 180f
        private val ARGS = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7)

        /** Every number in [s] (commas, spaces, signs and compact forms like `1.5.5` or `1e-3`). */
        fun numbers(s: String): List<Float> = tokenize(s).filterIsInstance<Float>()

        /** Commands as [Char], numbers as [Float]; arc flags may be written without separators (`a1 1 0 01 2 2`). */
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        fun tokenize(s: String): List<Any> {
            val out = ArrayList<Any>(s.length / 3)
            var i = 0
            var lastCmd = ' '
            var argIndex = 0
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch.isLetter() && ch != 'e' && ch != 'E' -> {
                        out.add(ch); lastCmd = ch.uppercaseChar(); argIndex = 0; i++
                    }
                    ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                        // Arc flags (4th and 5th arguments) are single digits.
                        val flag = lastCmd == 'A' && (argIndex % 7 == 3 || argIndex % 7 == 4)
                        if (flag && (ch == '0' || ch == '1')) {
                            out.add(if (ch == '1') 1f else 0f); i++; argIndex++
                            continue
                        }
                        var j = i
                        if (s[j] == '-' || s[j] == '+') j++
                        var dot = false
                        while (j < s.length && digitOrFirstDot(s[j], dot)) {
                            if (s[j] == '.') dot = true
                            j++
                        }
                        if (j < s.length && (s[j] == 'e' || s[j] == 'E')) {
                            var k = j + 1
                            if (k < s.length && (s[k] == '-' || s[k] == '+')) k++
                            if (k < s.length && s[k].isDigit()) {
                                j = k
                                while (j < s.length && s[j].isDigit()) j++
                            }
                        }
                        s.substring(i, j).toFloatOrNull()?.let { out.add(it); argIndex++ }
                        i = if (j > i) j else i + 1
                    }
                    else -> i++
                }
            }
            return out
        }

        private fun digitOrFirstDot(c: Char, dotSeen: Boolean): Boolean = c.isDigit() || (c == '.' && !dotSeen)

        /** Bounds of polylines as left, top, right, bottom (or null for none). */
        fun bounds(lines: List<Polyline>): FloatArray? {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (l in lines) {
                for (i in 0 until l.size) {
                    x0 = min(x0, l.points[i * 2]); x1 = max(x1, l.points[i * 2])
                    y0 = min(y0, l.points[i * 2 + 1]); y1 = max(y1, l.points[i * 2 + 1])
                }
            }
            return if (x1 < x0) null else floatArrayOf(x0, y0, x1, y1)
        }
    }
}
