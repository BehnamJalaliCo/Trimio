package io.trimio.engine.illustration.style

import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Raster
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the styler draws from: a picture ([image], straight alpha) plus what is known about its
 * structure — the painted areas of a vector drawing ([regions]) or the line strength found in a photo
 * ([edges]) — and where the accent paints ([accent], 0..1).
 */
class StyleInput(
    val image: Raster,
    val regions: IntArray? = null,
    val edges: FloatArray? = null,
    val accent: FloatArray? = null,
)

/**
 * The procedural styler that unifies every source into the film's look: lines where painted areas
 * meet (or where a photo has edges) drawn with the hand's pressure and a slight double stroke, tone
 * turned into hatching (one, two, three directions as it darkens) over a light graphite smudge, paper
 * grain, dark areas as solid graphite, and the accent laid flat under the lines. Monochrome except the
 * accent, whatever the source's colours were. Deterministic for a [FilmLook.seed].
 */
class SketchStyler(private val look: FilmLook) {
    private val noise = Noise(look.seed)

    fun style(input: StyleInput): Raster {
        val img = input.image
        val w = img.width
        val h = img.height
        val n = w * h
        val unit = min(w, h) / REFERENCE_SIZE
        val luma = FloatArray(n)
        val alpha = FloatArray(n)
        for (i in 0 until n) {
            val c = img.argb[i]
            alpha[i] = Colors.a(c) / BYTE
            luma[i] = Colors.luma(c)
        }
        val lines = lines(input, luma, alpha, w, h, unit)
        val interior = if (look.style == ArtStyle.Ink) null else interior(luma, alpha, w, h, unit)
        val accent = input.accent
        val out = IntArray(n)
        val ink = look.ink
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val line = lines[i]
                val a = max(alpha[i], line)
                if (a <= ALPHA_EPS) continue
                val acc = accent?.get(i) ?: 0f
                val d0 = darkness(luma[i], alpha[i])
                val d = if (interior != null && interior[i] > 0f) min(d0, INTERIOR_DARK) else d0
                val grain = grain(x, y, unit)
                val tone = tone(x, y, d, unit, acc)
                val dark = max(line * grain, tone)
                val base = if (acc > 0f) Colors.lerp(look.paper, shadedAccent(luma[i]), acc) else look.paper
                val c = Colors.lerp(base, ink, dark.coerceIn(0f, 1f))
                out[i] = Colors.withAlpha(c, (a * BYTE).roundToInt())
            }
        }
        return Raster(w, h, out)
    }

    /**
     * Inside large dark fills (hair, a headscarf, a black jacket): pixels farther than a few line widths
     * from any light area. They are hatched densely instead of filled solid, as a pencil artist would;
     * the dark outline around them stays ink.
     */
    private fun interior(luma: FloatArray, alpha: FloatArray, w: Int, h: Int, unit: Float): FloatArray {
        val light = FloatArray(w * h) { i -> if (alpha[i] < HALF || luma[i] > DARK_LUMA) 1f else 0f }
        val near = Fields.dilate(light, w, h, (INTERIOR_RADIUS * unit).roundToInt().coerceAtLeast(1))
        return FloatArray(w * h) { i -> if (near[i] <= 0f) 1f else 0f }
    }

    /** How dark an area should read: 0 paper … 1 ink (a light lift so mid-greys keep some paper). */
    private fun darkness(l: Float, a: Float): Float {
        if (a <= ALPHA_EPS) return 0f
        val d = 1f - l
        return (d * d * (TONE_CURVE - (TONE_CURVE - 1f) * d)).coerceIn(0f, 1f)
    }

    /** Hatching, smudge and solid graphite for darkness [d]; lighter over the accent so it stays bright. */
    private fun tone(x: Int, y: Int, d: Float, unit: Float, accent: Float): Float {
        if (d < MIN_TONE) return 0f
        val solid = Fields.ramp(d, SOLID_FROM, SOLID_TO)
        val grainy = grain(x, y, unit)
        val solidInk = solid * SOLID_INK * grainy
        return when (look.style) {
            ArtStyle.Flat -> max(solidInk, posterized(d))
            else -> {
                val spacing = HATCH_SPACING * unit / look.hatching
                val h1 = hatch(x, y, ANGLE_1, spacing, unit) * Fields.ramp(d, H1_FROM, H1_TO)
                val h2 = hatch(x, y, ANGLE_2, spacing, unit) * Fields.ramp(d, H2_FROM, H2_TO)
                val h3 = hatch(x, y, ANGLE_3, spacing * DENSE, unit) * Fields.ramp(d, H3_FROM, H3_TO)
                val strength = if (look.style == ArtStyle.Ink) INK_HATCH else PENCIL_HATCH
                val hatching = max(h1, max(h2 * H2_WEIGHT, h3 * H3_WEIGHT)) * strength * grainy
                val smudge = if (look.style == ArtStyle.Pencil) d * SMUDGE * (1f - accent) else 0f
                val onAccent = 1f - accent * ACCENT_SOFTEN
                max(solidInk, max(hatching, smudge) * onAccent)
            }
        }
    }

    private fun posterized(d: Float): Float = when {
        d < FLAT_LIGHT -> 0f
        d < FLAT_MID -> FLAT_TONE_1
        else -> FLAT_TONE_2
    }

    /** One family of parallel hatch lines: about a pixel wide, wobbling, broken into strokes. */
    private fun hatch(x: Int, y: Int, angle: Float, spacing: Float, unit: Float): Float {
        val c = cos(angle)
        val s = sin(angle)
        val u = (x * c + y * s) / spacing + noise.smooth(x / WOBBLE_SCALE / unit, y / WOBBLE_SCALE / unit) * WOBBLE
        val v = (-x * s + y * c) / (spacing * STROKE_LENGTH)
        val row = floor(u)
        val f = u - row
        val dist = min(f, 1f - f) * spacing
        val width = HATCH_WIDTH * unit * look.lineWeight
        val on = 1f - Fields.ramp(dist, width * HATCH_CORE, width)
        // Strokes end and restart along their row, staggered row to row.
        val gap = noise.smooth(v + row * ROW_SHIFT, row * ROW_SCALE)
        return on * Fields.ramp(gap, GAP_FROM, GAP_TO)
    }

    private fun grain(x: Int, y: Int, unit: Float): Float {
        if (look.style == ArtStyle.Ink || look.style == ArtStyle.Flat) return 1f
        val fine = noise.hash(x, y)
        val pressure = noise.smooth(x / PRESSURE_SCALE / unit, y / PRESSURE_SCALE / unit)
        return (GRAIN_BASE + GRAIN_FINE * fine) * (PRESSURE_BASE + PRESSURE_RANGE * pressure)
    }

    private fun shadedAccent(l: Float): Int {
        val k = (ACCENT_SHADE_BASE + ACCENT_SHADE_RANGE * l).coerceIn(ACCENT_SHADE_MIN, 1f)
        return Colors.lerp(look.ink, look.accent, k)
    }

    /** Line strength 0..1: region boundaries (vector) or the given edges (photo), thickened and drawn twice with a wobble. */
    private fun lines(input: StyleInput, luma: FloatArray, alpha: FloatArray, w: Int, h: Int, unit: Float): FloatArray {
        val raw = input.edges ?: input.regions?.let { boundaries(it, luma, alpha, w, h) } ?: FloatArray(w * h)
        val radius = (LINE_RADIUS * unit * look.lineWeight).roundToInt().coerceAtLeast(0)
        val thick = Fields.blur(Fields.dilate(raw, w, h, radius), w, h, 1, passes = 1)
        if (look.style == ArtStyle.Flat) return thick
        val jitter = JITTER * unit
        val out = FloatArray(w * h)
        val scale = JITTER_SCALE * unit
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nx = noise.smooth(x / scale, y / scale) - HALF
                val ny = noise.smooth(x / scale + OFFSET, y / scale - OFFSET) - HALF
                val a = Fields.sample(thick, w, h, x + nx * jitter, y + ny * jitter)
                if (look.style == ArtStyle.Ink) {
                    out[y * w + x] = Fields.ramp(a, INK_EDGE_FROM, INK_EDGE_TO)
                    continue
                }
                val b = Fields.sample(thick, w, h, x - ny * jitter * SECOND_STROKE, y + nx * jitter * SECOND_STROKE)
                out[y * w + x] = max(a, b * SECOND_WEIGHT).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /** Where one painted area meets another (by contrast) or the drawing meets the paper (always a line). */
    private fun boundaries(regions: IntArray, luma: FloatArray, alpha: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var e = 0f
                if (x + 1 < w) e = max(e, edge(i, i + 1, regions, luma, alpha))
                if (y + 1 < h) e = max(e, edge(i, i + w, regions, luma, alpha))
                if (e > 0f) {
                    out[i] = max(out[i], e)
                    if (x + 1 < w) out[i + 1] = max(out[i + 1], e * NEIGHBOUR)
                    if (y + 1 < h) out[i + w] = max(out[i + w], e * NEIGHBOUR)
                }
            }
        }
        return out
    }

    private fun edge(i: Int, j: Int, regions: IntArray, luma: FloatArray, alpha: FloatArray): Float {
        val inI = alpha[i] >= HALF
        val inJ = alpha[j] >= HALF
        if (inI != inJ) return 1f
        if (!inI || regions[i] == regions[j]) return 0f
        val contrast = abs(luma[i] - luma[j])
        return if (contrast < MIN_CONTRAST) 0f else (contrast * CONTRAST_GAIN + CONTRAST_BASE).coerceAtMost(1f)
    }

    private companion object {
        const val BYTE = 255f
        const val HALF = 0.5f
        const val OFFSET = 31.7f
        const val ALPHA_EPS = 0.004f
        const val REFERENCE_SIZE = 384f
        const val TONE_CURVE = 1.6f
        const val DARK_LUMA = 0.22f
        const val INTERIOR_RADIUS = 2.6f
        const val INTERIOR_DARK = 0.58f
        const val MIN_TONE = 0.04f
        const val SOLID_FROM = 0.80f
        const val SOLID_TO = 0.93f
        const val SOLID_INK = 0.9f
        const val HATCH_SPACING = 3.1f
        const val DENSE = 0.8f
        const val ANGLE_1 = 0.785f
        const val ANGLE_2 = -0.785f
        const val ANGLE_3 = 0.12f
        const val H1_FROM = 0.10f
        const val H1_TO = 0.32f
        const val H2_FROM = 0.36f
        const val H2_TO = 0.58f
        const val H3_FROM = 0.60f
        const val H3_TO = 0.80f
        const val H2_WEIGHT = 0.95f
        const val H3_WEIGHT = 0.9f
        const val PENCIL_HATCH = 0.62f
        const val INK_HATCH = 0.9f
        const val SMUDGE = 0.2f
        const val ACCENT_SOFTEN = 0.55f
        const val FLAT_LIGHT = 0.18f
        const val FLAT_MID = 0.55f
        const val FLAT_TONE_1 = 0.16f
        const val FLAT_TONE_2 = 0.38f
        const val WOBBLE_SCALE = 18f
        const val WOBBLE = 0.35f
        const val STROKE_LENGTH = 7f
        const val ROW_SHIFT = 0.37f
        const val ROW_SCALE = 0.61f
        const val GAP_FROM = 0.18f
        const val GAP_TO = 0.3f
        const val HATCH_WIDTH = 0.55f
        const val HATCH_CORE = 0.35f
        const val PRESSURE_SCALE = 22f
        const val GRAIN_BASE = 0.72f
        const val GRAIN_FINE = 0.28f
        const val PRESSURE_BASE = 0.78f
        const val PRESSURE_RANGE = 0.3f
        const val ACCENT_SHADE_BASE = 0.72f
        const val ACCENT_SHADE_RANGE = 0.4f
        const val ACCENT_SHADE_MIN = 0.55f
        const val LINE_RADIUS = 0.45f
        const val JITTER = 0.9f
        const val JITTER_SCALE = 14f
        const val SECOND_STROKE = 1.3f
        const val SECOND_WEIGHT = 0.5f
        const val INK_EDGE_FROM = 0.15f
        const val INK_EDGE_TO = 0.45f
        const val NEIGHBOUR = 0.6f
        const val MIN_CONTRAST = 0.035f
        const val CONTRAST_GAIN = 2.6f
        const val CONTRAST_BASE = 0.3f
    }
}
