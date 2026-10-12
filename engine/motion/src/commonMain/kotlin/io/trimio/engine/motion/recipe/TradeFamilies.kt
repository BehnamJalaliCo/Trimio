package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * The trader's desk (crypto and forex): a candlestick chart, a ticker tape, a wallet card and an
 * exchange-style order card. Prices and time run left to right as on every exchange, words read in
 * the piece's own direction; up is the look's positive colour, down its negative, always with an
 * arrow or sign as well (never colour alone).
 */
object TradeFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    private fun inkFree(look: Look, cue: Cue) = free(look, cue)?.let { Kit.inkOn(look, it) } ?: look.ink

    /**
     * A change as read: signed in English (+2.40%); in Persian the figure and «٪» only, its
     * direction carried by the arrow beside it (a sign does not survive right-to-left).
     */
    private fun pct(v: Float, rtl: Boolean): String {
        val n = Kit.format(kotlin.math.abs(v), if (kotlin.math.abs(v) < 10f) 2 else 1, false)
        return if (rtl) Kit.digits(n, true) + "٪" else (if (v >= 0f) "+" else "−") + n + "%"
    }

    /** A small up or down triangle at ([x], [y]). */
    private fun arrow(up: Boolean, x: Float, y: Float, size: Float, color: Color): Node =
        ShapeNode(ShapeSpec.Path(if (up) UP else DOWN, 10f, size), fill = color.fill(), transform = Transform(x = x.anim, y = y.anim), name = if (up) "up" else "down")

    // ------------------------------------------------------------------ candles

    /** One candle: open, high, low, close. */
    private class Candle(val o: Float, val h: Float, val l: Float, val c: Float)

    /** Candles from closes ([Cue.points]) or a seeded walk: each opens where the last closed, with wicks. */
    private fun candles(cue: Cue, n: Int): List<Candle> {
        val closes = cue.points?.takeIf { it.size >= 4 } ?: run {
            var k = cue.seed * 97 + 13
            var v = 100f
            List(n) {
                k = k * 1103515245 + 12345
                v += (((k ushr 9) and 0xFF) / 255f - 0.42f) * 6f
                v
            }
        }
        val range = (closes.max() - closes.min()).coerceAtLeast(1e-3f)
        var k = cue.seed * 31 + 5
        return closes.mapIndexed { i, c ->
            val o = closes.getOrElse(i - 1) { c - range * 0.05f }
            k = k * 1103515245 + 12345
            val wick = range * (0.03f + ((k ushr 10) and 0x3F) / 63f * 0.08f)
            Candle(o, maxOf(o, c) + wick, minOf(o, c) - wick * 0.8f, c)
        }
    }

    /**
     * Candles in [box], growing in time order from [at] (each a beat after the last), with an
     * optional volume strip under them. Returns the nodes and the last close's y.
     */
    private fun candleNodes(look: Look, cs: List<Candle>, box: Box, at: Float, gap: Float, volume: Boolean): Pair<List<Node>, Float> {
        val hi = cs.maxOf { it.h }
        val lo = cs.minOf { it.l }
        val volH = if (volume) box.height * 0.16f else 0f
        val chartH = box.height - volH - (if (volume) box.height * 0.04f else 0f)
        fun y(v: Float) = box.top + chartH * (1f - (v - lo) / (hi - lo).coerceAtLeast(1e-6f))
        val w = box.width / cs.size
        val nodes = mutableListOf<Node>()
        for ((i, c) in cs.withIndex()) {
            val x = box.left + w * (i + 0.5f)
            val up = c.c >= c.o
            val color = if (up) look.positive else look.negative
            val t0 = at + i * gap
            val top = y(maxOf(c.o, c.c))
            val bodyH = (y(minOf(c.o, c.c)) - top).coerceAtLeast(3f)
            val grow = Anim.tween(0f, 1f, t0, t0 + 0.35f, Easing.ExpoOut)
            nodes += ShapeNode(
                ShapeSpec.Rect(maxOf(2f, w * 0.08f).anim, (y(c.l) - y(c.h)).anim), fill = color.fill(),
                transform = Transform(x = x.anim, y = ((y(c.h) + y(c.l)) / 2f).anim, scaleY = grow), name = "wick",
            )
            nodes += ShapeNode(
                ShapeSpec.Rect((w * 0.62f).anim, bodyH.anim, (w * 0.06f).anim), fill = color.fill(),
                transform = Transform(x = x.anim, y = (top + bodyH / 2f).anim, scaleY = grow), name = "candle",
            )
            if (volume) {
                val vh = volH * (0.3f + 0.7f * kotlin.math.abs(c.c - c.o) / (hi - lo).coerceAtLeast(1e-6f) * 3f).coerceAtMost(1f)
                nodes += ShapeNode(
                    ShapeSpec.Rect((w * 0.62f).anim, vh.anim), fill = color.copy(alpha = 0.45f).fill(),
                    transform = Transform(x = x.anim, y = box.bottom.anim, anchorY = 1f, scaleY = grow), name = "volume",
                )
            }
        }
        return nodes to y(cs.last().c)
    }

    /**
     * A candlestick chart ([Cue.points] closes, [Cue.label] the pair, [Cue.value] the last price):
     * on a card with the last price tagged on a dashed line, full-bleed with volume and a big
     * watermark of the pair, or a compact pill (pair, mini candles, change).
     */
    val Candles = object : Family("candles", listOf(Move.Wipe, Move.Rise, Move.Pop)) {
        override val minHold = 3.2f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> full(cue, look, fit, p)
            2 -> pill(cue, look, fit, p)
            else -> card(cue, look, fit, p)
        }

        private fun pair(cue: Cue) = (cue.label ?: cue.text).ifBlank { "BTC/USDT" }

        private fun change(cs: List<Candle>) = (cs.last().c - cs.first().o) / cs.first().o.coerceAtLeast(1e-6f) * 100f

        /** The pair, its last price and a change chip across the top of [b]. */
        private fun header(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, b: Box, cs: List<Candle>, ground: Color, size: Float): List<Node> {
            val ink = Kit.inkOn(look, ground)
            val pf = Kit.fit(fit, pair(cue), Kit.labelVoice(look, Typo.Data).copy(weight = 800), size, b.width * 0.4f, size * 1.6f)
            val y = b.top + size
            val nodes = mutableListOf<Node>(Kit.words(pair(cue), pf, ink, b.left + size * 0.6f + pf.width / 2f, y, p.at + 0.1f, ground, false, Kit.Reveal.Fade, name = "candles-pair"))
            val ch = change(cs)
            val chip = if (ch >= 0f) look.positive else look.negative
            val text = pct(ch, p.rtl)
            val cf = Kit.fit(fit, text, Kit.numberVoice(look, Typo.Data), size * 0.9f, b.width * 0.3f, size * 1.4f)
            val tri = size * 0.6f
            val cw = cf.width + tri + size * 1.1f
            val cx = b.right - size * 0.6f - cw / 2f
            nodes += Kit.rect(cx, y, cw, cf.height + size * 0.4f, chip, (cf.height + size * 0.4f) / 2f * look.roundness, "change-chip")
            nodes += arrow(ch >= 0f, cx - cw / 2f + size * 0.45f + tri / 2f, y, tri, Look.textOn(chip))
            nodes += Kit.words(text, cf, Look.textOn(chip), cx + tri / 2f + size * 0.1f, y, p.at + 0.3f, chip, p.rtl, Kit.Reveal.Fade, name = "change")
            return nodes
        }

        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.66f))
            val s = Kit.surface(look, cue)
            val cs = candles(cue, CANDLES)
            val size = b.height * 0.07f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.08f, "candles-card").toMutableList()
            nodes += header(cue, look, fit, p, b, cs, s.ground, size)
            val tagW = b.width * 0.2f
            val area = Box(b.left + size * 0.6f, b.top + size * 2.4f, b.right - tagW - size * 0.4f, b.bottom - size * 0.8f)
            val gap = Craft.pace(cue.energy, 0.09f, 0.05f)
            val (bars, lastY) = candleNodes(look, cs, area, p.at + 0.3f, gap, volume = false)
            nodes += bars
            val done = p.at + 0.3f + cs.size * gap
            val up = cs.last().c >= cs.last().o
            val tag = if (up) look.positive else look.negative
            val dashes = Group(
                (0 until 24).map { k -> Kit.rect(area.left + (area.width + size * 0.4f) * (k + 0.5f) / 24f, lastY, (area.width / 24f) * 0.5f, 2f, tag.copy(alpha = 0.8f), 0f, "dash") },
                transform = Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, done, done + 0.2f)), name = "last-line",
            )
            nodes += dashes
            val v = cue.value ?: cs.last().c
            val vf = Kit.fit(fit, Kit.format(v, cue.decimals, false), Kit.numberVoice(look, Typo.Data), size * 0.95f, tagW * 0.9f, size * 1.5f)
            val tx = b.right - size * 0.3f - tagW / 2f
            nodes += Kit.rect(tx, lastY, tagW, vf.height + size * 0.4f, tag, size * 0.2f * look.roundness, "price-tag")
            nodes += Kit.number(v, cs.first().c, cue.decimals, cue.prefix, "", Kit.numberVoice(look, Typo.Data), vf.type.size, Look.textOn(tag), tx, lastY, p.at + 0.3f, cs.size * gap, tag, p.rtl)
            return Kit.built(cue, look, p, b, nodes, name, cs.indices.filter { it % 3 == 0 }.map { Sfx(p.at + 0.3f + it * gap, SfxKind.Tick, 0.25f) } + Sfx(done, SfxKind.Pop, 0.5f))
        }

        private fun full(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val ground = free(look, cue)
            val cs = candles(cue, CANDLES + 6)
            val nodes = mutableListOf<Node>()
            val mark = Kit.fit(fit, pair(cue).substringBefore('/'), look.headline, b.height * 0.42f, b.width * 0.9f, b.height * 0.5f)
            val faint = (ground?.let { Kit.inkOn(look, it) } ?: look.ink).copy(alpha = 0.08f)
            // A watermark, not words to read: never guarded, never held to the floor.
            val watermark = io.trimio.engine.motion.TextNode(
                pair(cue).substringBefore('/'), mark.type, faint.fill(), rtl = false,
                transform = Transform(x = b.centerX.anim, y = b.centerY.anim, opacity = Anim.tween(0f, 1f, p.at, p.at + 0.6f)), name = "candles-watermark",
            )
            nodes += Craft.onBody(watermark)
            for (k in 1..3) nodes += Kit.line(b.left, b.top + b.height * k / 4f, b.right, b.top + b.height * k / 4f, faint.copy(alpha = 0.18f), 2f, p.at, 0.5f, "grid")
            val gap = Craft.pace(cue.energy, 0.07f, 0.04f)
            nodes += candleNodes(look, cs, Box(b.left, b.top + b.height * 0.05f, b.right, b.bottom), p.at + 0.2f, gap, volume = true).first
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Riser, 0.4f)))
        }

        private fun pill(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.2f))
            val s = Kit.surface(look, cue)
            val cs = candles(cue, 10)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height / 2f, "candles-pill").toMutableList()
            val pf = Kit.fit(fit, pair(cue), Kit.labelVoice(look, Typo.Data).copy(weight = 800), b.height * 0.3f, b.width * 0.3f, b.height * 0.6f)
            nodes += Kit.words(pair(cue), pf, ink, b.left + b.height * 0.45f + pf.width / 2f, b.centerY, p.at + 0.1f, s.ground, false, Kit.Reveal.Fade, name = "candles-pair")
            val ch = change(cs)
            val text = pct(ch, p.rtl)
            val cf = Kit.fit(fit, text, Kit.numberVoice(look, Typo.Data), b.height * 0.32f, b.width * 0.25f, b.height * 0.6f)
            val tone = Kit.tint(look, if (ch >= 0f) look.positive else look.negative, s.ground)
            nodes += Kit.words(text, cf, tone, b.right - b.height * 0.45f - cf.width / 2f, b.centerY, p.at + 0.5f, s.ground, p.rtl, Kit.Reveal.Fade, name = "change")
            nodes += arrow(ch >= 0f, b.right - b.height * 0.55f - cf.width - b.height * 0.1f, b.centerY, b.height * 0.2f, tone)
            val area = Box(b.left + b.height * 0.8f + pf.width, b.top + b.height * 0.2f, b.right - b.height * 1.1f - cf.width, b.bottom - b.height * 0.2f)
            nodes += candleNodes(look, cs, area, p.at + 0.2f, 0.06f, volume = false).first
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Tick, 0.3f)))
        }
    }

    // ------------------------------------------------------------------ ticker tape

    /**
     * A ticker tape of symbols ([Cue.items]) and their moves ([Cue.points], %): one band running
     * the whole time, two crossed bands running opposite ways, or a band with a live chip.
     */
    val Tape = object : Family("tape", listOf(Move.Wipe, Move.Slide, Move.Unfold)) {
        override val minHold = 2.4f
        override val preferredHeight = 0.12f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val symbols = cue.items.ifEmpty { listOf("BTC", "ETH", "SOL", "XRP", "BNB") }.take(8)
            val moves = symbols.indices.map { cue.points?.getOrNull(it) ?: listOf(2.4f, -1.1f, 5.6f, -0.4f, 1.2f, 0.8f)[it % 6] }
            return when (p.layout) {
                1 -> crossed(cue, look, fit, p, symbols, moves)
                2 -> live(cue, look, fit, p, symbols, moves)
                else -> band(cue, look, fit, p, symbols, moves, Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.12f)), Kit.Tone.Ink, 1f, 0f)
            }
        }

        /**
         * One running band in [b]: entries repeated end to end so the band never runs dry, moving
         * at [speed] (px a second, sign = direction), turned by [tilt] degrees.
         */
        private fun band(
            cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, symbols: List<String>, moves: List<Float>, b: Box, tone: Kit.Tone, dirSign: Float, tilt: Float,
            extra: List<Node> = emptyList(),
        ): Built =
            Kit.built(cue, look, p, b, listOf(tape(cue, look, fit, p, symbols, moves, b, tone, dirSign, tilt)) + extra, name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))

        private fun tape(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, symbols: List<String>, moves: List<Float>, b: Box, tone: Kit.Tone, dirSign: Float, tilt: Float): Node {
            val s = Kit.surface(look, cue, tone)
            val ink = Kit.inkOn(look, s.ground)
            val h = b.height
            val entries = mutableListOf<Node>()
            var x = 0f
            val size = h * 0.4f
            val parts = mutableListOf<Pair<Float, List<Node>>>()
            for ((i, sym) in symbols.withIndex()) {
                val sf = Kit.fit(fit, sym, Kit.labelVoice(look, Typo.Data).copy(weight = 800, tracking = 0.02f), size, h * 4f, h * 0.8f)
                val up = moves[i] >= 0f
                val mText = pct(moves[i], p.rtl)
                val mf = Kit.fit(fit, mText, Kit.numberVoice(look, Typo.Data), size * 0.9f, h * 4f, h * 0.8f)
                val arrow = h * 0.34f
                val w = sf.width + arrow + mf.width + h * 1.2f
                val color = Kit.tint(look, if (up) look.positive else look.negative, s.ground)
                val cx = x
                parts += cx to listOf(
                    Kit.words(sym, sf, ink, cx + sf.width / 2f, 0f, -10f, s.ground, false, Kit.Reveal.None, name = "tape-symbol"),
                    ShapeNode(
                        ShapeSpec.Path(if (up) UP else DOWN, 10f, arrow), fill = color.fill(),
                        transform = Transform(x = (cx + sf.width + h * 0.25f + arrow / 2f).anim, y = 0f.anim), name = "tape-arrow",
                    ),
                    Kit.words(mText, mf, color, cx + sf.width + arrow + h * 0.4f + mf.width / 2f, 0f, -10f, s.ground, p.rtl, Kit.Reveal.None, name = "tape-move"),
                    Kit.dot(cx + w - h * 0.3f, 0f, h * 0.08f, ink.copy(alpha = 0.4f), "tape-sep"),
                )
                x += w
            }
            val run = x
            val copies = ((b.width / run).toInt() + 2).coerceAtMost(6)
            for (c in 0 until copies) for ((_, nodes) in parts) entries += Group(nodes, Transform(x = (c * run).anim, anchorX = 0f, anchorY = 0f), name = "tape-entry")
            val speed = h * 1.4f * dirSign
            val travel = speed * (cue.out - p.at + 0.5f)
            val offset = if (dirSign > 0f) -run else 0f
            // Bounds as wide as the run: a moving group is motion-blurred over its own box.
            val lifted = Group(entries, Transform(y = h.anim, anchorX = 0f, anchorY = 0f), name = "tape-entries")
            val scroll = Group(
                listOf(lifted),
                Transform(
                    x = (b.left + offset).anim + Anim.tween(0f, travel.coerceIn(-run * (copies - 1), run * (copies - 1)), p.at, cue.out + 0.5f, Easing.Linear),
                    y = (b.centerY - h).anim, anchorX = 0f, anchorY = 0f,
                ),
                width = run * copies, height = h * 2f, name = "tape-scroll",
            )
            val clip = Kit.rect(b.centerX, b.centerY, b.width, h, Color.White, 0f, "clip")
            val body = Kit.rect(b.centerX, b.centerY, b.width, h, s.ground, h * 0.12f * look.roundness, "tape")
            val inner = listOf(body, Group(listOf(scroll), mask = Mask(clip), name = "tape-window"))
            return Kit.pivot(inner, b.centerX, b.centerY, b, Transform(rotation = tilt.anim), "tape-band")
        }

        private fun crossed(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, symbols: List<String>, moves: List<Float>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.42f))
            val h = minOf(b.height * 0.3f, b.width * 0.11f)
            // Turned bands are shortened so their corners stay inside the slot.
            val w = b.width * 0.94f
            val one = Box.around(b.centerX, b.centerY - h * 0.55f, w, h)
            val two = Box.around(b.centerX, b.centerY + h * 0.55f, w, h)
            val back = tape(cue, look, fit, p, symbols.reversed(), moves.reversed(), two, Kit.Tone.Accent, -1f, 4f)
            val front = tape(cue, look, fit, p, symbols, moves, one, Kit.Tone.Ink, 1f, -4f)
            return Kit.built(cue, look, p, b, listOf(back, front), name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))
        }

        private fun live(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, symbols: List<String>, moves: List<Float>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.12f))
            val h = b.height
            val chipW = h * 2.6f
            val chipBox = if (p.rtl) Box(b.right - chipW, b.top, b.right, b.bottom) else Box(b.left, b.top, b.left + chipW, b.bottom)
            val bandBox = if (p.rtl) Box(b.left, b.top, b.right - chipW, b.bottom) else Box(b.left + chipW, b.top, b.right, b.bottom)
            val hot = Kit.surface(look, cue, Kit.Tone.Hot)
            val word = Kit.say(p.rtl, "زنده", "LIVE")
            val wf = Kit.fit(fit, word, Kit.labelVoice(look, p.typo).copy(weight = 800), h * 0.36f, chipW * 0.6f, h * 0.7f)
            val dot = h * 0.18f
            val ink = Kit.inkOn(look, hot.ground)
            val pulse = Anim.keys(List(((cue.out - p.at) / 0.5f).toInt().coerceAtLeast(1) + 1) { k -> Anim.Key(p.at + k * 0.5f, if (k % 2 == 0) 1f else 0.3f, Easing.SineInOut) })
            val total = dot + h * 0.2f + wf.width
            val startX = chipBox.centerX - p.dir * total / 2f
            val chip = listOf(
                Kit.rect(chipBox.centerX, chipBox.centerY, chipBox.width, h, hot.ground, h * 0.12f * look.roundness, "live-chip"),
                ShapeNode(
                    ShapeSpec.Ellipse(dot.anim, dot.anim), fill = ink.fill(),
                    transform = Transform(x = (startX + p.dir * dot / 2f).anim, y = b.centerY.anim, opacity = pulse), name = "live-dot",
                ),
                Kit.words(word, wf, ink, startX + p.dir * (dot + h * 0.2f + wf.width / 2f), b.centerY, p.at, hot.ground, p.rtl, Kit.Reveal.None, name = "live"),
            )
            val band = tape(cue, look, fit, p, symbols, moves, bandBox, Kit.Tone.Ink, if (p.rtl) 1f else -1f, 0f)
            return Kit.built(cue, look, p, b, listOf(band) + chip, name, listOf(Sfx(p.at, SfxKind.Swish, 0.5f)))
        }
    }

    // ------------------------------------------------------------------ wallet

    /**
     * A wallet ([Cue.label] the network, [Cue.text] an address, [Cue.value] the balance): a metal
     * card flipping in, an address card with a code and a «copied» toast, or a compact address
     * pill. An address is never invented: without one it is shown masked.
     */
    val Wallet = object : Family("wallet", listOf(Move.Flip, Move.Rise, Move.Pop)) {
        override val minHold = 2.4f
        override val preferredHeight = 0.28f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> code(cue, look, fit, p)
            2 -> pill(cue, look, fit, p)
            else -> card(cue, look, fit, p)
        }

        /** The address as shown: the said one shortened (0x7f3a…c21e), else masked. */
        private fun address(cue: Cue): String {
            val a = cue.text.trim().takeIf { it.length >= 10 && it.none { c -> c == ' ' } } ?: return "0x•••• •••• ••••"
            return a.take(6) + "…" + a.takeLast(4)
        }

        private fun network(cue: Cue, p: Kit.Plan) = cue.label ?: Kit.say(p.rtl, "کیف پول تتر", "USDT wallet")

        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, minOf(cue.width * 0.9f, cue.height * 1.58f), minOf(cue.height, cue.width * 0.57f))
            // A dark metal card in every look: its lighter end is what the words are held to.
            val dark = Color(0xFF15161B)
            val lit = androidx.compose.ui.graphics.lerp(dark, look.accent, 0.22f)
            val ink = Look.textOn(lit).let { if (Kit.ratio(it, lit) >= Kit.FLOOR) it else Color.White }
            val r = b.height * 0.08f
            val nodes = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Rect(b.width.anim, b.height.anim, r.anim), fill = Fill.Linear(listOf(dark, lit), angle = 35f.anim),
                    stroke = Stroke(Color.White.copy(alpha = 0.18f).fill(), 2f), shadow = Shadow(Color.Black.copy(alpha = 0.45f), 0f, 22f, 46f),
                    transform = Transform(x = b.centerX.anim, y = b.centerY.anim), name = "wallet-card",
                ),
            )
            // A sheen sweeping across once it has landed.
            val sheen = ShapeNode(
                ShapeSpec.Rect((b.width * 0.18f).anim, (b.height * 1.6f).anim), fill = Fill.Linear(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent)),
                transform = Transform(
                    x = Anim.tween(b.left - b.width * 0.2f, b.right + b.width * 0.2f, p.at + 0.7f, p.at + 1.5f, Easing.SineInOut), y = b.centerY.anim,
                    rotation = 20f.anim,
                ), name = "sheen",
            )
            nodes += Group(listOf(sheen), mask = io.trimio.engine.motion.Mask(Kit.rect(b.centerX, b.centerY, b.width, b.height, Color.White, r, "clip")), name = "wallet-sheen")
            val pad = b.height * 0.12f
            val chipW = b.height * 0.2f
            nodes += Kit.rect(Kit.startX(b, p.rtl, pad + chipW / 2f), b.top + b.height * 0.42f, chipW, chipW * 0.76f, Color(0xFFD9B66A), chipW * 0.15f, "chip")
            val nf = Kit.fit(fit, network(cue, p), Kit.labelVoice(look, p.typo).copy(weight = 800), b.height * 0.08f, b.width * 0.6f, b.height * 0.14f)
            nodes += Kit.words(
                network(cue, p), nf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad), nf.width, p.rtl), b.top + pad + nf.height / 2f, p.at + 0.3f, lit, p.rtl,
                Kit.Reveal.Fade, name = "wallet-network",
            )
            nodes += Kit.icon("coin", Kit.endX(b, p.rtl, pad + b.height * 0.07f), b.top + pad + b.height * 0.06f, b.height * 0.16f, look.accent, p.at + 0.3f, b.height * 0.012f)
            val af = Kit.fit(fit, address(cue), Kit.numberVoice(look, Typo.Data), b.height * 0.1f, b.width - pad * 2f, b.height * 0.16f)
            nodes += Kit.words(address(cue), af, ink, b.left + pad + af.width / 2f, b.top + b.height * 0.66f, p.at + 0.45f, lit, false, Kit.Reveal.Type, name = "wallet-address")
            cue.value?.let { v ->
                val sample = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix
                val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), b.height * 0.13f, b.width * 0.6f, b.height * 0.2f)
                nodes += Kit.number(
                    v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), vf.type.size, ink,
                    Kit.fromEnd(Kit.endX(b, p.rtl, pad), vf.width, p.rtl), b.bottom - pad - vf.height / 2f, p.at + 0.5f, 1f, lit, p.rtl,
                )
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.7f, SfxKind.Shimmer, 0.4f)))
        }

        /** A code-like block of squares (decoration from the address' hash; not a scannable code). */
        private fun pattern(cx: Float, cy: Float, side: Float, ink: Color, seed: Int, at: Float): List<Node> {
            val n = 13
            val cell = side / n
            val nodes = mutableListOf<Node>()
            var k = seed * 2654435761L.toInt() + 7
            fun finder(gx: Int, gy: Int) {
                val x = cx - side / 2f + (gx + 1.5f) * cell
                val y = cy - side / 2f + (gy + 1.5f) * cell
                nodes += ShapeNode(
                    ShapeSpec.Rect((cell * 3f).anim, (cell * 3f).anim, (cell * 0.4f).anim), stroke = Stroke(ink.fill(), cell * 0.6f),
                    transform = Transform(x = x.anim, y = y.anim), name = "finder",
                )
            }
            finder(0, 0)
            finder(n - 3, 0)
            finder(0, n - 3)
            for (gx in 0 until n) for (gy in 0 until n) {
                val corner = (gx < 4 && gy < 4) || (gx >= n - 4 && gy < 4) || (gx < 4 && gy >= n - 4)
                k = k * 1103515245 + 12345
                if (corner || (k ushr 17) and 1 == 0) continue
                val t = at + (gx + gy) * 0.012f
                nodes += ShapeNode(
                    ShapeSpec.Rect((cell * 0.86f).anim, (cell * 0.86f).anim), fill = ink.fill(),
                    transform = Transform(
                        x = (cx - side / 2f + (gx + 0.5f) * cell).anim, y = (cy - side / 2f + (gy + 0.5f) * cell).anim,
                        scale = Anim.tween(0f, 1f, t, t + 0.2f, Easing.ExpoOut),
                    ), name = "module",
                )
            }
            return nodes
        }

        private fun code(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.5f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.1f, "address-card").toMutableList()
            val side = b.height * 0.72f
            val qx = Kit.startX(b, p.rtl, b.height * 0.14f + side / 2f)
            nodes += Kit.rect(qx, b.centerY, side * 1.08f, side * 1.08f, Color.White, side * 0.06f, "code-plate")
            nodes += pattern(qx, b.centerY, side * 0.92f, Color(0xFF111111), address(cue).hashCode(), p.at + 0.3f)
            val edge = Kit.startX(b, p.rtl, b.height * 0.3f + side)
            val room = b.width - side - b.height * 0.45f
            val nf = Kit.fit(fit, network(cue, p), Kit.titleVoice(look, p.typo), b.height * 0.1f, room, b.height * 0.2f)
            nodes += Kit.words(network(cue, p), nf, ink, Kit.fromStart(edge, nf.width, p.rtl), b.top + b.height * 0.24f, p.at + 0.2f, s.ground, p.rtl, Kit.Reveal.Fade, name = "address-network")
            val raised = Kit.surface(look, cue, Kit.Tone.Raised)
            val af = Kit.fit(fit, address(cue), Kit.numberVoice(look, Typo.Data), b.height * 0.08f, room - b.height * 0.3f, b.height * 0.15f)
            val fieldW = room
            val fy = b.centerY + b.height * 0.02f
            nodes += Kit.rect(Kit.fromStart(edge, fieldW, p.rtl), fy, fieldW, af.height + b.height * 0.1f, raised.ground, b.height * 0.05f * look.roundness, "address-field")
            nodes += Kit.words(
                address(cue), af, Kit.inkOn(look, raised.ground), Kit.fromStart(edge + p.dir * b.height * 0.05f, af.width, p.rtl), fy, p.at + 0.4f,
                raised.ground, false, Kit.Reveal.Type, name = "address",
            )
            val tap = p.at + 1.3f
            val acc = Kit.surface(look, cue, Kit.Tone.Accent)
            val copy = Kit.say(p.rtl, "کپی شد", "Copied")
            val cf = Kit.fit(fit, copy, Kit.labelVoice(look, p.typo).copy(weight = 800), b.height * 0.08f, room * 0.6f, b.height * 0.14f)
            val tw = cf.width + b.height * 0.32f
            val ty = b.bottom - b.height * 0.2f
            val tx = Kit.fromStart(edge, tw, p.rtl)
            val toast = listOf(
                Kit.rect(tx, ty, tw, cf.height + b.height * 0.08f, acc.ground, (cf.height + b.height * 0.08f) / 2f * look.roundness, "toast"),
                Kit.icon("check", tx - p.dir * (tw / 2f - b.height * 0.1f), ty, cf.height * 0.8f, Kit.inkOn(look, acc.ground), tap + 0.1f, cf.height * 0.09f),
                Kit.words(copy, cf, Kit.inkOn(look, acc.ground), tx + p.dir * b.height * 0.06f, ty, tap + 0.05f, acc.ground, p.rtl, Kit.Reveal.Fade, name = "toast-text"),
            )
            nodes += Kit.item(Move.Pop, Box.around(tx, ty, tw, cf.height + b.height * 0.08f), tap, p.dir, toast, "toast")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(tap, SfxKind.Click, 0.6f)))
        }

        private fun pill(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.2f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val h = b.height
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, h, h / 2f, "address-pill").toMutableList()
            val acc = Kit.surface(look, cue, Kit.Tone.Accent)
            val d = h * 0.66f
            val cx = Kit.startX(b, p.rtl, h * 0.17f + d / 2f)
            nodes += Kit.dot(cx, b.centerY, d, acc.ground, "coin-disc")
            nodes += Kit.icon("coin", cx, b.centerY, d * 0.62f, Kit.inkOn(look, acc.ground), p.at + 0.2f, d * 0.05f)
            val nf = Kit.fit(fit, network(cue, p), Kit.labelVoice(look, p.typo).copy(weight = 800), h * 0.22f, b.width * 0.5f, h * 0.3f)
            val edge = Kit.startX(b, p.rtl, h * 0.3f + d)
            nodes += Kit.words(
                network(cue, p), nf, Kit.quiet(look, s.ground), Kit.fromStart(edge, nf.width, p.rtl), b.centerY - h * 0.17f, p.at + 0.2f, s.ground, p.rtl,
                Kit.Reveal.Fade, name = "pill-network",
            )
            val af = Kit.fit(fit, address(cue), Kit.numberVoice(look, Typo.Data), h * 0.28f, b.width * 0.6f, h * 0.4f)
            nodes += Kit.words(address(cue), af, ink, Kit.fromStart(edge, af.width, p.rtl), b.centerY + h * 0.16f, p.at + 0.3f, s.ground, false, Kit.Reveal.Type, name = "pill-address")
            val tap = p.at + 1.1f
            val ix = Kit.endX(b, p.rtl, h * 0.45f)
            nodes += Kit.icon("check", ix, b.centerY, h * 0.36f, Kit.tint(look, look.positive, s.ground), tap, h * 0.035f)
            nodes += ShapeNode(
                ShapeSpec.Ellipse((h * 0.5f).anim, (h * 0.5f).anim), stroke = Stroke(look.accent.fill(), 3f),
                transform = Transform(
                    x = ix.anim, y = b.centerY.anim, scale = Anim.tween(0.4f, 2.2f, tap, tap + 0.5f, Easing.ExpoOut),
                    opacity = Anim.tween(0.9f, 0f, tap, tap + 0.5f),
                ), start = tap, end = tap + 0.55f, name = "tap",
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(tap, SfxKind.Click, 0.6f)))
        }
    }

    // ------------------------------------------------------------------ order

    /**
     * An exchange-style order ([Cue.label] the pair, [Cue.items] first item the side: buy/long or
     * sell/short, [Cue.value] the entry, [Cue.points] stop then target): an order ticket that
     * fills, a position with its entry, target and stop on a price ladder, or an order book.
     */
    val Order = object : Family("order", listOf(Move.Rise, Move.Slide, Move.Flip)) {
        override val minHold = 3.2f
        override val preferredHeight = 0.32f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> position(cue, look, fit, p)
            2 -> book(cue, look, fit, p)
            else -> ticket(cue, look, fit, p)
        }

        private fun pair(cue: Cue) = cue.label?.takeIf { it.isNotBlank() } ?: "BTC/USDT"

        private fun buying(cue: Cue): Boolean {
            val side = (cue.items.firstOrNull() ?: cue.text).lowercase()
            return listOf("sell", "short", "فروش", "شورت").none { it in side }
        }

        private fun sideWord(p: Kit.Plan, buy: Boolean, cue: Cue): String {
            val said = (cue.items.firstOrNull() ?: "").lowercase()
            val long = "long" in said || "لانگ" in said
            val short = "short" in said || "شورت" in said
            return when {
                long -> Kit.say(p.rtl, "لانگ", "LONG")
                short -> Kit.say(p.rtl, "شورت", "SHORT")
                buy -> Kit.say(p.rtl, "خرید", "BUY")
                else -> Kit.say(p.rtl, "فروش", "SELL")
            }
        }

        private fun price(v: Float, cue: Cue) = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix

        private fun ticket(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width * 0.86f, minOf(cue.height, cue.width * 0.62f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val buy = buying(cue)
            val side = if (buy) look.positive else look.negative
            val pad = b.height * 0.09f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.07f, "order-card").toMutableList()
            val word = sideWord(p, buy, cue)
            val wf = Kit.fit(fit, word, Kit.labelVoice(look, p.typo).copy(weight = 800), b.height * 0.08f, b.width * 0.3f, b.height * 0.14f)
            val chipW = wf.width + pad * 1.6f
            val chipH = wf.height + pad * 0.6f
            val chipX = Kit.fromStart(Kit.startX(b, p.rtl, pad), chipW, p.rtl)
            val headY = b.top + pad + chipH / 2f
            nodes += Kit.rect(chipX, headY, chipW, chipH, side, chipH * 0.25f * look.roundness, "side-chip")
            nodes += Kit.words(word, wf, Look.textOn(side), chipX, headY, p.at + 0.2f, side, p.rtl, Kit.Reveal.Pop, name = "side")
            val pf = Kit.fit(fit, pair(cue), Kit.titleVoice(look, Typo.Data), b.height * 0.09f, b.width * 0.36f, b.height * 0.16f)
            nodes += Kit.words(pair(cue), pf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad * 1.8f + chipW), pf.width, p.rtl), headY, p.at + 0.25f, s.ground, false, Kit.Reveal.Fade, name = "pair")
            val entry = cue.value ?: 64250f
            val amount = cue.points?.getOrNull(2) ?: 0.25f
            val rows = listOf(
                Kit.say(p.rtl, "قیمت", "Price") to price(entry, cue),
                Kit.say(p.rtl, "مقدار", "Amount") to Kit.format(amount, 2, false),
                Kit.say(p.rtl, "نوع", "Type") to Kit.say(p.rtl, "لیمیت", "Limit"),
            )
            val rowH = (b.height - chipH - pad * 4.2f) / (rows.size + 0.6f)
            for ((i, r) in rows.withIndex()) {
                val y = headY + chipH / 2f + pad * 0.8f + rowH * (i + 0.5f)
                val t0 = p.at + 0.35f + i * 0.12f
                if (i > 0) nodes += Kit.line(b.left + pad, y - rowH / 2f, b.right - pad, y - rowH / 2f, Kit.quiet(look, s.ground).copy(alpha = 0.25f), 2f, t0, 0.3f, "order-rule")
                val lf = Kit.fit(fit, r.first, look.body.copy(weight = maxOf(look.body.weight, 600)), rowH * 0.42f, b.width * 0.4f, rowH * 0.8f)
                val vf = Kit.fit(fit, r.second, Kit.numberVoice(look, Typo.Data), rowH * 0.46f, b.width * 0.5f, rowH * 0.8f)
                nodes += Kit.words(r.first, lf, Kit.quiet(look, s.ground), Kit.fromStart(Kit.startX(b, p.rtl, pad), lf.width, p.rtl), y, t0, s.ground, p.rtl, Kit.Reveal.Fade, name = "order-label")
                nodes += Kit.words(
                    Kit.digits(r.second, p.rtl), vf, ink, Kit.fromEnd(Kit.endX(b, p.rtl, pad), vf.width, p.rtl), y, t0 + 0.05f, s.ground, p.rtl,
                    Kit.Reveal.Fade, name = "order-value",
                )
            }
            // The fill: a bar runs to 100%, then the order is stamped done.
            val barY = b.bottom - pad * 1.6f - rowH * 0.3f
            val bw = b.width - pad * 2f
            val fillAt = p.at + 0.8f
            val raised = Kit.surface(look, cue, Kit.Tone.Raised)
            nodes += Kit.rect(b.centerX, barY, bw, rowH * 0.22f, raised.ground, rowH * 0.11f, "fill-track")
            nodes += ShapeNode(
                ShapeSpec.Rect(Anim.tween(0f, bw, fillAt, fillAt + 1.1f, Easing.ExpoInOut), (rowH * 0.22f).anim, (rowH * 0.11f).anim), fill = side.fill(),
                transform = Transform(x = Kit.startX(b, p.rtl, pad).anim, y = barY.anim, anchorX = if (p.rtl) 1f else 0f), name = "fill",
            )
            val done = Kit.say(p.rtl, "انجام شد", "FILLED")
            val df = Kit.fit(fit, done, look.headline, b.height * 0.085f, b.width * 0.34f, b.height * 0.13f)
            val land = fillAt + 1.15f
            // Stamped in the header's free corner, never over a figure.
            val sx = Kit.endX(b, p.rtl, pad + df.width * 0.6f + pad * 0.3f)
            val sy = headY
            val stampInk = Kit.tint(look, side, s.ground)
            val stamp = listOf(
                ShapeNode(
                    ShapeSpec.Rect((df.width + pad).anim, (df.height + pad * 0.4f).anim, (pad * 0.2f).anim),
                    stroke = Stroke(stampInk.fill(), pad * 0.12f, round = false), transform = Transform(x = sx.anim, y = sy.anim), name = "stamp-frame",
                ),
                Kit.words(done, df, stampInk, sx, sy, land, s.ground, p.rtl, Kit.Reveal.None, name = "stamp"),
            )
            nodes += Kit.pivot(
                stamp, sx, sy, Box.around(sx, sy, df.width + pad, df.height + pad),
                Transform(rotation = (-8f * p.dir).anim, scale = Anim.tween(2.2f, 1f, land, land + 0.16f, Easing.ExpoIn), opacity = Anim.tween(0f, 1f, land, land + 0.06f)),
                "filled", start = land - 0.02f,
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(fillAt, SfxKind.Riser, 0.4f), Sfx(land + 0.16f, SfxKind.Hit, 0.7f)), listOf(Craft.punch(land + 0.16f, 0.02f)))
        }

        /** Entry, target and stop as lines on a price ladder; a marker travels from the entry to the target. */
        private fun position(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.62f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val buy = buying(cue)
            val entry = cue.value ?: 64250f
            val stop = cue.points?.getOrNull(0) ?: if (buy) entry * 0.97f else entry * 1.03f
            val target = cue.points?.getOrNull(1) ?: if (buy) entry * 1.06f else entry * 0.94f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.07f, "position-card").toMutableList()
            val pad = b.height * 0.1f
            val hi = maxOf(stop, target, entry)
            val lo = minOf(stop, target, entry)
            val lad = Box(b.left + pad, b.top + pad * 1.4f, b.right - pad, b.bottom - pad * 1.4f)
            fun y(v: Float) = lad.top + lad.height * (1f - (v - lo) / (hi - lo).coerceAtLeast(1e-6f))
            val levels = listOf(
                Triple(Kit.say(p.rtl, "حد سود", "Target"), target, look.positive),
                Triple(Kit.say(p.rtl, "ورود", "Entry"), entry, ink),
                Triple(Kit.say(p.rtl, "حد ضرر", "Stop"), stop, look.negative),
            )
            val zoneTop = y(maxOf(entry, target))
            val zoneBottom = y(minOf(entry, target))
            nodes += ShapeNode(
                ShapeSpec.Rect(lad.width.anim, (zoneBottom - zoneTop).anim), fill = look.positive.copy(alpha = 0.14f).fill(),
                transform = Transform(x = lad.centerX.anim, y = ((zoneTop + zoneBottom) / 2f).anim, scaleX = Anim.tween(0f, 1f, p.at + 0.5f, p.at + 1f, Easing.ExpoOut)),
                name = "profit-zone",
            )
            val riskTop = y(maxOf(entry, stop))
            val riskBottom = y(minOf(entry, stop))
            nodes += ShapeNode(
                ShapeSpec.Rect(lad.width.anim, (riskBottom - riskTop).anim), fill = look.negative.copy(alpha = 0.14f).fill(),
                transform = Transform(x = lad.centerX.anim, y = ((riskTop + riskBottom) / 2f).anim, scaleX = Anim.tween(0f, 1f, p.at + 0.6f, p.at + 1.1f, Easing.ExpoOut)),
                name = "risk-zone",
            )
            for ((i, lv) in levels.withIndex()) {
                val ly = y(lv.second)
                val t0 = p.at + 0.2f + i * 0.15f
                val color = Kit.tint(look, lv.third, s.ground)
                nodes += Kit.line(lad.left, ly, lad.right, ly, color, 3f, t0, 0.4f, "level")
                val lf = Kit.fit(fit, lv.first, look.body.copy(weight = 800), b.height * 0.085f, b.width * 0.36f, b.height * 0.12f)
                val labelY = ly - lf.height * 0.75f
                nodes += Kit.words(
                    lv.first, lf, color, Kit.fromStart(Kit.startX(lad, p.rtl, 0f), lf.width, p.rtl), labelY.coerceAtLeast(b.top + lf.height * 0.6f), t0 + 0.1f,
                    s.ground, p.rtl, Kit.Reveal.Fade, name = "level-label",
                )
                val vf = Kit.fit(fit, price(lv.second, cue), Kit.numberVoice(look, Typo.Data), b.height * 0.085f, b.width * 0.4f, b.height * 0.12f)
                nodes += Kit.words(
                    Kit.digits(price(lv.second, cue), p.rtl), vf, color, Kit.fromEnd(Kit.endX(lad, p.rtl, 0f), vf.width, p.rtl), labelY.coerceAtLeast(b.top + vf.height * 0.6f),
                    t0 + 0.15f, s.ground, false, Kit.Reveal.Fade, name = "level-price",
                )
            }
            val run = p.at + 1.1f
            val mx = lad.centerX
            val marker = ShapeNode(
                ShapeSpec.Ellipse((b.height * 0.06f).anim, (b.height * 0.06f).anim), fill = look.accent.fill(), stroke = Stroke(Color.White.fill(), 3f),
                transform = Transform(
                    x = mx.anim,
                    y = Anim.tween(y(entry), y(target), run, run + 1.4f, Easing.ExpoInOut) + Anim.Noise(cue.seed, 3f, Anim.tween(6f, 0f, run, run + 1.4f)),
                    scale = Anim.tween(0f, 1f, run - 0.2f, run, Easing.ExpoOut),
                ),
                name = "price-marker",
            )
            nodes += marker
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(run, SfxKind.Riser, 0.4f), Sfx(run + 1.4f, SfxKind.Pop, 0.6f)))
        }

        /** Asks above, bids below as depth bars growing from the reading end; the spread in the middle. */
        private fun book(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.8f))
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val mid = cue.value ?: 64250f
            val levels = 4
            val pad = b.height * 0.06f
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.07f, "book-card").toMutableList()
            val rowH = (b.height - pad * 2f) / (levels * 2 + 1.4f)
            var k = cue.seed * 17 + 3
            for (i in 0 until levels * 2) {
                val ask = i < levels
                val step = if (ask) levels - i else i - levels + 1
                val pr = mid * (1f + (if (ask) 1 else -1) * step * 0.0008f)
                val y = b.top + pad + rowH * (i + 0.5f) + (if (ask) 0f else rowH * 1.4f)
                k = k * 1103515245 + 12345
                val depth = 0.25f + 0.7f * (((k ushr 10) and 0xFF) / 255f) * (0.5f + step / (levels * 2f))
                val color = if (ask) look.negative else look.positive
                val t0 = p.at + 0.2f + (if (ask) levels - 1 - i else i - levels) * 0.08f
                val w = (b.width - pad * 2f) * depth.coerceAtMost(1f)
                nodes += ShapeNode(
                    ShapeSpec.Rect(Anim.tween(0f, w, t0, t0 + 0.6f, Easing.ExpoOut), (rowH * 0.84f).anim), fill = color.copy(alpha = 0.22f).fill(),
                    transform = Transform(x = Kit.endX(b, p.rtl, pad).anim, y = y.anim, anchorX = if (p.rtl) 0f else 1f), name = "depth",
                )
                val pf = Kit.fit(fit, Kit.format(pr, 1, false), Kit.numberVoice(look, Typo.Data), rowH * 0.56f, b.width * 0.4f, rowH * 0.9f)
                // The bar's tint is light: the price is held to the mix of the bar over the card.
                val under = Kit.over(color.copy(alpha = 0.22f), s.ground)
                nodes += Kit.words(Kit.format(pr, 1, p.rtl), pf, Kit.tint(look, color, under), b.left + pad * 1.5f + pf.width / 2f, y, t0 + 0.1f, under, false, Kit.Reveal.Fade, name = "book-price")
                val qty = Kit.format(depth * 3.2f, 3, p.rtl)
                val qf = Kit.fit(fit, qty, Kit.numberVoice(look, Typo.Data), rowH * 0.5f, b.width * 0.3f, rowH * 0.9f)
                nodes += Kit.words(
                    qty, qf, Kit.inkOn(look, under).let { if (Kit.ratio(it, under) >= Kit.FLOOR) it else ink }, b.right - pad * 1.5f - qf.width / 2f, y,
                    t0 + 0.12f, under, false, Kit.Reveal.Fade, name = "book-qty",
                )
            }
            val my = b.top + pad + rowH * (levels + 0.7f)
            val mf = Kit.fit(fit, price(mid, cue), Kit.numberVoice(look, p.typo), rowH * 1.0f, b.width * 0.6f, rowH * 1.3f)
            nodes += Kit.number(
                mid, mid * 0.995f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), mf.type.size, Kit.tint(look, look.positive, s.ground),
                b.centerX, my, p.at + 0.3f, 1.2f, s.ground, p.rtl,
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Tick, 0.4f), Sfx(p.at + 1.5f, SfxKind.Pop, 0.5f)))
        }
    }

    private const val CANDLES = 18

    /** Small up and down triangles in a 10 box. */
    private const val UP = "M5 1L9.5 9H0.5Z"
    private const val DOWN = "M5 9L0.5 1H9.5Z"

    val all: List<Recipe> = listOf(Candles, Tape, Wallet, Order)
}
