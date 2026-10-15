package io.trimio.engine.autopilot

import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The look of a reference the creator attaches («مثل این ویدیو بساز», a motion they saved): an editor matching a
 * reference takes its palette first — elements, texts and motion in colours that belong together and to the picture —
 * then its tone and its pace. Read from the clip's frames alone, no model: the dominant colours (k-means over a sample
 * of pixels), whether it is dark or light, its accent (the most saturated colour covering a real share of the frame),
 * and how often it cuts (samples that change almost entirely from the one before).
 */
object ReferenceStyle {

    data class Read(
        /** Dominant colours, most covering first, as 0xRRGGBB. */
        val palette: List<Int>,
        val dark: Boolean,
        /** The colour the reference speaks in, or null when it is greyscale. */
        val accent: Int?,
        /** Cuts a second, when the frames are evenly spaced samples of the clip. */
        val cutsPerSecond: Float?,
    ) {
        /** The accent as the score writes it («#D4AF6A»). */
        val accentHex: String? get() = accent?.let { "#" + it.toString(HEX).padStart(HEX_DIGITS, '0').uppercase() }

        /** The pace it implies: «fast» past a cut a second and a half, «calm» under one in four seconds. */
        val pace: String? get() = cutsPerSecond?.let { if (it >= FAST_CUTS) "fast" else if (it <= CALM_CUTS) "calm" else "normal" }
    }

    /** [frames] of a clip [duration] seconds long, sampled evenly (the first and last included). */
    fun read(frames: List<RgbImage>, duration: Float? = null): Read? {
        val pixels = frames.flatMap { sample(it) }
        if (pixels.isEmpty()) return null
        val clusters = kMeans(pixels, K)
        val total = clusters.sumOf { it.second }.toFloat()
        val palette = clusters.sortedByDescending { it.second }.map { it.first }
        val light = pixels.sumOf { luma(it).toDouble() } / pixels.size
        val accent = clusters.filter { it.second / total >= MIN_ACCENT_SHARE }.maxByOrNull { saturation(it.first) * sqrt(it.second / total) }
            ?.first?.takeIf { saturation(it) >= MIN_SATURATION && luma(it) in MIN_ACCENT_LUMA..MAX_ACCENT_LUMA }
        val cuts = if (duration != null && duration > 0f && frames.size >= 3) {
            frames.zipWithNext().count { (a, b) -> change(a, b) >= CUT_CHANGE } / duration
        } else {
            null
        }
        return Read(palette, light < DARK_LUMA, accent, cuts)
    }

    /**
     * The style that answers [read]: one of the reference's tone (dark: noir; light: paper or lumen) whose accents come
     * closest in hue to the reference's, else the closest of any. Null when the reference has no colour to match.
     */
    fun style(read: Read, styles: List<StyleLanguage> = StyleBook.all): StyleLanguage? {
        val accent = read.accent ?: return null
        val tone = if (read.dark) "noir" else "paper"
        fun distance(s: StyleLanguage) = s.accents.mapNotNull(::parse).minOfOrNull { hueGap(it, accent) } ?: Float.MAX_VALUE
        val toned = styles.filter { tone in it.tones || (!read.dark && "lumen" in it.tones) }
        return toned.minByOrNull(::distance) ?: styles.minByOrNull(::distance)
    }

    private fun parse(hex: String): Int? = hex.removePrefix("#").takeIf { it.length == HEX_DIGITS }?.toIntOrNull(HEX)

    /** Up to [SAMPLES] pixels of [image] on a regular grid. */
    private fun sample(image: RgbImage): List<Int> {
        val n = image.width * image.height
        if (n == 0) return emptyList()
        val step = max(1, n / SAMPLES)
        return (0 until n step step).map { i ->
            val o = i * 3
            ((image.rgb[o].toInt() and BYTE) shl 16) or ((image.rgb[o + 1].toInt() and BYTE) shl 8) or (image.rgb[o + 2].toInt() and BYTE)
        }
    }

    /** The share of sampled pixels that changed a lot between [a] and [b] (a cut changes nearly all of them). */
    private fun change(a: RgbImage, b: RgbImage): Float {
        val pa = sample(a)
        val pb = sample(b)
        val n = min(pa.size, pb.size).takeIf { it > 0 } ?: return 0f
        return (0 until n).count { i -> distance(pa[i], pb[i]) > PIXEL_CHANGE }.toFloat() / n
    }

    /** k-means in RGB, seeded deterministically across the brightness range; each centre with how many pixels it holds. */
    private fun kMeans(pixels: List<Int>, k: Int): List<Pair<Int, Int>> {
        val sorted = pixels.sortedBy(::luma)
        var centres = (0 until k).map { sorted[(it * (sorted.size - 1)) / maxOf(1, k - 1)] }.distinct()
        var counts = IntArray(centres.size)
        repeat(ROUNDS) {
            val sums = Array(centres.size) { LongArray(3) }
            counts = IntArray(centres.size)
            for (p in pixels) {
                val c = centres.indices.minBy { distance(p, centres[it]) }
                counts[c]++
                sums[c][0] += (p shr 16 and BYTE).toLong()
                sums[c][1] += (p shr 8 and BYTE).toLong()
                sums[c][2] += (p and BYTE).toLong()
            }
            centres = centres.indices.map { c ->
                if (counts[c] == 0) centres[c] else ((sums[c][0] / counts[c]).toInt() shl 16) or ((sums[c][1] / counts[c]).toInt() shl 8) or (sums[c][2] / counts[c]).toInt()
            }
        }
        return centres.indices.filter { counts[it] > 0 }.map { centres[it] to counts[it] }
    }

    private fun channels(c: Int) = Triple(c shr 16 and BYTE, c shr 8 and BYTE, c and BYTE)

    private fun distance(a: Int, b: Int): Int {
        val (r1, g1, b1) = channels(a)
        val (r2, g2, b2) = channels(b)
        return abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
    }

    private fun luma(c: Int): Float {
        val (r, g, b) = channels(c)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / BYTE
    }

    private fun saturation(c: Int): Float {
        val (r, g, b) = channels(c)
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        return if (hi == 0) 0f else (hi - lo).toFloat() / hi
    }

    private fun hue(c: Int): Float {
        val (r, g, b) = channels(c)
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        if (hi == lo) return 0f
        val d = (hi - lo).toFloat()
        val h = when (hi) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    private fun hueGap(a: Int, b: Int): Float = abs(hue(a) - hue(b)).let { min(it, 360f - it) }

    private const val K = 6
    private const val ROUNDS = 8
    private const val SAMPLES = 2400
    private const val BYTE = 0xFF
    private const val HEX = 16
    private const val HEX_DIGITS = 6
    private const val DARK_LUMA = 0.38
    private const val MIN_ACCENT_SHARE = 0.03f
    private const val MIN_SATURATION = 0.35f
    private const val MIN_ACCENT_LUMA = 0.18f
    private const val MAX_ACCENT_LUMA = 0.92f
    private const val PIXEL_CHANGE = 120
    private const val CUT_CHANGE = 0.6f
    private const val FAST_CUTS = 1.5f
    private const val CALM_CUTS = 0.25f
}
