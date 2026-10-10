package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.Subject

/**
 * Where the speaker's face is, from a few small frames: skin-coloured cells (YCbCr range that
 * holds across skin tones) seen in most frames, the largest connected patch of them, and its
 * upper part as the head. Graphics over footage then keep above the head and below the chin.
 * Platforms with a face detector (ML Kit on Android) can pass a better [Subject] directly.
 */
object SubjectFinder {

    /** [frames]: RGBA, [width]×[height] each (small: ~90×160 is plenty). */
    fun find(frames: List<ByteArray>, width: Int, height: Int): Subject? {
        if (frames.isEmpty()) return null
        val cell = maxOf(2, width / CELLS_ACROSS)
        val gw = width / cell
        val gh = height / cell
        val votes = IntArray(gw * gh)
        for (f in frames) for (gy in 0 until gh) for (gx in 0 until gw) {
            if (skinCell(f, width, gx * cell, gy * cell, cell)) votes[gy * gw + gx]++
        }
        val need = (frames.size * 0.6f).toInt().coerceAtLeast(1)
        val on = BooleanArray(votes.size) { votes[it] >= need }
        val blob = largest(on, gw, gh) ?: return null
        if (blob.size < MIN_CELLS) return null
        val xs = blob.map { it % gw }
        val ys = blob.map { it / gw }
        val top = ys.min()
        // The face is roughly as tall as it is wide; below it skin continues (neck, hands) or not.
        val faceW = (xs.max() - xs.min() + 1)
        val bottom = minOf(ys.max(), top + (faceW * 1.35f).toInt())
        return Subject(
            left = (xs.min().toFloat() / gw - MARGIN).coerceIn(0f, 1f),
            top = (top.toFloat() / gh - MARGIN).coerceIn(0f, 1f),
            right = ((xs.max() + 1).toFloat() / gw + MARGIN).coerceIn(0f, 1f),
            bottom = ((bottom + 1).toFloat() / gh + MARGIN).coerceIn(0f, 1f),
        )
    }

    /** At least half the pixels of the [cell]-sized square at ([x0], [y0]) are skin. */
    private fun skinCell(f: ByteArray, width: Int, x0: Int, y0: Int, cell: Int): Boolean {
        var skin = 0
        for (y in y0 until y0 + cell) for (x in x0 until x0 + cell) {
            val i = (y * width + x) * 4
            if (isSkin(f[i].toInt() and 0xFF, f[i + 1].toInt() and 0xFF, f[i + 2].toInt() and 0xFF)) skin++
        }
        return skin * 2 >= cell * cell
    }

    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return y > 45f && cb in 77f..127f && cr in 135f..173f && r > b
    }

    private fun largest(on: BooleanArray, w: Int, h: Int): List<Int>? {
        val seen = BooleanArray(on.size)
        var best: List<Int>? = null
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            val blob = mutableListOf<Int>()
            val stack = ArrayDeque<Int>().apply { addLast(start) }
            seen[start] = true
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                blob += c
                val x = c % w
                val y = c / w
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (on[n] && !seen[n]) { seen[n] = true; stack.addLast(n) }
                }
            }
            if (blob.size > (best?.size ?: 0)) best = blob
        }
        return best
    }

    private const val CELLS_ACROSS = 30
    private const val MIN_CELLS = 12
    private const val MARGIN = 0.02f
    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
