package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Ordered things: a ranking (leaderboard, podium, big numerals), a timeline (vertical rail,
 * horizontal track, stepped cards) and numbered steps (connected discs, chevrons, stacked
 * cards). Items land as they are said; on a Persian piece everything reads from the right and
 * counts in Persian digits.
 */
object ListFamilies {

    /** The beat's title above the graphic, when it has one: a small eyebrow in the family's label voice. */
    private fun eyebrow(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, b: Box, ground: Color?, size: Float): Pair<List<Node>, Float> {
        val title = cue.label?.takeIf { it.isNotBlank() } ?: return emptyList<Node>() to 0f
        val f = Kit.fit(fit, title, Kit.labelVoice(look, p.typo), size, b.width * 0.9f, size * 1.6f)
        val color = ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent
        val node = Kit.words(
            title, f, color, Kit.fromStart(Kit.startX(b, p.rtl, 0f), f.width, p.rtl), b.top + f.height / 2f, p.at,
            ground ?: look.canvas.takeIf { !cue.overMedia }, p.rtl, Kit.Reveal.Fade, name = "eyebrow",
        )
        return listOf(node) to f.height * 1.5f
    }

    /** Where free-standing words are read: the canvas on a stage; over footage the compiler's guard decides. */
    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    // ------------------------------------------------------------------ ranking

    /** A leaderboard: the first place leads, the rest follow it in order. */
    val Ranking = object : Family("ranking", listOf(Move.Rise, Move.Slide, Move.Flip)) {
        override val minHold = 3f
        override val preferredHeight = 0.34f

        override fun layoutWeight(look: Look, cue: Cue, layout: Int): Float = when {
            layout == 1 && Kit.items(cue, MAX).size < 3 -> 0f
            else -> 1f
        }

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val items = Kit.items(cue, MAX).ifEmpty { return Built(emptyList()) }
            val layout = if (p.layout == 1 && items.size < 3) 0 else p.layout
            return when (layout) {
                1 -> podium(cue, look, fit, p, items)
                2 -> numerals(cue, look, fit, p, items)
                else -> board(cue, look, fit, p, items)
            }
        }

        private fun value(cue: Cue, i: Int): String? = cue.points?.getOrNull(i)?.let { cue.prefix + Kit.format(it, cue.decimals, cue.rtl) + cue.suffix }

