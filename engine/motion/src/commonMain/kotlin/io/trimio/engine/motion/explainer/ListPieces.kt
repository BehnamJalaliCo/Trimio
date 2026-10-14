package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.lerp
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.fill
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.recipe.Kit

/** Uniform items flowed into centred rows, in reading order (right to left in Persian). */
internal class Flow(val centres: List<Pair<Float, Float>>, val w: Float, val h: Float) {
    companion object {
        fun of(n: Int, iw: Float, ih: Float, gap: Float, rowGap: Float, maxW: Float, perRow: Int, rtl: Boolean): Flow {
            val fit = ((maxW + gap) / (iw + gap)).toInt().coerceAtLeast(1)
            val cols = minOf(n, perRow, fit).coerceAtLeast(1)
            val rows = (n + cols - 1) / cols
            val w = cols * iw + (cols - 1) * gap
            val h = rows * ih + (rows - 1) * rowGap
            val centres = List(n) { i ->
                val r = i / cols
                val inRow = minOf(cols, n - r * cols)
                val c = i % cols
                val rowW = inRow * iw + (inRow - 1) * gap
                val left = (w - rowW) / 2f
                val k = if (rtl) inRow - 1 - c else c
                (left + k * (iw + gap) + iw / 2f) to (r * (ih + rowGap) + ih / 2f)
            }
            return Flow(centres, w, h)
        }
    }
}

/**
 * The list primitives: chips, icon tiles, task tiles, a checklist, steps, a timeline, a grid, a
 * stack and a chat. Items arrive one by one where they will stay; checks tick them, highlights
 * pick them out in the chapter's accent.
 */
internal object ListPieces {
    /** How far an unticked tile's text lines go from the track toward the muted ink. */
    private const val IDLE_TEXT = 0.5f

    private fun labels(s: Spec, h: Hand, fallback: Int = 0): List<String> =
        s.labels.map { h.say(it) }.ifEmpty { if (fallback > 0) List(fallback) { "" } else listOf(h.say(s.text.ifEmpty { "…" })) }

    /** Pills in rows: checked ones fill with the accent, picked ones take an accent rim. */
    val Chips = Piece { s, h ->
        val texts = labels(s, h)
        val fits = h.fitAll(texts, Voices.body, 30f * h.u, minOf(h.maxW, 560f * h.u), 1)
        val ph = 68f * h.u
        val pad = 30f * h.u
        val icons = s.items.map { it.visual.ifEmpty { s.visual } }
        val iconW = 36f * h.u
        val widths = fits.mapIndexed { i, f -> f.width + pad * 2f + if (icons.getOrNull(i).isNullOrEmpty()) 0f else iconW + 12f * h.u }
        val gap = 16f * h.u
        val maxW = minOf(h.maxW, 940f * h.u)
        val rows = h.wrap(widths, maxW, gap)
        val w = rows.maxOf { r -> r.sumOf { widths[it].toDouble() }.toFloat() + gap * (r.size - 1) }
        val ht = rows.size * ph + (rows.size - 1) * gap
        val boxes = HashMap<Int, Box>()
        for ((ri, r) in rows.withIndex()) {
            val rw = r.sumOf { widths[it].toDouble() }.toFloat() + gap * (r.size - 1)
            var x = if (h.rtl) (w + rw) / 2f else (w - rw) / 2f
            for (i in r) {
                val left = if (h.rtl) x - widths[i] else x
                boxes[i] = Box(left, ri * (ph + gap), left + widths[i], ri * (ph + gap) + ph)
                x += h.dir * (widths[i] + gap)
            }
        }
        val slots = texts.indices.map { i -> boxes.getValue(i).let { Slot(it.left, it.top, it.width, it.height, ph / 2f, h.card) } }
        Laid(w, ht, slots) { t ->
            texts.indices.map { i ->
                val b = boxes.getValue(i)
                val icon = icons.getOrNull(i).orEmpty()
                val tx = if (icon.isEmpty()) b.centerX else b.centerX + h.dir * (iconW + 12f * h.u) / 2f
                val ix = if (h.rtl) b.right - pad - iconW / 2f else b.left + pad + iconW / 2f
                val at = t.revealAt(i)
                fun body(fill: ColorAnim, rim: ColorAnim?, ink: ColorAnim, on: androidx.compose.ui.graphics.Color, glyph: ColorAnim): List<Node> = listOfNotNull(
                    h.rect(b.centerX, b.centerY, b.width, b.height, ph / 2f, fill, stroke = rim?.let { Stroke(Fill.Solid(it), 2.5f * h.u) }),
                    if (icon.isNotEmpty()) h.glyph(icon, ix, b.centerY, iconW, glyph, at + 0.1f) else null,
                    h.words(texts[i], fits[i], ink, tx, b.centerY, on, at + 0.05f, Kit.Reveal.Fade, name = "chip"),
                )
                val plain = body(ColorAnim.of(h.card), ColorAnim.of(h.pal.rim), ColorAnim.of(h.ink), h.card, ColorAnim.of(h.accentOnCard))
                val picked = h.toggled(body(ColorAnim.of(h.wash), ColorAnim.of(h.accent), ColorAnim.of(h.ink), h.wash, ColorAnim.of(h.readable(h.accent, h.wash))), t.pickSteps(i), "chip-pick")
                val checked = t.checkedAt(i)?.let { c -> h.layer(body(ColorAnim.of(h.accentBody), null, ColorAnim.of(h.onAccent), h.accentBody, ColorAnim.of(h.onAccent)), c, "chip-on") }
                h.pop(listOfNotNull(h.rect(b.centerX, b.centerY + 3f * h.u, b.width, b.height, ph / 2f, ColorAnim.of(h.pal.rim.copy(alpha = 0.05f)))) + plain + listOfNotNull(picked, checked), b,
                    at, "chip")
            }
        }
    }

