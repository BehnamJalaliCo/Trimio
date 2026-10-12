package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Two sides of a thing: a split-screen comparison (A vs B), pros and cons, and a before/after
 * wipe. The first side is always the one read first (the right on a Persian piece).
 */
object CompareFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    /** The two sides: items, else the text split on "|" or « vs » / «یا». */
    private fun sides(cue: Cue): Pair<String, String>? {
        val items = Kit.items(cue, 2)
        if (items.size == 2) return items[0] to items[1]
        val t = cue.text
        for (sep in listOf(" vs ", " VS ", " در برابر ", " یا ", " or ")) {
            val i = t.indexOf(sep)
            if (i > 0) return t.substring(0, i).trim() to t.substring(i + sep.length).trim()
        }
        return null
    }

    // ------------------------------------------------------------------ versus

    /**
     * A against B: two columns meeting at a «VS» disc, two halves stacked top and bottom, or a
     * scoreboard of their figures. Values ([Cue.points]) count up under their names.
     */
    val Versus = object : Family("versus", listOf(Move.Slide, Move.Pop, Move.Wipe)) {
        override val minHold = 2.8f
        override val preferredHeight = 0.3f

        override fun layoutWeight(look: Look, cue: Cue, layout: Int): Float = if (layout == 2 && (cue.points?.size ?: 0) < 2) 0f else 1f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val (a, b) = sides(cue) ?: return Built(emptyList())
            val layout = if (p.layout == 2 && (cue.points?.size ?: 0) < 2) 0 else p.layout
            return when (layout) {
                1 -> stacked(cue, look, fit, p, a, b)
                2 -> score(cue, look, fit, p, a, b)
                else -> columns(cue, look, fit, p, a, b)
            }
        }

        private fun vs(look: Look, cue: Cue, fit: Fitter, p: Kit.Plan, cx: Float, cy: Float, d: Float, at: Float): List<Node> {
            val s = Kit.surface(look, cue, Kit.Tone.Ink)
            val word = Kit.say(p.rtl, "یا", "VS")
            val f = Kit.fit(fit, word, look.headline, d * 0.42f, d * 0.72f, d * 0.6f)
            val disc = ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = s.fill, stroke = Stroke(look.accent.fill(), d * 0.05f),
                transform = Transform(x = cx.anim, y = cy.anim, scale = Anim.tween(0f, 1f, at, at + 0.5f, Easing.Spring(0.45f, 2.4f)), rotation = Anim.tween(-90f, 0f, at, at + 0.6f, Easing.ExpoOut)),
                name = "vs-disc",
            )
            return listOf(disc, Kit.words(word, f, Kit.inkOn(look, s.ground), cx, cy, at + 0.15f, s.ground, p.rtl, Kit.Reveal.Pop, name = "vs"))
        }

        private fun side(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, i: Int, box: Box, tone: Kit.Tone, at: Float): List<Node> {
            val s = Kit.surface(look, cue, tone)
            val nodes = Kit.panel(look, cue, s, box.centerX, box.centerY, box.width, box.height, box.height * 0.14f, "side-$i").toMutableList()
            val ink = Kit.inkOn(look, s.ground)
            val v = cue.points?.getOrNull(i)
            val tf = Kit.fit(fit, text, Kit.titleVoice(look, p.typo), box.height * (if (v != null) 0.2f else 0.26f), box.width * 0.84f, box.height * 0.5f, lines = 2)
            val ty = if (v != null) box.centerY - box.height * 0.16f else box.centerY
            nodes += Kit.words(text, tf, ink, box.centerX, ty, at + 0.15f, s.ground, p.rtl, name = "side-name")
            if (v != null) {
                val nf = Kit.fit(fit, cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix, Kit.numberVoice(look, p.typo), box.height * 0.26f, box.width * 0.8f, box.height * 0.32f)
                nodes += Kit.number(
                    v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, ink, box.centerX, box.centerY + box.height * 0.2f,
                    at + 0.25f, 0.9f, s.ground, p.rtl,
                )
            }
            return nodes
        }

        private fun columns(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, a: String, b: String): Built {
            val box = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.62f))
            val gap = box.width * 0.04f
            val w = (box.width - gap) / 2f
            // The first side on the reading start.
            val first = if (p.rtl) Box(box.right - w, box.top, box.right, box.bottom) else Box(box.left, box.top, box.left + w, box.bottom)
            val second = if (p.rtl) Box(box.left, box.top, box.left + w, box.bottom) else Box(box.right - w, box.top, box.right, box.bottom)
            val t1 = p.at + 0.05f
            val t2 = Kit.itemAt(cue, 1, p.at + 0.35f, 0.3f)
            val nodes = listOf(
                Kit.item(p.move, first, t1, p.dir, side(cue, look, fit, p, a, 0, first, Kit.Tone.Card, t1), "versus-a"),
                Kit.item(p.move, second, t2, -p.dir, side(cue, look, fit, p, b, 1, second, Kit.Tone.Accent, t2), "versus-b"),
            ) + vs(look, cue, fit, p, box.centerX, box.centerY, minOf(box.height * 0.36f, box.width * 0.2f), t2 + 0.2f)
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(t2 + 0.2f, SfxKind.Hit, 0.6f)), listOf(Craft.punch(t2 + 0.25f, 0.02f)))
        }

        private fun stacked(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, a: String, b: String): Built {
            val box = Kit.box(cue, cue.width, cue.height)
            val gap = box.height * 0.05f
            val h = (box.height - gap) / 2f
            val top = Box(box.left, box.top, box.right, box.top + h)
            val bottom = Box(box.left, box.bottom - h, box.right, box.bottom)
            val t1 = p.at + 0.05f
            val t2 = Kit.itemAt(cue, 1, p.at + 0.35f, 0.3f)
            val nodes = listOf(
                Kit.item(p.move, top, t1, p.dir, side(cue, look, fit, p, a, 0, top, Kit.Tone.Card, t1), "versus-a"),
                Kit.item(p.move, bottom, t2, -p.dir, side(cue, look, fit, p, b, 1, bottom, Kit.Tone.Accent, t2), "versus-b"),
            ) + vs(look, cue, fit, p, box.centerX, box.centerY, minOf(box.height * 0.3f, box.width * 0.16f), t2 + 0.2f)
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(t2 + 0.2f, SfxKind.Hit, 0.6f)))
        }

        /** Names at either end, two bars meeting in the middle sized by their figures. */
        private fun score(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, a: String, b: String): Built {
            val box = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.5f))
            val ground = free(look, cue)
            val ink = ground?.let { Kit.inkOn(look, it) } ?: look.ink
            val va = cue.points!![0].coerceAtLeast(0f)
            val vb = cue.points!![1].coerceAtLeast(0f)
            val share = if (va + vb <= 0f) 0.5f else (va / (va + vb)).coerceIn(0.12f, 0.88f)
            val barH = box.height * 0.26f
            val barY = box.centerY + box.height * 0.18f
            val at = p.at + 0.3f
            val nodes = mutableListOf<Node>()
            val startEdge = Kit.startX(box, p.rtl, 0f)
            val endEdge = Kit.endX(box, p.rtl, 0f)
            val split = startEdge + p.dir * box.width * share
            fun bar(from: Float, to: Float, color: Color, name: String): ShapeNode {
                val left = minOf(from, to)
                val w = kotlin.math.abs(to - from)
                val grows = if ((from < to)) 0f else 1f
                return ShapeNode(
                    ShapeSpec.Rect(Anim.tween(0f, w, at, at + 0.9f, Easing.ExpoOut), barH.anim, (barH * 0.5f * look.roundness).anim), fill = color.fill(),
                    transform = Transform(x = (left + w * grows).anim, y = barY.anim, anchorX = grows), name = name,
                )
            }
            nodes += bar(startEdge, split - p.dir * 3f, look.accent, "versus-bar-a")
            nodes += bar(endEdge, split + p.dir * 3f, look.hot, "versus-bar-b")
            for ((i, text) in listOf(a, b).withIndex()) {
                val edge = if (i == 0) startEdge else endEdge
                val tf = Kit.fit(fit, text, Kit.titleVoice(look, p.typo), box.height * 0.2f, box.width * 0.46f, box.height * 0.3f)
                val x = if (i == 0) Kit.fromStart(edge, tf.width, p.rtl) else Kit.fromEnd(edge, tf.width, p.rtl)
                nodes += Kit.words(text, tf, ink, x, box.top + tf.height / 2f, p.at + i * 0.15f, ground, p.rtl, name = "versus-name")
                val v = if (i == 0) va else vb
                val bg = if (i == 0) look.accent else look.hot
                val sample = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix
                val nf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), barH * 0.62f, box.width * 0.3f, barH * 0.8f)
                val nx = if (i == 0) Kit.fromStart(edge, nf.width, p.rtl) + p.dir * barH * 0.4f else Kit.fromEnd(edge, nf.width, p.rtl) - p.dir * barH * 0.4f
                nodes += Kit.number(v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, Kit.inkOn(look, bg), nx, barY, at + 0.1f, 0.9f, bg, p.rtl)
            }
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(at, SfxKind.Riser, 0.4f), Sfx(at + 0.9f, SfxKind.Pop, 0.6f)))
        }
    }

    // ------------------------------------------------------------------ pros and cons

    /**
     * Good and bad points: items starting with "-" (or "−", "!") are cons, the rest pros. Two
     * columns under their headings, one mixed list of ticks and crosses, or two stacked cards.
     */
    val ProsCons = object : Family("pros-cons", listOf(Move.Rise, Move.Slide, Move.Pop)) {
        override val minHold = 3f
        override val preferredHeight = 0.34f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val raw = Kit.items(cue, MAX_POINTS).ifEmpty { return Built(emptyList()) }
            val points = raw.map { (it.startsWith("-") || it.startsWith("−") || it.startsWith("!")) to it.trimStart('-', '−', '!', '+', ' ') }
            return when (p.layout) {
                1 -> mixed(cue, look, fit, p, points)
                2 -> stacked(cue, look, fit, p, points)
                else -> columns(cue, look, fit, p, points)
            }
        }

        private fun mark(look: Look, con: Boolean, cx: Float, cy: Float, d: Float, at: Float): List<Node> {
            val color = if (con) look.negative else look.positive
            val ground = color
            return listOf(Kit.dot(cx, cy, d, ground, "point-disc"), Kit.icon(if (con) "close" else "check", cx, cy, d * 0.56f, Look.textOn(ground), at, d * 0.07f))
        }

        private fun heading(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, con: Boolean, cx: Float, top: Float, w: Float, size: Float, ground: Color?): Pair<Node, Float> {
            val word = if (con) Kit.say(p.rtl, "معایب", "Cons") else Kit.say(p.rtl, "مزایا", "Pros")
            val f = Kit.fit(fit, word, Kit.labelVoice(look, p.typo).copy(weight = 800), size, w, size * 1.5f)
            val color = ground?.let { Kit.tint(look, if (con) look.negative else look.positive, it) } ?: if (con) look.negative else look.positive
            return Kit.words(word, f, color, cx, top + f.height / 2f, p.at, ground, p.rtl, Kit.Reveal.Fade, name = "point-heading") to f.height * 1.4f
        }

        /** The size every point is set in: the largest all of them fit at in a row of [w] × [h]. */
        private fun size(fit: Fitter, look: Look, p: Kit.Plan, points: List<Pair<Boolean, String>>, w: Float, h: Float): Float {
            val d = minOf(h * 0.62f, 64f)
            return Kit.uniform(fit, points.map { it.second }, Kit.titleVoice(look, p.typo), h * 0.42f, w - d * 1.5f, h * 0.9f, lines = 2).minOf { it.type.size }
        }

        private fun row(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, point: Pair<Boolean, String>, box: Box, ground: Color?, at: Float, name: String, size: Float): Group {
            val d = minOf(box.height * 0.62f, 64f)
            val ink = ground?.let { Kit.inkOn(look, it) } ?: look.ink
            val mx = Kit.startX(box, p.rtl, d / 2f)
            val tf = Kit.fit(fit, point.second, Kit.titleVoice(look, p.typo), size, box.width - d * 1.5f, box.height * 0.9f, lines = 2)
            val parts = mark(look, point.first, mx, box.centerY, d, at + 0.08f) +
                Kit.words(point.second, tf, ink, Kit.fromStart(mx - p.dir * (-d * 0.8f), tf.width, p.rtl), box.centerY, at + 0.1f, ground, p.rtl, name = "point")
            return Kit.item(p.move, box, at, p.dir, parts, name)
        }

        private fun columns(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, points: List<Pair<Boolean, String>>): Built {
            val box = Kit.box(cue, cue.width, cue.height)
            val gap = box.width * 0.04f
            val w = (box.width - gap) / 2f
            val s = Kit.surface(look, cue)
            val nodes = mutableListOf<Node>()
            var k = 0
            for (con in listOf(false, true)) {
                val col = if (con == p.rtl) Box(box.left, box.top, box.left + w, box.bottom) else Box(box.right - w, box.top, box.right, box.bottom)
                nodes += Kit.panel(look, cue, s, col.centerX, col.centerY, col.width, col.height, w * 0.08f, "column")
                val (head, headH) = heading(cue, look, fit, p, con, col.centerX, col.top + w * 0.06f, w * 0.8f, box.height * 0.075f, s.ground)
                nodes += head
                val mine = points.filter { it.first == con }
                val most = maxOf(points.count { it.first }, points.count { !it.first }).coerceAtLeast(1)
                val rowH = ((col.height - headH - w * 0.12f) / most).coerceAtMost(col.height * 0.3f)
                val sz = size(fit, look, p, points, w * 0.88f, rowH)
                for ((i, pt) in mine.withIndex()) {
                    val top = col.top + w * 0.06f + headH + i * rowH
                    nodes += row(cue, look, fit, p, pt, Box(col.left + w * 0.06f, top, col.right - w * 0.06f, top + rowH), s.ground, Kit.itemAt(cue, k, p.at + 0.2f, 0.25f), "point-${k++}", sz)
                }
            }
            return Kit.built(cue, look, p, box, nodes, name, points.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.2f, 0.25f), SfxKind.Click, 0.4f) })
        }

        private fun mixed(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, points: List<Pair<Boolean, String>>): Built {
            val box = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val rowH = box.height / points.size
            val sz = size(fit, look, p, points, box.width, rowH)
            val nodes = points.mapIndexed { i, pt ->
                val top = box.top + i * rowH
                row(cue, look, fit, p, pt, Box(box.left, top, box.right, top + rowH), ground, Kit.itemAt(cue, i, p.at + 0.1f, 0.25f), "point-$i", sz)
            }
            return Kit.built(cue, look, p, box, nodes, name, points.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.1f, 0.25f), SfxKind.Click, 0.4f) })
        }

        private fun stacked(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, points: List<Pair<Boolean, String>>): Built {
            val box = Kit.box(cue, cue.width, cue.height)
            val groups = listOf(false, true).map { con -> con to points.filter { it.first == con } }.filter { it.second.isNotEmpty() }
            val total = points.size + groups.size * 0.8f
            val unit = box.height / total
            val nodes = mutableListOf<Node>()
            var y = box.top
            var k = 0
            val sz = size(fit, look, p, points, box.width - unit * 0.8f, unit * 0.92f)
            for ((con, mine) in groups) {
                val h = unit * (mine.size + 0.8f) - unit * 0.15f
                val card = Box(box.left, y, box.right, y + h)
                val s = Kit.surface(look, cue, if (con) Kit.Tone.Card else Kit.Tone.Raised)
                val edge = Kit.rect(Kit.startX(card, p.rtl, 4f), card.centerY, 8f, h, if (con) look.negative else look.positive, 4f * look.roundness, "point-edge")
                nodes += Kit.panel(look, cue, s, card.centerX, card.centerY, card.width, card.height, unit * 0.3f, "point-card") + edge
                val (head, headH) = heading(
                    cue, look, fit, p, con, Kit.fromStart(Kit.startX(card, p.rtl, unit * 0.5f), card.width * 0.3f, p.rtl), card.top + unit * 0.12f,
                    card.width * 0.3f, unit * 0.36f, s.ground,
                )
                nodes += head
                for ((i, pt) in mine.withIndex()) {
                    val top = card.top + unit * 0.12f + headH + i * unit
                    nodes += row(
                        cue, look, fit, p, pt, Box(card.left + unit * 0.4f, top, card.right - unit * 0.4f, top + unit * 0.92f), s.ground,
                        Kit.itemAt(cue, k, p.at + 0.2f, 0.25f), "point-${k++}", sz,
                    )
                }
                y += h + unit * 0.15f
            }
            return Kit.built(cue, look, p, box, nodes, name, points.indices.map { Sfx(Kit.itemAt(cue, it, p.at + 0.2f, 0.25f), SfxKind.Click, 0.4f) })
        }
    }

    // ------------------------------------------------------------------ before / after

    /**
     * Before and after ([Cue.items]: the two states; [Cue.from] → [Cue.value] their figures, when
     * said): the "after" side is wiped over the "before" by a moving divider, the halves are stacked
     * and wiped top to bottom, or two panels are joined by an arrow that draws on.
     */
    val BeforeAfter = object : Family("before-after", listOf(Move.Wipe, Move.Rise, Move.Flip)) {
        override val minHold = 3f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val items = Kit.items(cue, 2)
            val before = items.getOrNull(0) ?: Kit.say(p.rtl, "قبل", "Before")
            val after = items.getOrNull(1) ?: Kit.say(p.rtl, "بعد", "After")
            return when (p.layout) {
                1 -> wipe(cue, look, fit, p, before, after, vertical = true)
                2 -> arrow(cue, look, fit, p, before, after)
                else -> wipe(cue, look, fit, p, before, after, vertical = false)
            }
        }

        /**
         * One state in [region] (a panel, or its half of a split card): its tag («قبل» / «بعد») in
         * the reading-start corner, its figure, and its words under it, stacked and centred.
         */
        private fun state(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, after: Boolean, region: Box, ground: Color, at: Float): List<Node> {
            val nodes = mutableListOf<Node>()
            val ink = Kit.inkOn(look, ground)
            val pad = minOf(region.width, region.height) * 0.1f
            val tag = if (after) Kit.say(p.rtl, "بعد", "AFTER") else Kit.say(p.rtl, "قبل", "BEFORE")
            val tf = Kit.fit(fit, tag, Kit.labelVoice(look, p.typo).copy(weight = 800), region.height * 0.09f, region.width * 0.6f, region.height * 0.14f)
            nodes += Kit.words(tag, tf, ink, Kit.fromStart(Kit.startX(region, p.rtl, pad), tf.width, p.rtl), region.top + pad + tf.height / 2f, at, ground, p.rtl, Kit.Reveal.Fade, name = "state-tag")
            val v = if (after) cue.value else cue.from
            val top = region.top + pad + tf.height * 1.3f
            val room = region.bottom - pad - top
            val nf = v?.let { Kit.fit(fit, cue.prefix + Kit.format(it, cue.decimals, false) + cue.suffix, Kit.numberVoice(look, p.typo), room * 0.45f, region.width - pad * 2f, room * 0.5f) }
            val wf = Kit.fit(fit, text, Kit.titleVoice(look, p.typo), room * (if (v != null) 0.2f else 0.3f), region.width - pad * 2f, room - (nf?.height ?: 0f), lines = 2)
            val total = (nf?.height ?: 0f) + wf.height
            var y = top + (room - total) / 2f
            if (v != null && nf != null) {
                nodes += Kit.number(
                    v, if (after) cue.from ?: 0f else v, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), nf.type.size, ink, region.centerX,
                    y + nf.height / 2f, at + 0.15f, 0.8f, ground, p.rtl,
                )
                y += nf.height
            }
            nodes += Kit.words(text, wf, ink, region.centerX, y + wf.height / 2f, at + 0.1f, ground, p.rtl, name = "state")
            return nodes
        }

        /** A panel of [tone] filling [box] (no glass: the wipe masks it). */
        private fun panelOf(look: Look, cue: Cue, box: Box, tone: Kit.Tone, name: String): Pair<Node, Color> {
            val s = Kit.surface(look, cue, tone)
            val ground = if (tone == Kit.Tone.Card) Kit.body(look, cue).let { if (Kit.light(look.canvas) || cue.overMedia) it else Kit.over(look.card, look.canvas) } else s.ground
            val fill = if (tone == Kit.Tone.Card && !Kit.light(look.canvas) && !cue.overMedia) androidx.compose.ui.graphics.lerp(look.canvas, look.ink, 0.08f) else s.ground
            return ShapeNode(
                ShapeSpec.Rect(box.width.anim, box.height.anim, (box.height * 0.1f * look.roundness).anim), fill = fill.fill(), stroke = s.stroke,
                transform = Transform(x = box.centerX.anim, y = box.centerY.anim), name = name,
            ) to
                (if (tone == Kit.Tone.Card && !Kit.light(look.canvas) && !cue.overMedia) fill else ground)
        }

        /**
         * A comparison slider: the card shows "before" whole, then a divider sweeps in from the far
         * edge and settles in the middle — "after" on the reading-end half (or the lower half),
         * "before" on the other. Each state is laid out in its own half.
         */
        private fun wipe(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, before: String, after: String, vertical: Boolean): Built {
            val box = Kit.box(cue, cue.width, minOf(cue.height, cue.width * (if (vertical) 0.7f else 0.5f)))
            val sweep = Kit.itemAt(cue, 1, p.at + 0.8f, 0f)
            val span = 0.9f
            // The halves: the first (before) at the reading start, or on top.
            val first = when {
                vertical -> Box(box.left, box.top, box.right, box.centerY)
                p.rtl -> Box(box.centerX, box.top, box.right, box.bottom)
                else -> Box(box.left, box.top, box.centerX, box.bottom)
            }
            val second = when {
                vertical -> Box(box.left, box.centerY, box.right, box.bottom)
                p.rtl -> Box(box.left, box.top, box.centerX, box.bottom)
                else -> Box(box.centerX, box.top, box.right, box.bottom)
            }
            val (beforePanel, beforeGround) = panelOf(look, cue, box, Kit.Tone.Card, "before-panel")
            val (afterPanel, afterGround) = panelOf(look, cue, box, Kit.Tone.Accent, "after-panel")
            val beforeNodes = listOf(beforePanel) + state(cue, look, fit, p, before, false, first, beforeGround, p.at + 0.1f)
            val afterNodes = listOf(afterPanel) + state(cue, look, fit, p, after, true, second, afterGround, sweep + 0.3f)
            // The divider's travel: from the far edge to the middle, where it rests.
            val far = if (vertical) box.bottom else Kit.endX(box, p.rtl, 0f)
            val mid = if (vertical) box.centerY else box.centerX
            val pos = Anim.tween(far, mid, sweep, sweep + span, Easing.ExpoOut)
            val reveal = if (vertical) {
                ShapeNode(
                    ShapeSpec.Rect(box.width.anim, Anim.tween(0f, box.bottom - mid, sweep, sweep + span, Easing.ExpoOut)), fill = Color.White.fill(),
                    transform = Transform(x = box.centerX.anim, y = box.bottom.anim, anchorY = 1f),
                )
            } else {
                ShapeNode(
                    ShapeSpec.Rect(Anim.tween(0f, box.width / 2f, sweep, sweep + span, Easing.ExpoOut), box.height.anim), fill = Color.White.fill(),
                    transform = Transform(x = far.anim, y = box.centerY.anim, anchorX = if (p.rtl) 0f else 1f),
                )
            }
            val wiped = Group(afterNodes, mask = Mask(reveal), name = "after")
            val nodes = beforeNodes + wiped + handle(look, box, vertical, pos, sweep)
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(sweep - 0.05f, SfxKind.Swish, 0.7f), Sfx(sweep + span * 0.6f, SfxKind.Pop, 0.5f)))
        }

        /** The divider: a bright rule with a round grip riding the wipe's edge, then resting. */
        private fun handle(look: Look, box: Box, vertical: Boolean, pos: Anim, sweep: Float): List<Node> {
            val color = if (Kit.light(look.canvas)) look.ink else Color.White
            val d = minOf(box.width, box.height) * 0.14f
            val show = Anim.tween(0f, 1f, sweep - 0.1f, sweep)
            val t = if (vertical) Transform(x = box.centerX.anim, y = pos, opacity = show) else Transform(x = pos, y = box.centerY.anim, opacity = show)
            val line = ShapeNode(ShapeSpec.Rect((if (vertical) box.width else 5f).anim, (if (vertical) 5f else box.height).anim), fill = color.fill(), transform = t, name = "divider")
            val grip = ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), fill = color.fill(), stroke = Stroke(look.accent.fill(), d * 0.08f), transform = t, name = "grip")
            val arrows = Kit.icon("code", 0f, 0f, d * 0.5f, look.accent, sweep + 0.2f)
            return listOf(line, grip, Group(listOf(arrows), transform = t.copy(anchorX = 0f, anchorY = 0f, rotation = (if (vertical) 90f else 0f).anim), name = "grip-arrows"))
        }

        /** Two panels in reading order with an arrow drawing on between them. */
        private fun arrow(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, before: String, after: String): Built {
            val box = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.5f))
            val gap = box.width * 0.14f
            val w = (box.width - gap) / 2f
            val first = if (p.rtl) Box(box.right - w, box.top, box.right, box.bottom) else Box(box.left, box.top, box.left + w, box.bottom)
            val second = if (p.rtl) Box(box.left, box.top, box.left + w, box.bottom) else Box(box.right - w, box.top, box.right, box.bottom)
            val t2 = Kit.itemAt(cue, 1, p.at + 0.6f, 0f)
            val ax0 = if (p.rtl) first.left - gap * 0.15f else first.right + gap * 0.15f
            val ax1 = if (p.rtl) second.right + gap * 0.15f else second.left - gap * 0.15f
            val ink = look.accent
            val arrowNodes = listOf(
                Kit.line(ax0, box.centerY, ax1, box.centerY, ink, 8f, t2 - 0.3f, 0.35f, "arrow-shaft"),
                Kit.line(ax1, box.centerY, ax1 - p.dir * gap * 0.25f, box.centerY - gap * 0.25f, ink, 8f, t2 + 0.02f, 0.18f, "arrow-head"),
                Kit.line(ax1, box.centerY, ax1 - p.dir * gap * 0.25f, box.centerY + gap * 0.25f, ink, 8f, t2 + 0.02f, 0.18f, "arrow-head"),
            )
            val (bp, bg) = panelOf(look, cue, first, Kit.Tone.Card, "before-panel")
            val (ap, ag) = panelOf(look, cue, second, Kit.Tone.Accent, "after-panel")
            val nodes = listOf(
                Kit.item(p.move, first, p.at, p.dir, listOf(bp) + state(cue, look, fit, p, before, false, first, bg, p.at + 0.05f), "before"),
                Kit.item(p.move, second, t2, p.dir, listOf(ap) + state(cue, look, fit, p, after, true, second, ag, t2 + 0.05f), "after"),
            ) + arrowNodes
            return Kit.built(cue, look, p, box, nodes, name, listOf(Sfx(t2 - 0.3f, SfxKind.Swish, 0.6f), Sfx(t2, SfxKind.Pop, 0.6f)))
        }
    }

    private const val MAX_POINTS = 6

    val all: List<Recipe> = listOf(Versus, ProsCons, BeforeAfter)
}