        private fun board(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val (head, headH) = eyebrow(cue, look, fit, p, b, free(look, cue), cue.width * 0.04f)
            val gap = b.height * 0.03f
            val rowH = ((b.height - headH - gap * (items.size - 1)) / items.size).coerceAtMost(b.width * 0.16f)
            val nodes = head.toMutableList()
            val valueW = items.indices.maxOf { i -> value(cue, i)?.let { Kit.fit(fit, it, Kit.numberVoice(look, p.typo), rowH * 0.42f, b.width * 0.3f, rowH * 0.7f).width + rowH * 0.3f } ?: 0f }
            val names = Kit.uniform(fit, items, Kit.titleVoice(look, p.typo), rowH * 0.4f, b.width - rowH * 0.62f - rowH * 0.8f - valueW, rowH * 0.8f)
            for ((i, item) in items.withIndex()) {
                val top = b.top + headH + i * (rowH + gap)
                val row = Box(b.left, top, b.right, top + rowH)
                val first = i == 0
                val s = Kit.surface(look, cue, if (first) Kit.Tone.Accent else Kit.Tone.Card)
                val ink = Kit.inkOn(look, s.ground)
                val t0 = Kit.itemAt(cue, i, p.at + 0.15f, 0.2f)
                val parts = mutableListOf<Node>()
                parts += Kit.panel(look, cue, s, row.centerX, row.centerY, row.width, rowH, rowH * 0.3f, "rank-row")
                val d = rowH * 0.62f
                val discX = Kit.startX(row, p.rtl, rowH * 0.2f + d / 2f)
                val disc = Kit.surface(look, cue, if (first) Kit.Tone.Ink else Kit.Tone.Accent)
                parts += Kit.dot(discX, row.centerY, d, disc.ground, "rank-disc")
                val rank = Kit.digits("${i + 1}", p.rtl)
                val rf = Kit.fit(fit, rank, Kit.numberVoice(look, p.typo), d * 0.6f, d * 0.8f, d * 0.8f)
                parts += Kit.words(rank, rf, Kit.inkOn(look, disc.ground), discX, row.centerY, t0 + 0.1f, disc.ground, p.rtl, Kit.Reveal.Pop, name = "rank-number")
                val v = value(cue, i)
                val vf = v?.let { Kit.fit(fit, it, Kit.numberVoice(look, p.typo), rowH * 0.42f, row.width * 0.3f, rowH * 0.7f) }
                val nameEdge = discX + p.dir * (d / 2f + rowH * 0.25f)
                val nf = names[i]
                parts += Kit.words(item, nf, ink, Kit.fromStart(nameEdge, nf.width, p.rtl), row.centerY, t0 + 0.12f, s.ground, p.rtl, name = "rank-name")
                if (v != null && vf != null) {
                    val endEdge = Kit.endX(row, p.rtl, rowH * 0.3f)
                    parts += Kit.words(
                        v, vf, if (first) ink else Kit.tint(look, look.positive, s.ground), Kit.fromEnd(endEdge, vf.width, p.rtl), row.centerY, t0 + 0.25f,
                        s.ground, p.rtl, Kit.Reveal.Fade, name = "rank-value",
                    )
                }
                nodes += Kit.item(p.move, row, t0, p.dir, parts, "rank-$i")
            }
            val sfx = items.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.15f, 0.2f), SfxKind.Click, 0.4f) }
            return Kit.built(cue, look, p, b, nodes, name, sfx)
        }

        /** The top three on blocks: second, first (tallest, accent), third. */
        private fun podium(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val (head, headH) = eyebrow(cue, look, fit, p, b, free(look, cue), cue.width * 0.04f)
            val order = listOf(1, 0, 2)
            val colW = b.width / 3f
            val nameH = (b.height - headH) * 0.24f
            val maxH = b.height - headH - nameH
            val nodes = head.toMutableList()
            for ((slot, rank) in order.withIndex()) {
                // Columns in reading order: on a Persian piece second place sits on the right.
                val col = if (p.rtl) 2 - slot else slot
                val cx = b.left + colW * (col + 0.5f)
                val h = maxH * listOf(1f, 0.74f, 0.56f)[rank]
                val bottom = b.bottom
                val s = Kit.surface(look, cue, if (rank == 0) Kit.Tone.Accent else Kit.Tone.Card)
                val t0 = p.at + 0.2f + (2 - rank) * 0.28f
                val block = ShapeNode(
                    ShapeSpec.Rect((colW * 0.86f).anim, Anim.tween(0f, h, t0, t0 + 0.6f, Easing.Spring(0.62f, 1.7f)), (colW * 0.08f * look.roundness).anim),
                    fill = s.fill, stroke = s.stroke, shadow = s.shadow, transform = Transform(x = cx.anim, y = bottom.anim, anchorY = 1f), name = "podium-block",
                )
                val rankText = Kit.digits("${rank + 1}", p.rtl)
                val rf = Kit.fit(fit, rankText, Kit.numberVoice(look, p.typo), minOf(colW * 0.42f, h * 0.5f), colW * 0.7f, h * 0.6f)
                val numeral = Kit.words(rankText, rf, Kit.inkOn(look, s.ground), cx, bottom - h + rf.height * 0.75f, t0 + 0.35f, s.ground, p.rtl, Kit.Reveal.Pop, name = "podium-rank")
                val name = items[rank]
                val nf = Kit.fit(fit, name, Kit.titleVoice(look, p.typo), nameH * 0.42f, colW * 0.92f, nameH * 0.9f, lines = 2)
                val ground = free(look, cue)
                val label = Kit.words(name, nf, ground?.let { Kit.inkOn(look, it) } ?: look.ink, cx, bottom - h - nameH * 0.5f, t0 + 0.45f, ground, p.rtl, name = "podium-name")
                val box = Box(cx - colW / 2f, bottom - h - nameH, cx + colW / 2f, bottom)
                nodes += Kit.item(p.move, box, t0, p.dir, listOf(block, numeral, label), "podium-$rank")
            }
            val sfx = listOf(Sfx(p.at + 0.2f, SfxKind.Tick, 0.4f), Sfx(p.at + 0.48f, SfxKind.Tick, 0.4f), Sfx(p.at + 0.76f, SfxKind.Pop, 0.6f))
            return Kit.built(cue, look, p, b, nodes, name, sfx)
        }

        /** Big numerals in the accent beside each name, no cards: an editorial countdown. */
        private fun numerals(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val (head, headH) = eyebrow(cue, look, fit, p, b, ground, cue.width * 0.04f)
            val rowH = ((b.height - headH) / items.size).coerceAtMost(b.width * 0.2f)
            val nodes = head.toMutableList()
            val numW = rowH * 0.9f
            val names = Kit.uniform(fit, items, Kit.titleVoice(look, p.typo), rowH * 0.42f, b.width - numW - rowH * 0.4f, rowH * 0.8f)
            for ((i, item) in items.withIndex()) {
                val top = b.top + headH + i * rowH
                val row = Box(b.left, top, b.right, top + rowH)
                val t0 = Kit.itemAt(cue, i, p.at + 0.1f, 0.22f)
                val n = Kit.digits("${i + 1}", p.rtl)
                val nf = Kit.fit(fit, n, Kit.numberVoice(look, p.typo), rowH * 0.86f, numW, rowH)
                val numX = Kit.fromStart(Kit.startX(row, p.rtl, 0f), numW, p.rtl)
                val accent = ground?.let { Kit.tint(look, if (i == 0) look.accent else look.ink, it) } ?: if (i == 0) look.accent else look.ink
                val rule = Kit.line(
                    Kit.startX(row, p.rtl, numW + rowH * 0.2f), row.bottom - 2f, Kit.endX(row, p.rtl, 0f), row.bottom - 2f,
                    (ground?.let { Kit.quiet(look, it) } ?: look.ink).copy(alpha = 0.35f), 2f, t0, name = "rank-rule",
                )
                val ff = names[i]
                val nameX = Kit.fromStart(Kit.startX(row, p.rtl, numW + rowH * 0.3f), ff.width, p.rtl)
                val parts = listOf(
                    rule,
                    Kit.words(n, nf, accent, numX, row.centerY, t0, ground, p.rtl, Kit.Reveal.Pop, name = "rank-numeral"),
                    Kit.words(item, ff, ground?.let { Kit.inkOn(look, it) } ?: look.ink, nameX, row.centerY, t0 + 0.1f, ground, p.rtl, name = "rank-name"),
                )
                nodes += Kit.item(p.move, row, t0, p.dir, parts, "rank-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, items.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.1f, 0.22f), SfxKind.Tick, 0.4f) })
        }
    }

    // ------------------------------------------------------------------ timeline

    /**
     * Events along time: items "۲۰۱۹: شروع" (a date before the colon) or plain steps. A rail draws
     * on and each event lands on it as it is said.
     */
    val Timeline = object : Family("timeline", listOf(Move.Wipe, Move.Rise, Move.Unfold)) {
        override val minHold = 3.2f
        override val preferredHeight = 0.36f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val items = Kit.items(cue, MAX).ifEmpty { return Built(emptyList()) }
            val events = items.map { it.split(':', '：').let { parts -> if (parts.size >= 2) parts[0].trim() to parts.drop(1).joinToString(":").trim() else null to it } }
            return when (p.layout) {
                1 -> track(cue, look, fit, p, events)
                2 -> stairs(cue, look, fit, p, events)
                else -> rail(cue, look, fit, p, events)
            }
        }

        /** A vertical rail at the reading start; dates and events beside each stop. */
        private fun rail(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, events: List<Pair<String?, String>>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val ink = ground?.let { Kit.inkOn(look, it) } ?: look.ink
            val stepH = b.height / events.size
            val railX = Kit.startX(b, p.rtl, stepH * 0.35f)
            val nodes = mutableListOf<Node>(Kit.line(railX, b.top + stepH * 0.5f, railX, b.bottom - stepH * 0.5f, ink.copy(alpha = 0.4f), 4f, p.at, 0.4f + events.size * 0.25f, "rail"))
            val dated = events.any { it.first != null }
            val texts = Kit.uniform(fit, events.map { it.second }, Kit.titleVoice(look, p.typo), stepH * (if (dated) 0.3f else 0.36f), b.width - minOf(stepH * 0.32f, 44f) * 3f, stepH * 0.5f)
            for ((i, ev) in events.withIndex()) {
                val cy = b.top + stepH * (i + 0.5f)
                val t0 = Kit.itemAt(cue, i, p.at + 0.2f, 0.28f)
                val d = minOf(stepH * 0.32f, 44f)
                val parts = mutableListOf<Node>(
                    Kit.dot(railX, cy, d * 1.8f, look.accent.copy(alpha = 0.25f), "stop-halo"),
                    Kit.dot(railX, cy, d, look.accent, "stop"),
                )
                val textEdge = railX - p.dir * (-d * 1.4f)
                var y = cy
                ev.first?.let { date ->
                    val df = Kit.fit(fit, Kit.digits(date, p.rtl), Kit.labelVoice(look, p.typo), stepH * 0.24f, b.width * 0.6f, stepH * 0.35f)
                    y = cy + df.height * 0.45f
                    parts += Kit.words(
                        Kit.digits(date, p.rtl), df, ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent, Kit.fromStart(textEdge, df.width, p.rtl),
                        cy - df.height * 0.55f, t0, ground, p.rtl, Kit.Reveal.Fade, name = "event-date",
                    )
                }
                val ef = texts[i]
                parts += Kit.words(ev.second, ef, ink, Kit.fromStart(textEdge, ef.width, p.rtl), y, t0 + 0.08f, ground, p.rtl, name = "event")
                nodes += Kit.item(p.move, Box(b.left, cy - stepH / 2f, b.right, cy + stepH / 2f), t0, p.dir, parts, "event-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, events.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.2f, 0.28f), SfxKind.Tick, 0.45f) })
        }

        /** A horizontal track in reading order; events alternate above and below it. */
        private fun track(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, events: List<Pair<String?, String>>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val ink = ground?.let { Kit.inkOn(look, it) } ?: look.ink
            val step = b.width / events.size
            val y = b.centerY
            val x0 = Kit.startX(b, p.rtl, step * 0.5f)
            val x1 = Kit.endX(b, p.rtl, step * 0.5f)
            val nodes = mutableListOf<Node>(Kit.line(x0, y, x1, y, ink.copy(alpha = 0.4f), 4f, p.at, 0.5f + events.size * 0.2f, "track"))
            val half = b.height / 2f
            val texts = Kit.uniform(fit, events.map { it.second }, Kit.titleVoice(look, p.typo), half * 0.2f, step * 0.94f, half * 0.42f, lines = 2)
            for ((i, ev) in events.withIndex()) {
                val cx = x0 + p.dir * step * i
                val up = i % 2 == 0
                val t0 = Kit.itemAt(cue, i, p.at + 0.25f, 0.28f)
                val d = minOf(step * 0.16f, 40f)
                val stem = half * 0.28f
                val parts = mutableListOf<Node>(
                    Kit.line(cx, y, cx, if (up) y - stem else y + stem, look.accent, 3f, t0, 0.25f, "stem"),
                    Kit.dot(cx, y, d, look.accent, "stop"),
                )
                val ef = texts[i]
                val df = ev.first?.let { Kit.fit(fit, Kit.digits(it, p.rtl), Kit.labelVoice(look, p.typo), half * 0.15f, step * 0.9f, half * 0.2f) }
                val blockH = ef.height + (df?.height ?: 0f)
                val top = if (up) y - stem - blockH - 8f else y + stem + 8f
                df?.let {
                    val color = ground?.let { g -> Kit.tint(look, look.accent, g) } ?: look.accent
                    parts += Kit.words(Kit.digits(ev.first!!, p.rtl), it, color, cx, top + it.height / 2f, t0, ground, p.rtl, Kit.Reveal.Fade, name = "event-date")
                }
                parts += Kit.words(ev.second, ef, ink, cx, top + (df?.height ?: 0f) + ef.height / 2f, t0 + 0.1f, ground, p.rtl, name = "event")
                nodes += Kit.item(p.move, Box(cx - step / 2f, b.top, cx + step / 2f, b.bottom), t0, p.dir, parts, "event-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, events.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.25f, 0.28f), SfxKind.Tick, 0.45f) })
        }

        /** Cards stepping down in reading order, joined by a thin path. */
        private fun stairs(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, events: List<Pair<String?, String>>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val n = events.size
            val cardH = b.height / n * 0.82f
            val cardW = b.width * (if (n <= 2) 0.7f else 0.62f)
            val stepX = if (n > 1) (b.width - cardW) / (n - 1) else 0f
            val stepY = if (n > 1) (b.height - cardH) / (n - 1) else 0f
            val nodes = mutableListOf<Node>()
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val tagW0 = cardH * 0.9f
            val texts = Kit.uniform(fit, events.map { it.second }, Kit.titleVoice(look, p.typo), cardH * 0.32f, cardW - tagW0 - cardH * 0.6f, cardH * 0.8f, lines = 2)
            val centres = events.indices.map { i -> Kit.fromStart(Kit.startX(b, p.rtl, i * stepX), cardW, p.rtl) to b.top + cardH / 2f + i * stepY }
            // Short joins from each card's reading-start foot down to the next card.
            for (i in 0 until n - 1) {
                val x = Kit.startX(Box(centres[i + 1].first - cardW / 2f, 0f, centres[i + 1].first + cardW / 2f, 0f), p.rtl, cardH * 0.4f)
                nodes += Kit.line(x, centres[i].second + cardH / 2f, x, centres[i + 1].second - cardH / 2f, look.accent, 4f, Kit.itemAt(cue, i + 1, p.at + 0.15f, 0.3f) - 0.15f, 0.2f, "stairs-join")
            }
            for ((i, ev) in events.withIndex()) {
                val (cx, cy) = centres[i]
                val t0 = Kit.itemAt(cue, i, p.at + 0.15f, 0.3f)
                val card = Box(cx - cardW / 2f, cy - cardH / 2f, cx + cardW / 2f, cy + cardH / 2f)
                val parts = Kit.panel(look, cue, s, cx, cy, cardW, cardH, cardH * 0.24f, "step-card").toMutableList()
                val tagS = Kit.surface(look, cue, Kit.Tone.Accent)
                val tag = ev.first?.let { Kit.digits(it, p.rtl) } ?: Kit.digits("${i + 1}", p.rtl)
                val tf = Kit.fit(fit, tag, Kit.labelVoice(look, p.typo).copy(weight = 800), cardH * 0.3f, cardW * 0.34f, cardH * 0.5f)
                val tagW = tf.width + cardH * 0.36f
                val tagX = Kit.fromStart(Kit.startX(card, p.rtl, cardH * 0.18f), tagW, p.rtl)
                parts += Kit.rect(tagX, cy, tagW, tf.height + cardH * 0.16f, tagS.ground, cardH * 0.16f * look.roundness, "step-tag")
                parts += Kit.words(tag, tf, Kit.inkOn(look, tagS.ground), tagX, cy, t0 + 0.1f, tagS.ground, p.rtl, Kit.Reveal.Fade, name = "step-tag-text")
                val ef = texts[i]
                parts += Kit.words(ev.second, ef, ink, Kit.fromStart(Kit.startX(card, p.rtl, tagW + cardH * 0.36f), ef.width, p.rtl), cy, t0 + 0.12f, s.ground, p.rtl, name = "event")
                nodes += Kit.item(p.move, card, t0, p.dir, parts, "event-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, events.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.15f, 0.3f), SfxKind.Pop, 0.4f) })
        }
    }

    // ------------------------------------------------------------------ steps

    /** How-to steps, numbered: connected discs, a chevron strip, or overlapping step cards. */
    val Steps = object : Family("steps", listOf(Move.Rise, Move.Slide, Move.Pop)) {
        override val minHold = 3f
        override val preferredHeight = 0.34f

        override fun layoutWeight(look: Look, cue: Cue, layout: Int): Float = if (layout == 1 && Kit.items(cue, MAX).size > 4) 0f else 1f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val items = Kit.items(cue, MAX).ifEmpty { return Built(emptyList()) }
            return when (if (p.layout == 1 && items.size > 4) 0 else p.layout) {
                1 -> chevrons(cue, look, fit, p, items)
                2 -> cards(cue, look, fit, p, items)
                else -> discs(cue, look, fit, p, items)
            }
        }

        private fun discs(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val ink = ground?.let { Kit.inkOn(look, it) } ?: look.ink
            val rowH = b.height / items.size
            val d = minOf(rowH * 0.66f, 110f)
            val cx = Kit.startX(b, p.rtl, d / 2f)
            val nodes = mutableListOf<Node>(Kit.line(cx, b.top + rowH / 2f, cx, b.bottom - rowH / 2f, look.accent.copy(alpha = 0.5f), 4f, p.at, 0.4f + items.size * 0.25f, "steps-rail"))
            val accent = Kit.surface(look, cue, Kit.Tone.Accent)
            val texts = Kit.uniform(fit, items, Kit.titleVoice(look, p.typo), rowH * 0.36f, b.width - d * 1.5f, rowH * 0.85f, lines = 2)
            for ((i, item) in items.withIndex()) {
                val cy = b.top + rowH * (i + 0.5f)
                val t0 = Kit.itemAt(cue, i, p.at + 0.15f, 0.3f)
                val n = Kit.digits("${i + 1}", p.rtl)
                val nf = Kit.fit(fit, n, Kit.numberVoice(look, p.typo), d * 0.52f, d * 0.7f, d * 0.7f)
                val tf = texts[i]
                val parts = listOf(
                    Kit.dot(cx, cy, d, accent.ground, "step-disc"),
                    Kit.words(n, nf, Kit.inkOn(look, accent.ground), cx, cy, t0 + 0.08f, accent.ground, p.rtl, Kit.Reveal.Pop, name = "step-number"),
                    Kit.words(item, tf, ink, Kit.fromStart(cx - p.dir * (-d * 0.85f), tf.width, p.rtl), cy, t0 + 0.12f, ground, p.rtl, name = "step"),
                )
                nodes += Kit.item(p.move, Box(b.left, cy - rowH / 2f, b.right, cy + rowH / 2f), t0, p.dir, parts, "step-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, items.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.15f, 0.3f), SfxKind.Click, 0.45f) })
        }

        /** A strip of arrow-shaped steps in reading order; the current one in the accent. */
        private fun chevrons(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.3f))
            val n = items.size
            val w = b.width / n
            val texts = Kit.uniform(fit, items, Kit.titleVoice(look, p.typo), b.height * 0.17f, w * 0.7f, b.height * 0.46f, lines = 3)
            val nodes = mutableListOf<Node>()
            for ((i, item) in items.withIndex()) {
                val cx = Kit.fromStart(Kit.startX(b, p.rtl, i * w), w, p.rtl)
                val t0 = Kit.itemAt(cue, i, p.at + 0.1f, 0.3f)
                val s = Kit.surface(look, cue, if (i == n - 1) Kit.Tone.Accent else Kit.Tone.Card)
                val shape = ShapeNode(
                    ShapeSpec.Path(chevron(i == 0), 100f, w * 1.02f), fill = s.fill, stroke = s.stroke,
                    transform = Transform(x = cx.anim, y = b.centerY.anim, scaleY = (b.height / (w * 1.02f)).anim, scaleX = (if (p.rtl) -1f else 1f).anim), name = "chevron",
                )
                val n1 = Kit.digits("${i + 1}", p.rtl)
                val ink = Kit.inkOn(look, s.ground)
                val nf = Kit.fit(fit, n1, Kit.numberVoice(look, p.typo), b.height * 0.26f, w * 0.5f, b.height * 0.3f)
                val tf = texts[i]
                val parts = listOf(
                    shape,
                    Kit.words(
                        n1, nf, Kit.tint(look, if (i == n - 1) ink else look.accent, s.ground), cx, b.centerY - tf.height / 2f - nf.height * 0.2f, t0 + 0.1f,
                        s.ground, p.rtl, Kit.Reveal.Pop, name = "chevron-number",
                    ),
                    Kit.words(item, tf, ink, cx, b.centerY + nf.height * 0.45f, t0 + 0.15f, s.ground, p.rtl, name = "chevron-text"),
                )
                nodes += Kit.item(p.move, Box(cx - w / 2f, b.top, cx + w / 2f, b.bottom), t0, p.dir, parts, "chevron-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, items.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.1f, 0.3f), SfxKind.Swish, 0.4f) })
        }

        /** An arrow-shaped step pointing right (mirrored for right-to-left); the first one has a flat back. */
    private fun chevron(first: Boolean): String = if (first) "M0 0H86L100 50L86 100H0Z" else "M0 0H86L100 50L86 100H0L14 50Z"

    /** Step cards overlapping like a fanned deck, big numerals in the corner. */
        private fun cards(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val n = items.size
            val cardH = b.height / (n * 0.78f + 0.22f)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = mutableListOf<Node>()
            val numW = cardH * 0.92f * 0.5f
            val texts = Kit.uniform(fit, items, Kit.titleVoice(look, p.typo), cardH * 0.92f * 0.3f, b.width - numW - cardH * 0.92f * 0.9f, cardH * 0.92f * 0.8f, lines = 2)
            for ((i, item) in items.withIndex()) {
                val top = b.top + i * cardH * 0.78f
                val inset = (n - 1 - i) * b.width * 0.015f
                val card = Box(b.left + inset, top, b.right - inset, top + cardH * 0.92f)
                val t0 = Kit.itemAt(cue, i, p.at + 0.1f, 0.3f)
                val parts = Kit.panel(look, cue, s, card.centerX, card.centerY, card.width, card.height, cardH * 0.2f, "step-card").toMutableList()
                val num = Kit.digits(if (i + 1 < 10) "0${i + 1}" else "${i + 1}", p.rtl)
                val nf = Kit.fit(fit, num, Kit.numberVoice(look, p.typo), card.height * 0.5f, card.width * 0.22f, card.height * 0.7f)
                val numX = Kit.fromStart(Kit.startX(card, p.rtl, card.height * 0.3f), nf.width, p.rtl)
                parts += Kit.words(num, nf, Kit.tint(look, look.accent, s.ground), numX, card.centerY, t0 + 0.1f, s.ground, p.rtl, Kit.Reveal.Fade, name = "step-numeral")
                val tf = texts[i]
                parts += Kit.words(item, tf, ink, Kit.fromStart(Kit.startX(card, p.rtl, nf.width + card.height * 0.6f), tf.width, p.rtl), card.centerY, t0 + 0.15f, s.ground, p.rtl, name = "step")
                nodes += Kit.item(p.move, card, t0, p.dir, parts, "step-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, items.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.1f, 0.3f), SfxKind.Pop, 0.4f) })
        }
    }

    private const val MAX = 5

    val all: List<Recipe> = listOf(Ranking, Timeline, Steps)
}