    /** Square tiles: a picture on a soft accent square, the label under it, inside the tile. */
    val IconTiles = Piece { s, h ->
        val texts = labels(s, h)
        val fits = h.fitAll(texts, Voices.label, 26f * h.u, 240f * h.u, 1)
        val tw = maxOf(150f * h.u, (fits.maxOfOrNull { it.width } ?: 0f) + 36f * h.u)
        val th = 170f * h.u
        val flow = Flow.of(texts.size, tw, th, 22f * h.u, 26f * h.u, minOf(h.maxW, 960f * h.u), if (h.maxW > h.maxH * 1.4f) 6 else 4, h.rtl)
        val sq = 66f * h.u
        val slots = flow.centres.map { (x, y) -> Slot(x - tw / 2f, y - th / 2f, tw, th, 26f * h.u, h.card) }
        Laid(flow.w, flow.h, slots) { t ->
            texts.indices.map { i ->
                val (cx, cy) = flow.centres[i]
                val at = t.revealAt(i)
                val q = s.items.getOrNull(i)?.visual?.ifEmpty { null } ?: s.items.getOrNull(i)?.label ?: s.visual
                val iy = cy - th / 2f + 22f * h.u + sq / 2f
                val ly = cy + th / 2f - 22f * h.u - fits[i].height / 2f
                val square = h.rect(cx, iy, sq, sq, 18f * h.u, h.wash)
                val glyph = h.glyph(q, cx, iy, sq * 0.52f, ColorAnim.of(h.readable(h.accent, h.wash)), at + 0.12f)
                val picked = h.toggled(
                    listOf(h.rect(cx, cy, tw, th, 26f * h.u, null, stroke = Stroke(h.accent.fill(), 3f * h.u)), h.rect(cx, iy, sq, sq, 18f * h.u, h.accentBody), h.glyph(q, cx, iy, sq * 0.52f,
                        ColorAnim.of(h.onAccent), 0f)),
                    t.pickSteps(i), "tile-pick",
                )
                val tick = t.checkedAt(i)?.let { h.tick(cx + h.dir * (tw / 2f - 8f * h.u), cy + th / 2f - 8f * h.u, 40f * h.u, it) }
                h.pop(
                    listOfNotNull(h.card(cx, cy, tw, th, 26f * h.u), square, glyph, picked, h.words(texts[i], fits[i], h.ink, cx, ly, h.card, at + 0.15f, Kit.Reveal.Fade, name = "tile"), tick),
                    Box.around(cx, cy, tw, th), at, "tile",
                )
            }
        }
    }

    /** Little documents in a row that get ticked one by one. */
    val TaskTiles = Piece { s, h ->
        val n = if (s.items.isNotEmpty()) s.items.size else (s.value?.takeIf { it >= 1 }?.toInt() ?: 5).coerceIn(1, 12)
        val texts = s.labels.map { h.say(it) }
        val fits = if (texts.any { it.isNotEmpty() }) h.fitAll(texts, Voices.label, 24f * h.u, 170f * h.u, 2) else emptyList()
        val tw = 104f * h.u
        val th = 120f * h.u
        val lh = fits.maxOfOrNull { it.height }?.let { it + 16f * h.u } ?: 0f
        val iw = maxOf(tw, fits.maxOfOrNull { it.width } ?: 0f)
        val flow = Flow.of(n, iw, th + lh, 22f * h.u, 30f * h.u, minOf(h.maxW, 920f * h.u), 6, h.rtl)
        val slots = flow.centres.map { (x, y) -> Slot(x - tw / 2f, y - (th + lh) / 2f, tw, th, 16f * h.u, h.card) }
        Laid(flow.w, flow.h, slots) { t ->
            (0 until n).map { i ->
                val (cx, cyAll) = flow.centres[i]
                val cy = cyAll - (th + lh) / 2f + th / 2f
                val at = t.revealAt(i)
                val done = t.doneAt(i, n)
                // A tile's text lines read as text before they are ticked (the track alone faded into the card on dark grounds).
                val idle = lerp(h.pal.track, h.muted, IDLE_TEXT)
                val lines = listOf(0.58f, 0.72f, 0.46f).mapIndexed { k, f ->
                    val y = cy - th / 2f + 30f * h.u + k * 22f * h.u
                    val c = done?.let { d -> ColorAnim.tween(idle, lerp(idle, h.accent, 0.6f), d, d + Track.TURN) } ?: ColorAnim.of(idle)
                    h.rect(cx - h.dir * (tw * (0.72f - f) / 2f), y, tw * f, 9f * h.u, 4.5f * h.u, c)
                }
                val tick = done?.let { h.tick(cx + h.dir * (tw / 2f - 6f * h.u), cy + th / 2f - 6f * h.u, 38f * h.u, it) }
                val label = fits.getOrNull(i)?.takeIf { texts[i].isNotEmpty() }?.let { h.words(texts[i], it, h.muted, cx, cy + th / 2f + 16f * h.u + it.height / 2f, h.ground, at + 0.1f,
                    Kit.Reveal.Fade, name = "task") }
                val picked = h.toggled(listOf(h.rect(cx, cy, tw, th, 16f * h.u, null, stroke = Stroke(h.accent.fill(), 3f * h.u))), t.pickSteps(i), "task-pick")
                h.pop(listOfNotNull(h.card(cx, cy, tw, th, 16f * h.u)) + lines + listOfNotNull(picked, tick, label), Box.around(cx, cy, tw, th), at, "task")
            }
        }
    }

    /** Rows with boxes on a card: a check fills the box and draws its tick. */
    val Checklist = Piece { s, h ->
        val texts = labels(s, h)
        val box = 42f * h.u
        val inner = minOf(h.maxW, 820f * h.u) - box - 120f * h.u
        val fits = h.fitAll(texts, Voices.body, 34f * h.u, inner, 2)
        val rowH = maxOf(78f * h.u, (fits.maxOfOrNull { it.height } ?: 0f) + 24f * h.u)
        val pad = 34f * h.u
        val w = (fits.maxOfOrNull { it.width } ?: 0f) + box + 28f * h.u + pad * 2f
        val ht = rowH * texts.size + pad * 2f - 12f * h.u
        val slots = texts.indices.map { i -> Slot(pad, pad - 6f * h.u + i * rowH, w - pad * 2f, rowH - 10f * h.u, 14f * h.u, h.pal.track) }
        Laid(w, ht, slots) { t ->
            listOf(h.card(w / 2f, ht / 2f, w, ht)) + texts.indices.map { i ->
                val cy = pad - 6f * h.u + i * rowH + rowH / 2f
                val bx = if (h.rtl) w - pad - box / 2f else pad + box / 2f
                val tx = if (h.rtl) w - pad - box - 28f * h.u - fits[i].width / 2f else pad + box + 28f * h.u + fits[i].width / 2f
                val at = t.revealAt(i)
                val done = t.doneAt(i, texts.size)
                val wash = h.toggled(listOf(h.rect(w / 2f, cy, w - pad, rowH - 8f * h.u, 14f * h.u, h.wash)), t.pickSteps(i), "row-pick")
                val empty = h.rect(bx, cy, box, box, 11f * h.u, ColorAnim.of(h.card), stroke = Stroke(h.ink.copy(alpha = 0.32f).fill(), 2.5f * h.u))
                val filled = done?.let { d ->
                    h.layer(
                        listOf(
                            h.rect(bx, cy, box, box, 11f * h.u, h.accentBody),
                            h.check(bx, cy, box * 0.7f, d + 0.08f),
                        ),
                        d, "box-on",
                    )
                }
                h.rise(listOfNotNull(wash, empty, filled, h.words(texts[i], fits[i], h.ink, tx, cy, if (wash != null) h.wash else h.card, at + 0.05f, Kit.Reveal.Fade, name = "row")), Box(0f,
                    cy - rowH / 2f, w, cy + rowH / 2f), at, "row")
            }
        }
    }
}
