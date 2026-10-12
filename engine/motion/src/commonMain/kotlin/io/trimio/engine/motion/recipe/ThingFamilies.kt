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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Things from a phone and the world: a notification stack, a receipt or ticket, an app mockup in
 * a phone, a sticker burst, and a map pin. Every bit of chrome is generic (no real app's look).
 */
object ThingFamilies {

    private fun free(look: Look, cue: Cue): Color? = look.canvas.takeIf { !cue.overMedia }

    private fun inkFree(look: Look, cue: Cue) = free(look, cue)?.let { Kit.inkOn(look, it) } ?: look.ink

    /** A small rounded app tile with an icon: generic, in the look's accent. */
    private fun appTile(look: Look, cx: Float, cy: Float, d: Float, icon: String, at: Float): List<Node> = listOf(
        ShapeNode(
            ShapeSpec.Rect(d.anim, d.anim, (d * 0.26f).anim),
            fill = Fill.Linear(listOf(look.accent, androidx.compose.ui.graphics.lerp(look.accent, look.hot, 0.45f)), angle = 60f.anim),
            transform = Transform(x = cx.anim, y = cy.anim), name = "app-tile",
        ),
        Kit.icon(icon, cx, cy, d * 0.56f, Look.textOn(look.accent), at, d * 0.06f),
    )

    // ------------------------------------------------------------------ notification stack

    /** Notifications ([Cue.items]) from an app ([Cue.label]): a lock-screen stack, one banner after another, or a fanned pile. */
    val Notifications = object : Family("notification-stack", listOf(Move.Drop, Move.Slide, Move.Pop)) {
        override val minHold = 3f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val items = Kit.items(cue, 4).ifEmpty { return Built(emptyList()) }
            return when (p.layout) {
                1 -> banners(cue, look, fit, p, items)
                2 -> pile(cue, look, fit, p, items)
                else -> stack(cue, look, fit, p, items)
            }
        }

        /** One notification card in [box]: tile, app name, «اکنون», and the message. */
        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, text: String, box: Box, at: Float): List<Node> {
            val s = Kit.solid(look, cue)
            val ground = s.ground
            val ink = Kit.inkOn(look, ground)
            val pad = box.height * 0.16f
            val d = box.height * 0.42f
            val app = cue.label ?: Kit.say(p.rtl, "کیف پول", "Wallet")
            val nodes = Kit.panel(look, cue, s, box.centerX, box.centerY, box.width, box.height, box.height * 0.22f, "notification").toMutableList()
            val tx = Kit.startX(box, p.rtl, pad + d / 2f)
            val ty = box.top + pad + d / 2f
            nodes += appTile(look, tx, ty, d, "bell", at + 0.1f)
            val af = Kit.fit(fit, app, Kit.labelVoice(look, p.typo), d * 0.4f, box.width * 0.5f, d * 0.6f)
            val edge = Kit.startX(box, p.rtl, pad * 1.7f + d)
            nodes += Kit.words(app, af, Kit.quiet(look, ground), Kit.fromStart(edge, af.width, p.rtl), ty, at + 0.1f, ground, p.rtl, Kit.Reveal.Fade, name = "notification-app")
            val now = Kit.say(p.rtl, "اکنون", "now")
            val nf = Kit.fit(fit, now, Kit.labelVoice(look, p.typo), d * 0.36f, box.width * 0.2f, d * 0.6f)
            nodes += Kit.words(now, nf, Kit.quiet(look, ground), Kit.fromEnd(Kit.endX(box, p.rtl, pad), nf.width, p.rtl), ty, at + 0.1f, ground, p.rtl, Kit.Reveal.Fade, name = "notification-time")
            val mf = Kit.fit(fit, text, look.body.copy(weight = maxOf(look.body.weight, 700)), box.height * 0.2f, box.width - pad * 2f, box.height - pad * 2.4f - d, lines = 2)
            nodes += Kit.words(
                text, mf, ink, Kit.fromStart(Kit.startX(box, p.rtl, pad), mf.width, p.rtl), ty + d / 2f + pad * 0.6f + mf.height / 2f, at + 0.15f, ground,
                p.rtl, Kit.Reveal.Fade, name = "notification-text",
            )
            return nodes
        }

        /** New ones land on top; the older ones are pushed down, shrink a little and dim. */
        private fun stack(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val n = items.size
            val b = Kit.box(cue, cue.width, cue.height)
            val h = minOf(b.height / (1f + (n - 1) * 0.3f), b.width * 0.3f)
            val step = h * 0.3f
            val times = items.indices.map { Kit.itemAt(cue, it, p.at, 0.6f) }
            val nodes = mutableListOf<Node>()
            // Drawn oldest last-on-top order reversed: the newest is in front.
            for (i in items.indices) {
                val box = Box(b.left, b.top, b.right, b.top + h)
                val t0 = times[i]
                val pushes = (i + 1 until n).map { times[it] }
                val y = Anim.keys(listOf(Anim.Key(t0, 0f, Easing.Hold)) + pushes.mapIndexed { k, t -> Anim.Key(t + 0.35f, step * (k + 1), Easing.ExpoOut) })
                val scale = Anim.keys(listOf(Anim.Key(t0, 1f, Easing.Hold)) + pushes.mapIndexed { k, t -> Anim.Key(t + 0.35f, 1f - 0.05f * (k + 1), Easing.ExpoOut) })
                val dim = Anim.keys(listOf(Anim.Key(t0, 1f, Easing.Hold)) + pushes.mapIndexed { k, t -> Anim.Key(t + 0.35f, maxOf(0.35f, 1f - 0.3f * (k + 1)), Easing.ExpoOut) })
                val inner = Kit.item(p.move, box, t0, p.dir, card(cue, look, fit, p, items[i], box, t0), "notification-$i")
                nodes += Kit.pivot(
                    listOf(inner), box.centerX, box.top, Box(box.left, box.top, box.right, box.bottom + step * n),
                    Transform(y = y, scale = scale, opacity = dim), "notification-slot-$i", start = t0 - 0.05f,
                )
            }
            val box = Box(b.left, b.top, b.right, b.top + h + step * (n - 1))
            return Kit.built(cue, look, p, box, nodes, name, times.map { Sfx(it, SfxKind.Pop, 0.5f) })
        }

        /** One banner at a time: each slides in from the top of the slot and the next replaces it. */
        private fun banners(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.3f))
            val times = items.indices.map { Kit.itemAt(cue, it, p.at, 1.3f) }
            val nodes = items.indices.map { i ->
                val t0 = times[i]
                val gone = times.getOrNull(i + 1)
                val box = Box(b.left, b.top, b.right, b.bottom)
                val enter = Kit.item(p.move, box, t0, p.dir, card(cue, look, fit, p, items[i], box, t0), "banner-$i")
                val leave = gone?.let { g -> anim(0f, g) { by(-b.height * 0.6f, 0.3f, Easing.ExpoIn) } } ?: Anim.Zero
                val fade = gone?.let { g -> anim(1f, g) { by(0f, 0.3f, Easing.ExpoIn) } } ?: Anim.One
                Group(listOf(enter), transform = Transform(anchorX = 0f, anchorY = 0f, y = leave, opacity = fade), start = t0 - 0.05f, end = (gone ?: cue.out) + 0.35f, name = "banner-slot-$i")
            }
            return Kit.built(cue, look, p, b, nodes, name, times.map { Sfx(it, SfxKind.Pop, 0.5f) })
        }

        /** A loose pile: each card lands a little turned, the last one on top. */
        private fun pile(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, items: List<String>): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val h = minOf(b.height * 0.62f, b.width * 0.3f)
            val w = b.width * 0.92f
            val times = items.indices.map { Kit.itemAt(cue, it, p.at, 0.5f) }
            val nodes = items.indices.map { i ->
                val dy = (i - (items.size - 1) / 2f) * h * 0.22f
                val box = Box.around(b.centerX + (if (i % 2 == 0) -1f else 1f) * b.width * 0.02f, b.centerY + dy, w, h)
                val turn = listOf(-4f, 3f, -2f, 2.5f)[i % 4] * p.dir
                val inner = Kit.item(p.move, box, times[i], p.dir, card(cue, look, fit, p, items[i], box, times[i]), "pile-$i")
                Kit.pivot(
                    listOf(inner), box.centerX, box.centerY, box, Transform(rotation = Anim.tween(turn * 3f, turn, times[i], times[i] + 0.5f, Easing.ExpoOut)),
                    "pile-slot-$i", start = times[i] - 0.05f,
                )
            }
            return Kit.built(cue, look, p, b, nodes, name, times.map { Sfx(it, SfxKind.Pop, 0.5f) })
        }
    }

    // ------------------------------------------------------------------ receipt

    /**
     * A receipt ([Cue.items]: "what: price" lines, [Cue.value] the total, [Cue.label] the shop):
     * printed paper with a torn edge, an event ticket with a stub and a barcode, or a payment-done card.
     */
    val Receipt = object : Family("receipt", listOf(Move.Unfold, Move.Rise, Move.Drop)) {
        override val minHold = 3f
        override val preferredHeight = 0.36f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> ticket(cue, look, fit, p)
            2 -> paid(cue, look, fit, p)
            else -> paper(cue, look, fit, p)
        }

        private val PAPER = Color(0xFFF7F4EE)

        private fun lines(cue: Cue): List<Pair<String, String?>> = Kit.items(cue, 5).map { l ->
            val k = l.lastIndexOf(':')
            if (k > 0) l.substring(0, k).trim() to l.substring(k + 1).trim() else l to null
        }

        private fun paper(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val rows = lines(cue)
            val w = minOf(cue.width * 0.62f, cue.height * 0.75f)
            val b = Kit.box(cue, w, cue.height)
            val ink = Kit.inkOn(look, PAPER)
            val pad = b.width * 0.08f
            val nodes = mutableListOf<Node>(
                ShapeNode(ShapeSpec.Path(torn(), 100f, b.width), fill = PAPER.fill(), shadow = Shadow(Color.Black.copy(alpha = 0.3f), 0f, 14f, 30f),
                    transform = Transform(x = b.centerX.anim, y = b.centerY.anim, scaleY = (b.height / b.width).anim), name = "receipt-paper"),
            )
            val shop = cue.label ?: Kit.say(p.rtl, "رسید پرداخت", "RECEIPT")
            val sf = Kit.fit(fit, shop, look.headline, b.height * 0.07f, b.width - pad * 2f, b.height * 0.1f)
            var y = b.top + pad + sf.height / 2f
            nodes += Kit.words(shop, sf, ink, b.centerX, y, p.at + 0.2f, PAPER, p.rtl, Kit.Reveal.Fade, name = "receipt-shop")
            y += sf.height / 2f + pad * 0.6f
            nodes += dashes(b.left + pad, b.right - pad, y, ink, p.at + 0.3f)
            val rowH = (b.height * 0.62f - sf.height) / (rows.size + 1.5f)
            for ((i, r) in rows.withIndex()) {
                y += rowH
                val t0 = p.at + 0.4f + i * 0.18f
                val nf = Kit.fit(fit, r.first, look.body.copy(weight = maxOf(look.body.weight, 600)), rowH * 0.5f, b.width * 0.55f, rowH)
                nodes += Kit.words(r.first, nf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad), nf.width, p.rtl), y, t0, PAPER, p.rtl, Kit.Reveal.Type, name = "receipt-item")
                r.second?.let { price ->
                    val pf = Kit.fit(fit, Kit.digits(price, p.rtl), Kit.numberVoice(look, Typo.Data), rowH * 0.5f, b.width * 0.35f, rowH)
                    nodes += Kit.words(Kit.digits(price, p.rtl), pf, ink, Kit.fromEnd(Kit.endX(b, p.rtl, pad), pf.width, p.rtl), y, t0 + 0.1f, PAPER, p.rtl, Kit.Reveal.Type, name = "receipt-price")
                }
            }
            y += rowH * 0.8f
            nodes += dashes(b.left + pad, b.right - pad, y, ink, p.at + 0.5f + rows.size * 0.18f)
            val total = cue.value
            if (total != null) {
                y += rowH * 0.9f
                val tl = Kit.say(p.rtl, "جمع کل", "TOTAL")
                val tf = Kit.fit(fit, tl, look.headline, rowH * 0.6f, b.width * 0.4f, rowH * 1.2f)
                nodes += Kit.words(
                    tl, tf, ink, Kit.fromStart(Kit.startX(b, p.rtl, pad), tf.width, p.rtl), y, p.at + 0.7f + rows.size * 0.18f, PAPER, p.rtl, Kit.Reveal.Fade,
                    name = "receipt-total-label",
                )
                val sample = cue.prefix + Kit.format(total, cue.decimals, false) + cue.suffix
                val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), rowH * 0.8f, b.width * 0.5f, rowH * 1.3f)
                nodes += Kit.number(
                    total, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), vf.type.size, Kit.tint(look, look.accent, PAPER),
                    Kit.fromEnd(Kit.endX(b, p.rtl, pad), vf.width, p.rtl), y, p.at + 0.8f + rows.size * 0.18f, 0.8f, PAPER, p.rtl,
                )
            }
            // A barcode at the foot.
            nodes += barcode(b.centerX, b.bottom - pad * 1.6f, b.width * 0.6f, pad * 0.9f, ink, cue.seed)
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at, SfxKind.Riser, 0.3f)) + rows.indices.map { Sfx(p.at + 0.4f + it * 0.18f, SfxKind.Tick, 0.3f) })
        }

        /** A paper slip with a torn (zig-zag) foot, filling a 100 box. */
        private fun torn(): String {
            val sb = StringBuilder("M0 0H100V96")
            val teeth = 14
            for (k in teeth downTo 0) {
                val x = 100f * k / teeth
                val y = if (k % 2 == 0) 96f else 100f
                sb.append("L").append(x).append(' ').append(y)
            }
            return sb.append("Z").toString()
        }

        private fun dashes(x0: Float, x1: Float, y: Float, ink: Color, at: Float): Node {
            val n = ((x1 - x0) / 22f).toInt().coerceAtLeast(2)
            val step = (x1 - x0) / n
            return Group(
                (0 until n).map { k -> Kit.rect(x0 + step * (k + 0.5f), y, step * 0.55f, 3f, ink.copy(alpha = 0.55f), 1.5f, "dash") },
                transform = Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, at, at + 0.2f)), name = "dashes",
            )
        }

        /** Bars of a made-up code (decoration, never data). */
        private fun barcode(cx: Float, cy: Float, w: Float, h: Float, ink: Color, seed: Int): Node {
            val bars = mutableListOf<Node>()
            var x = cx - w / 2f
            var k = seed * 7 + 3
            while (x < cx + w / 2f) {
                k = k * 1103515245 + 12345
                val bw = 2f + ((k ushr 16) and 3) * 2f
                if ((k ushr 20) and 1 == 0) bars += Kit.rect(x + bw / 2f, cy, bw, h, ink, 0f, "bar")
                x += bw + 2f
            }
            return Group(bars, name = "barcode")
        }

        private fun ticket(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.42f))
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val ink = Kit.inkOn(look, s.ground)
            val stubW = b.width * 0.28f
            val stubX = Kit.endX(b, p.rtl, stubW)
            val r = b.height * 0.09f
            val nodes = mutableListOf<Node>(Kit.rect(b.centerX, b.centerY, b.width, b.height, s.ground, b.height * 0.1f * look.roundness, "ticket"))
            // The perforation: notches at top and bottom, a dotted tear line between.
            val hole = look.canvas
            nodes += Kit.dot(stubX, b.top, r * 2f, hole, "notch")
            nodes += Kit.dot(stubX, b.bottom, r * 2f, hole, "notch")
            val dots = 9
            for (k in 1 until dots) nodes += Kit.dot(stubX, b.top + b.height * k / dots, r * 0.35f, ink.copy(alpha = 0.5f), "perforation")
            val main = if (p.rtl) Box(stubX, b.top, b.right, b.bottom) else Box(b.left, b.top, stubX, b.bottom)
            val stub = if (p.rtl) Box(b.left, b.top, stubX, b.bottom) else Box(stubX, b.top, b.right, b.bottom)
            val pad = b.height * 0.14f
            val eyebrow = Kit.say(p.rtl, "بلیت", "ADMIT ONE")
            val ef = Kit.fit(fit, eyebrow, Kit.labelVoice(look, p.typo), b.height * 0.09f, main.width * 0.6f, b.height * 0.14f)
            nodes += Kit.words(
                eyebrow, ef, ink, Kit.fromStart(Kit.startX(main, p.rtl, pad), ef.width, p.rtl), b.top + pad + ef.height / 2f, p.at + 0.2f, s.ground, p.rtl,
                Kit.Reveal.Fade, name = "ticket-eyebrow",
            )
            val title = cue.label ?: cue.text.ifBlank { Kit.say(p.rtl, "ورود ویژه", "VIP ENTRY") }
            val tf = Kit.fit(fit, title, look.headline, b.height * 0.22f, main.width - pad * 2f, b.height * 0.45f, lines = 2)
            nodes += Kit.words(title, tf, ink, Kit.fromStart(Kit.startX(main, p.rtl, pad), tf.width, p.rtl), b.centerY + b.height * 0.05f, p.at + 0.3f, s.ground, p.rtl, name = "ticket-title")
            cue.value?.let { v ->
                val sample = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix
                val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), stub.width * 0.24f, stub.width * 0.8f, b.height * 0.3f)
                nodes += Kit.number(
                    v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), vf.type.size, ink, stub.centerX, b.top + b.height * 0.3f,
                    p.at + 0.4f, 0.8f, s.ground, p.rtl,
                )
            }
            nodes += Group(listOf(barcode(stub.centerX, b.top + b.height * 0.68f, stub.width * 0.7f, b.height * 0.26f, ink, cue.seed)), name = "ticket-code")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.2f, SfxKind.Swish, 0.5f)))
        }

        private fun paid(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width * 0.8f, cue.height)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = Kit.panel(look, cue, s, b.centerX, b.centerY, b.width, b.height, b.height * 0.1f, "paid-card").toMutableList()
            val d = b.height * 0.26f
            val cy = b.top + b.height * 0.24f
            val ok = look.positive.let { if (Kit.ratio(Look.textOn(it), it) >= 3f) it else look.accent }
            nodes += ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = ok.fill(),
                transform = Transform(x = b.centerX.anim, y = cy.anim, scale = Anim.tween(0f, 1f, p.at + 0.2f, p.at + 0.65f, Easing.Spring(0.45f, 2.4f))), name = "paid-disc",
            )
            nodes += Kit.icon("check", b.centerX, cy, d * 0.56f, Look.textOn(ok), p.at + 0.45f, d * 0.08f)
            nodes += ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(ok.fill(), 4f),
                transform = Transform(
                    x = b.centerX.anim, y = cy.anim, scale = Anim.tween(1f, 1.8f, p.at + 0.45f, p.at + 1.1f, Easing.ExpoOut),
                    opacity = Anim.tween(0.9f, 0f, p.at + 0.45f, p.at + 1.1f),
                ),
                start = p.at + 0.45f, end = p.at + 1.15f, name = "paid-ring",
            )
            val done = cue.label ?: Kit.say(p.rtl, "پرداخت موفق", "Payment complete")
            val df = Kit.fit(fit, done, Kit.titleVoice(look, p.typo), b.height * 0.08f, b.width * 0.8f, b.height * 0.12f)
            nodes += Kit.words(done, df, Kit.quiet(look, s.ground), b.centerX, cy + d * 0.5f + df.height, p.at + 0.5f, s.ground, p.rtl, Kit.Reveal.Fade, name = "paid-label")
            val v = cue.value ?: 0f
            val sample = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix
            val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), b.height * 0.16f, b.width * 0.86f, b.height * 0.2f)
            val vy = cy + d * 0.5f + df.height * 1.6f + vf.height * 0.6f
            nodes += Kit.number(v, 0f, cue.decimals, cue.prefix, cue.suffix, Kit.numberVoice(look, p.typo), vf.type.size, ink, b.centerX, vy, p.at + 0.6f, 1f, s.ground, p.rtl)
            val rows = lines(cue).take(2)
            val pad = b.width * 0.08f
            for ((i, r) in rows.withIndex()) {
                val y = vy + vf.height * 0.9f + i * b.height * 0.09f
                if (y > b.bottom - pad * 0.6f) break
                val nf = Kit.fit(fit, r.first, look.body.copy(weight = maxOf(look.body.weight, 600)), b.height * 0.045f, b.width * 0.5f, b.height * 0.08f)
                nodes += Kit.words(
                    r.first, nf, Kit.quiet(look, s.ground), Kit.fromStart(Kit.startX(b, p.rtl, pad), nf.width, p.rtl), y, p.at + 0.8f + i * 0.1f, s.ground,
                    p.rtl, Kit.Reveal.Fade, name = "paid-row",
                )
                r.second?.let { v2 ->
                    val pf = Kit.fit(fit, Kit.digits(v2, p.rtl), look.body.copy(weight = 700), b.height * 0.045f, b.width * 0.4f, b.height * 0.08f)
                    nodes += Kit.words(
                        Kit.digits(v2, p.rtl), pf, ink, Kit.fromEnd(Kit.endX(b, p.rtl, pad), pf.width, p.rtl), y, p.at + 0.85f + i * 0.1f, s.ground, p.rtl,
                        Kit.Reveal.Fade, name = "paid-value",
                    )
                }
            }
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.45f, SfxKind.Pop, 0.7f), Sfx(p.at + 0.5f, SfxKind.Shimmer, 0.4f)))
        }
    }

    // ------------------------------------------------------------------ phone mockup

    /**
     * A generic app in a phone ([Cue.label] the app's title, [Cue.value] a balance, [Cue.items]
     * rows with [Cue.points] their figures): upright with its rows sliding in, turned in 3D with a
     * card floating out, or rising from the bottom of the slot with a notification landing on it.
     */
    val Phone = object : Family("phone", listOf(Move.Rise, Move.Flip, Move.Slide)) {
        override val minHold = 3f
        override val preferredHeight = 0.42f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built = when (p.layout) {
            1 -> turned(cue, look, fit, p)
            2 -> rising(cue, look, fit, p)
            else -> upright(cue, look, fit, p)
        }

        /** The phone body and its screen at [cx], [cy] with height [h]; returns the nodes and the screen box. */
        private fun body(look: Look, cx: Float, cy: Float, h: Float): Pair<List<Node>, Box> {
            val w = h * 0.49f
            val bezel = w * 0.045f
            val frame = Color(0xFF16161A)
            val screen = Box.around(cx, cy, w - bezel * 2f, h - bezel * 2f)
            val glass = if (Kit.light(look.canvas)) Color(0xFFFBFAF7) else androidx.compose.ui.graphics.lerp(look.canvas, Color.White, 0.04f)
            return listOf(
                ShapeNode(
                    ShapeSpec.Rect(w.anim, h.anim, (w * 0.16f).anim), fill = frame.fill(), stroke = Stroke(Color(0xFF3A3A40).fill(), 3f),
                    shadow = Shadow(Color.Black.copy(alpha = 0.4f), 0f, 24f, 50f), transform = Transform(x = cx.anim, y = cy.anim), name = "phone",
                ),
                ShapeNode(ShapeSpec.Rect(screen.width.anim, screen.height.anim, (w * 0.13f).anim), fill = glass.fill(), transform = Transform(x = cx.anim, y = cy.anim), name = "phone-screen"),
                Kit.rect(cx, screen.top + screen.height * 0.025f, w * 0.26f, screen.height * 0.022f, frame, screen.height * 0.011f, "phone-notch"),
            ) to screen
        }

        /** The app on [scr]: title, balance, a sparkline and rows; returns its nodes. */
        private fun app(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, scr: Box, at: Float, ground: Color): List<Node> {
            val ink = Kit.inkOn(look, ground)
            val pad = scr.width * 0.08f
            val nodes = mutableListOf<Node>()
            val title = cue.label ?: Kit.say(p.rtl, "دارایی من", "My portfolio")
            val tf = Kit.fit(fit, title, look.body.copy(weight = 800), scr.width * 0.08f, scr.width - pad * 2f, scr.height * 0.06f)
            var y = scr.top + scr.height * 0.1f
            nodes += Kit.words(title, tf, Kit.quiet(look, ground), Kit.fromStart(Kit.startX(scr, p.rtl, pad), tf.width, p.rtl), y, at, ground, p.rtl, Kit.Reveal.Fade, name = "app-title")
            val v = cue.value ?: 12480f
            val sample = cue.prefix.ifEmpty { "$" } + Kit.format(v, cue.decimals, false) + cue.suffix
            val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), scr.width * 0.17f, scr.width - pad * 2f, scr.height * 0.1f)
            y += tf.height * 0.6f + vf.height * 0.7f
            nodes += Kit.number(
                v, v * 0.6f, cue.decimals, cue.prefix.ifEmpty { "$" }, cue.suffix, Kit.numberVoice(look, p.typo), vf.type.size, ink,
                Kit.fromStart(Kit.startX(scr, p.rtl, pad), vf.width, p.rtl), y, at + 0.15f, 1.2f, ground, p.rtl,
            )
            y += vf.height * 0.8f
            val chartH = scr.height * 0.2f
            val pts = listOf(3f, 3.6f, 3.2f, 4.4f, 4.1f, 5.3f, 5f, 6.4f)
            val xy = pts.mapIndexed { i, q -> scr.left + pad + (scr.width - pad * 2f) * i / (pts.size - 1) to y + chartH * (1f - (q - 3f) / 3.4f) }
            nodes += Kit.path(xy, look.positive.let { Kit.tint(look, it, ground) }, scr.width * 0.012f, at + 0.3f, 0.9f, smooth = true, name = "app-spark")
            y += chartH + scr.height * 0.05f
            val rows = cue.items.ifEmpty { listOf("BTC", "ETH", "USDT") }.take(3)
            val rowH = scr.height * 0.115f
            val raised = androidx.compose.ui.graphics.lerp(ground, look.ink, 0.06f)
            for ((i, r) in rows.withIndex()) {
                val t0 = at + 0.5f + i * 0.15f
                val row = Box(scr.left + pad, y, scr.right - pad, y + rowH * 0.86f)
                val parts = mutableListOf<Node>(Kit.rect(row.centerX, row.centerY, row.width, row.height, raised, row.height * 0.25f * look.roundness, "app-row"))
                val d = row.height * 0.56f
                val ix = Kit.startX(row, p.rtl, row.height * 0.25f + d / 2f)
                parts += Kit.dot(ix, row.centerY, d, listOf(look.accent, look.hot, look.positive)[i % 3], "coin")
                val nf = Kit.fit(fit, r, look.body.copy(weight = 800), row.height * 0.32f, row.width * 0.5f, row.height * 0.7f)
                parts += Kit.words(
                    r, nf, Kit.inkOn(look, raised), Kit.fromStart(ix - p.dir * (-d / 2f - row.height * 0.2f), nf.width, p.rtl), row.centerY, t0 + 0.05f, raised,
                    p.rtl, Kit.Reveal.Fade, name = "app-row-name",
                )
                val change = cue.points?.getOrNull(i) ?: listOf(2.4f, -1.2f, 0.1f)[i % 3]
                val cs = (if (change >= 0f) "+" else "") + Kit.format(change, 1, false) + "%"
                val cf = Kit.fit(fit, cs, Kit.numberVoice(look, Typo.Data), row.height * 0.3f, row.width * 0.3f, row.height * 0.6f)
                parts += Kit.words(
                    Kit.digits(cs, p.rtl).let { if (p.rtl) it.replace("%", "٪") else it }, cf,
                    Kit.tint(look, if (change >= 0f) look.positive else look.negative, raised),
                    Kit.fromEnd(Kit.endX(row, p.rtl, row.height * 0.3f), cf.width, p.rtl), row.centerY, t0 + 0.1f, raised, p.rtl, Kit.Reveal.Fade,
                    name = "app-row-change",
                )
                nodes += Kit.item(Move.Slide, row, t0, p.dir, parts, "app-row-$i")
                y += rowH
            }
            // A tab bar at the foot: four generic tabs, the first one lit.
            val barY = scr.bottom - scr.height * 0.06f
            nodes += Kit.line(
                scr.left + pad * 0.5f, barY - scr.height * 0.045f, scr.right - pad * 0.5f, barY - scr.height * 0.045f,
                Kit.quiet(look, ground).copy(alpha = 0.3f), 2f, at, 0.3f, "tab-rule",
            )
            listOf("home", "chart", "card", "user").forEachIndexed { k, icon ->
                val x = scr.left + scr.width * (k + 0.5f) / 4f
                nodes += Kit.icon(icon, x, barY, scr.width * 0.08f, if (k == 0) Kit.tint(look, look.accent, ground) else Kit.quiet(look, ground), at + 0.2f + k * 0.05f, scr.width * 0.008f)
            }
            return nodes
        }

        private fun screenGround(look: Look) = if (Kit.light(look.canvas)) Color(0xFFFBFAF7) else androidx.compose.ui.graphics.lerp(look.canvas, Color.White, 0.04f)

        private fun upright(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val h = minOf(cue.height, cue.width * 1.2f)
            val b = Kit.box(cue, h * 0.49f, h)
            val (phone, scr) = body(look, b.centerX, b.centerY, b.height)
            return Kit.built(cue, look, p, b, phone + app(cue, look, fit, p, scr, p.at + 0.3f, screenGround(look)), name, listOf(Sfx(p.at + 0.5f, SfxKind.Click, 0.4f)))
        }

        /** Turned in 3D toward the reading end; the balance card floats out over the reading start. */
        private fun turned(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val h = minOf(b.height, b.width * 1.1f)
            val px = Kit.endX(b, p.rtl, h * 0.3f)
            val (phone, scr) = body(look, px, b.centerY, h)
            val turned = Kit.pivot(
                phone + app(cue, look, fit, p, scr, p.at + 0.3f, screenGround(look)), px, b.centerY, Box.around(px, b.centerY, h * 0.5f, h),
                Transform(rotationY = (p.dir * 22f).anim, perspective = 1800f), "phone-turned",
            )
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val cw = b.width * 0.5f
            val ch = cw * 0.5f
            val cx = Kit.fromStart(Kit.startX(b, p.rtl, 0f), cw, p.rtl)
            val cy = b.centerY + h * 0.12f
            val t0 = p.at + 0.9f
            val ink = Kit.inkOn(look, s.ground)
            val label = Kit.say(p.rtl, "سود امروز", "Today")
            val lf = Kit.fit(fit, label, Kit.labelVoice(look, p.typo), ch * 0.16f, cw * 0.8f, ch * 0.3f)
            val gain = cue.points?.firstOrNull() ?: 2.4f
            val sample = "+" + Kit.format(gain, 1, false) + "%"
            val vf = Kit.fit(fit, sample, Kit.numberVoice(look, p.typo), ch * 0.4f, cw * 0.8f, ch * 0.5f)
            val card = listOf(
                ShapeNode(
                    ShapeSpec.Rect(cw.anim, ch.anim, (ch * 0.18f * look.roundness).anim), fill = s.fill,
                    shadow = Shadow(Color.Black.copy(alpha = 0.4f), 0f, 20f, 44f), transform = Transform(x = cx.anim, y = cy.anim), name = "float-card",
                ),
                Kit.words(
                    label, lf, ink, Kit.fromStart(cx - p.dir * cw / 2f + p.dir * ch * 0.18f, lf.width, p.rtl), cy - ch * 0.22f, t0 + 0.1f, s.ground, p.rtl,
                    Kit.Reveal.Fade, name = "float-label",
                ),
                Kit.number(
                    gain, 0f, 1, "+", if (p.rtl) "٪" else "%", Kit.numberVoice(look, p.typo), vf.type.size, ink,
                    Kit.fromStart(cx - p.dir * cw / 2f + p.dir * ch * 0.18f, vf.width, p.rtl), cy + ch * 0.12f, t0 + 0.15f, 0.9f, s.ground, p.rtl,
                ),
            )
            val floating = Kit.item(Move.Pop, Box.around(cx, cy, cw, ch), t0, p.dir, card, "float")
            return Kit.built(cue, look, p, b, listOf(turned, floating), name, listOf(Sfx(t0, SfxKind.Pop, 0.6f)))
        }

        /** Rising from the bottom of the slot (its top half shown); a notification lands over it. */
        private fun rising(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val h = minOf(b.height * 1.7f, b.width * 1.4f)
            val cy = b.bottom + h * 0.5f - b.height * 0.92f
            val (phone, scr) = body(look, b.centerX, cy, h)
            val clip = Kit.rect(b.centerX, b.centerY, b.width * 1.2f, b.height, Color.White, 0f, "clip")
            val content = Group(phone + app(cue, look, fit, p, scr, p.at + 0.3f, screenGround(look)), mask = Mask(clip), name = "phone-rising")
            val s = Kit.surface(look, cue)
            val nw = b.width * 0.9f
            val nh = b.height * 0.24f
            val box = Box.around(b.centerX, b.top + nh * 0.6f, nw, nh)
            val t0 = p.at + 1.1f
            val note = cue.text.ifBlank { Kit.say(p.rtl, "سفارش شما انجام شد", "Your order was filled") }
            val nf = Kit.fit(fit, note, look.body.copy(weight = 800), nh * 0.3f, nw - nh * 1.3f, nh * 0.7f)
            val ink = Kit.inkOn(look, s.ground)
            val tx = Kit.startX(box, p.rtl, nh * 0.18f + nh * 0.3f)
            val parts = Kit.panel(look, cue, s, box.centerX, box.centerY, box.width, box.height, nh * 0.3f, "phone-note").toMutableList<Node>() +
                appTile(look, tx, box.centerY, nh * 0.6f, "bell", t0 + 0.1f) +
                Kit.words(note, nf, ink, Kit.fromStart(tx - p.dir * (-nh * 0.45f), nf.width, p.rtl), box.centerY, t0 + 0.15f, s.ground, p.rtl, Kit.Reveal.Fade, name = "phone-note-text")
            val drop = Kit.item(Move.Drop, box, t0, p.dir, parts, "phone-note")
            return Kit.built(cue, look, p, b, listOf(content, drop), name, listOf(Sfx(t0, SfxKind.Pop, 0.6f)))
        }
    }

    // ------------------------------------------------------------------ sticker burst

    /**
     * A reaction ([Cue.text], [Cue.icon]): a die-cut sticker with a burst of rays, a sticker in a
     * shower of confetti, or a ring of icon stickers popping around the word.
     */
    val Burst = object : Family("burst", listOf(Move.Pop, Move.Drop, Move.Focus)) {
        override val minHold = 1.6f
        override val preferredHeight = 0.2f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val word = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            return when (p.layout) {
                1 -> confetti(cue, look, fit, p, word)
                2 -> stickers(cue, look, fit, p, word)
                else -> rays(cue, look, fit, p, word)
            }
        }

        /** The word as a die-cut sticker: on a white (or ink) pill with a thick outline, turned a little. */
        private fun sticker(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, word: String, cx: Float, cy: Float, size: Float, maxW: Float, at: Float): Node {
            val s = Kit.surface(look, cue, Kit.Tone.Accent)
            val f = Kit.fit(fit, word, look.headline, size, maxW, size * 1.6f)
            val w = f.width + f.type.size * 0.9f
            val h = f.height + f.type.size * 0.45f
            val edge = if (Kit.light(look.canvas)) look.ink else Color.White
            val nodes = listOf(
                ShapeNode(
                    ShapeSpec.Rect(w.anim, h.anim, (h * 0.3f).anim), fill = s.fill, stroke = Stroke(edge.fill(), h * 0.07f),
                    shadow = Shadow(Color.Black.copy(alpha = 0.35f), 0f, 10f, 22f), transform = Transform(x = cx.anim, y = cy.anim), name = "sticker",
                ),
                Kit.words(word, f, Kit.inkOn(look, s.ground), cx, cy, at + 0.05f, s.ground, p.rtl, Kit.Reveal.None, name = "sticker-text"),
            )
            return Kit.pivot(
                nodes, cx, cy, Box.around(cx, cy, w, h),
                Transform(rotation = anim(-p.dir * 18f, at) { by(-p.dir * 5f, 0.5f, Easing.Spring(0.4f, 2.2f)) }, scale = anim(0.2f, at) { by(1f, 0.45f, Easing.Spring(0.42f, 2.4f)) }),
                "sticker-word", start = at - 0.02f,
            )
        }

        private fun rays(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, word: String): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val hit = p.at + 0.1f
            val nodes = mutableListOf<Node>()
            val r0 = b.height * 0.32f
            val r1 = b.height * 0.48f
            // Rays stretch sideways, never past the slot.
            val wide = minOf(1.7f, b.width / 2f / r1 * 0.96f)
            for (k in 0 until RAYS) {
                val a = k * 2 * PI.toFloat() / RAYS + 0.2f
                val x0 = b.centerX + cos(a) * r0 * wide * 0.94f
                val y0 = b.centerY + sin(a) * r0
                val x1 = b.centerX + cos(a) * r1 * wide
                val y1 = b.centerY + sin(a) * r1
                nodes += Kit.line(x0, y0, x1, y1, if (k % 2 == 0) look.accent else look.hot, maxOf(6f, b.height * 0.03f), hit + 0.12f, 0.25f, "ray")
            }
            val icon = cue.icon?.let { Icons.path(it) }?.let { cue.icon } ?: "star"
            for (k in 0 until 4) {
                val a = k * PI.toFloat() / 2f + 0.8f
                val x = b.centerX + cos(a) * b.width * 0.36f
                val y = b.centerY + sin(a) * b.height * 0.38f
                nodes += ShapeNode(
                    ShapeSpec.Path(Icons.path(icon)!!, Icons.VIEWPORT, b.height * 0.14f), fill = look.hot.fill(),
                    transform = Transform(
                        x = x.anim, y = y.anim, scale = Anim.tween(0f, 1f, hit + 0.15f + k * 0.05f, hit + 0.55f + k * 0.05f, Easing.Spring(0.4f, 2.6f)),
                        rotation = Anim.tween(-90f, 0f, hit, hit + 0.6f, Easing.ExpoOut),
                    ),
                    name = "spark",
                )
            }
            nodes += sticker(cue, look, fit, p, word, b.centerX, b.centerY, b.height * 0.3f, b.width * 0.7f, hit)
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(hit, SfxKind.Pop, 0.8f)), listOf(Craft.punch(hit + 0.1f, 0.02f)))
        }

        private fun confetti(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, word: String): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val hit = p.at + 0.1f
            val nodes = mutableListOf<Node>()
            val colors = listOf(look.accent, look.hot, look.positive, Color.White)
            var k = cue.seed * 31 + 7
            for (i in 0 until PIECES) {
                k = k * 1103515245 + 12345
                val u = ((k ushr 8) and 0xFFFF) / 65535f
                k = k * 1103515245 + 12345
                val v = ((k ushr 8) and 0xFFFF) / 65535f
                val x = b.left + b.width * u
                val delay = v * 0.6f
                val fallTo = b.top + b.height * (0.4f + 0.6f * ((u * 7f + v * 3f) % 1f))
                val size = b.height * (0.03f + 0.025f * v)
                nodes += ShapeNode(
                    if (i % 3 == 0) ShapeSpec.Ellipse(size.anim, size.anim) else ShapeSpec.Rect(size.anim, (size * 0.5f).anim),
                    fill = colors[i % colors.size].fill(),
                    transform = Transform(
                        x = x.anim + Anim.Noise(i + 3, 1.2f, (size * 1.5f).anim), y = Anim.tween(b.top, fallTo, hit + delay, hit + delay + 1.4f, Easing.QuintOut),
                        rotation = Anim.tween(0f, 360f * (if (i % 2 == 0) 1f else -1f), hit + delay, hit + delay + 2.2f, Easing.Linear),
                        opacity = anim(0f, hit + delay) { by(1f, 0.05f); hold(cue.out - 0.6f); by(0f, 0.3f) },
                    ),
                    start = hit + delay - 0.02f, name = "confetti",
                )
            }
            nodes += sticker(cue, look, fit, p, word, b.centerX, b.centerY, b.height * 0.3f, b.width * 0.7f, hit)
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(hit, SfxKind.Pop, 0.8f), Sfx(hit + 0.05f, SfxKind.Shimmer, 0.5f)))
        }

        private fun stickers(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, word: String): Built {
            val b = Kit.box(cue, cue.width, cue.height)
            val hit = p.at + 0.1f
            val icons = listOf(cue.icon?.takeIf { Icons.path(it) != null } ?: "heart", "fire", "star", "bolt", "heart")
            val nodes = mutableListOf<Node>()
            val d = b.height * 0.3f
            val spots = listOf(-0.4f to -0.25f, 0.42f to -0.2f, -0.36f to 0.3f, 0.38f to 0.32f, 0f to -0.42f)
            val disc = if (Kit.light(look.canvas)) Color.White else Color(0xFFF7F4EE)
            for ((i, s) in spots.withIndex()) {
                val x = b.centerX + s.first * b.width * p.dir
                val y = b.centerY + s.second * b.height
                val t0 = hit + 0.12f * i
                val color = listOf(look.hot, look.accent, look.positive)[i % 3]
                val parts = listOf(
                    ShapeNode(
                        ShapeSpec.Ellipse(d.anim, d.anim), fill = disc.fill(), shadow = Shadow(Color.Black.copy(alpha = 0.3f), 0f, 8f, 18f),
                        transform = Transform(x = x.anim, y = y.anim), name = "icon-sticker",
                    ),
                    ShapeNode(
                        ShapeSpec.Path(Icons.path(icons[i % icons.size])!!, Icons.VIEWPORT, d * 0.58f), fill = color.fill(),
                        stroke = Stroke(color.fill(), d * 0.02f), transform = Transform(x = x.anim, y = y.anim), name = "icon-sticker-glyph",
                    ),
                )
                nodes += Kit.pivot(
                    parts, x, y, Box.around(x, y, d, d),
                    Transform(
                        scale = Anim.tween(0f, 1f, t0, t0 + 0.45f, Easing.Spring(0.4f, 2.6f)),
                        rotation = Anim.tween(-30f, (i % 3 - 1) * 10f, t0, t0 + 0.5f, Easing.ExpoOut) + Anim.Noise(i + cue.seed, 0.6f, 4f.anim),
                    ),
                    "icon-sticker-$i", start = t0 - 0.02f,
                )
            }
            nodes += sticker(cue, look, fit, p, word, b.centerX, b.centerY, b.height * 0.28f, b.width * 0.5f, hit)
            return Kit.built(cue, look, p, b, nodes, name, spots.indices.map { Sfx(hit + 0.12f * it, SfxKind.Pop, 0.45f) })
        }
    }

    // ------------------------------------------------------------------ map pin

    /**
     * A place ([Cue.text] or [Cue.label]): a pin dropping onto a stylised map card, a wide map strip
     * with the name in a callout, or a round map lens beside the name. The map is drawn, never a
     * real map's tiles.
     */
    val MapPin = object : Family("map-pin", listOf(Move.Drop, Move.Pop, Move.Focus)) {
        override val minHold = 2.2f
        override val preferredHeight = 0.3f

        override fun compose(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan): Built {
            val place = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            return when (p.layout) {
                1 -> strip(cue, look, fit, p, place)
                2 -> lens(cue, look, fit, p, place)
                else -> card(cue, look, fit, p, place)
            }
        }

        /** The map's own colours: land, blocks, roads and water, in the look's light or dark. */
        private fun palette(look: Look): List<Color> = if (Kit.light(look.canvas)) {
            listOf(Color(0xFFEDEAE2), Color(0xFFE1DDD2), Color(0xFFFFFFFF), Color(0xFFBFD8E6))
        } else {
            listOf(
                androidx.compose.ui.graphics.lerp(look.canvas, Color(0xFF26324A), 0.6f),
                androidx.compose.ui.graphics.lerp(look.canvas, Color(0xFF2E3A52), 0.75f), Color(0xFF55607A), Color(0xFF1E4A66),
            )
        }

        private fun parkOf(look: Look) = if (Kit.light(look.canvas)) Color(0xFFCFE3C4) else Color(0xFF29483A)

        /** A drawn map in [box]: blocks, a river, a grid of roads and two diagonals. */
        private fun map(look: Look, box: Box, seed: Int, at: Float): List<Node> {
            val (land, block, road, water) = palette(look)
            val nodes = mutableListOf<Node>(Kit.rect(box.centerX, box.centerY, box.width, box.height, land, 0f, "map-land"))
            val cell = box.width / 6f
            var k = seed * 13 + 1
            for (gx in 0 until 7) for (gy in 0 until (box.height / cell).toInt() + 2) {
                k = k * 1103515245 + 12345
                if ((k ushr 18) and 3 == 0) continue
                val x = box.left + gx * cell + cell / 2f
                val y = box.top + gy * cell + cell / 2f
                // Now and then a park instead of a block.
                val park = (k ushr 22) and 7 == 0
                nodes += Kit.rect(x, y, cell * 0.78f, cell * 0.78f, if (park) parkOf(look) else block, cell * (if (park) 0.2f else 0.06f), if (park) "map-park" else "map-block")
            }
            val river = (0..20).map { i ->
                val u = i / 20f
                box.left + box.width * u to box.top + box.height * (0.7f + 0.12f * sin(u * 5f + seed))
            }
            nodes += Kit.path(river, water, cell * 0.45f, at - 0.4f, 0.01f, smooth = true, name = "map-river")
            for (gx in 1 until 6) nodes += Kit.line(box.left + gx * cell, box.top, box.left + gx * cell, box.bottom, road, cell * 0.12f, at - 0.4f, 0.01f, "map-road")
            var y = box.top + cell
            while (y < box.bottom) { nodes += Kit.line(box.left, y, box.right, y, road, cell * 0.12f, at - 0.4f, 0.01f, "map-road"); y += cell }
            nodes += Kit.line(box.left, box.bottom, box.right, box.top + box.height * 0.2f, road, cell * 0.2f, at - 0.4f, 0.01f, "map-avenue")
            return nodes
        }

        /** The pin: drops onto ([x], [y]) with a bounce, its shadow tightening, a ripple on landing. */
        private fun pin(look: Look, x: Float, y: Float, size: Float, at: Float, top: Float): List<Node> {
            val land = at + 0.45f
            // It falls from the top of its graphic, never from outside it.
            val fall = (y - size - top).coerceIn(size * 0.4f, size * 2.4f)
            val drop = anim(-fall, at) { by(0f, 0.45f, Easing.ExpoIn); by(-size * 0.25f, 0.14f, Easing.ExpoOut); by(0f, 0.16f, Easing.ExpoIn) }
            val color = look.hot
            return listOf(
                ShapeNode(
                    ShapeSpec.Ellipse((size * 0.6f).anim, (size * 0.2f).anim), fill = Color.Black.copy(alpha = 0.3f).fill(),
                    transform = Transform(x = x.anim, y = y.anim, scale = Anim.tween(0.3f, 1f, at, land, Easing.ExpoIn)), name = "pin-shadow",
                ),
                ShapeNode(
                    ShapeSpec.Ellipse((size * 0.5f).anim, (size * 0.18f).anim), stroke = Stroke(color.fill(), 3f),
                    transform = Transform(
                        x = x.anim, y = y.anim, scale = Anim.tween(1f, 4f, land, land + 0.8f, Easing.ExpoOut),
                        opacity = Anim.tween(0.9f, 0f, land, land + 0.8f),
                    ), start = land, end = land + 0.85f, name = "pin-ripple",
                ),
                ShapeNode(
                    ShapeSpec.Path(PIN, 100f, size), fill = color.fill(), stroke = Stroke(Color.White.fill(), size * 0.03f),
                    transform = Transform(x = x.anim, y = (y - size / 2f).anim + drop), name = "pin",
                ),
                ShapeNode(
                    ShapeSpec.Ellipse((size * 0.3f).anim, (size * 0.3f).anim), fill = Color.White.fill(),
                    transform = Transform(x = x.anim, y = (y - size * 0.62f).anim + drop), name = "pin-eye",
                ),
            )
        }

        private fun nameChip(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, place: String, cx: Float, cy: Float, maxW: Float, size: Float, at: Float): List<Node> {
            val s = Kit.surface(look, cue, Kit.Tone.Ink)
            val f = Kit.fit(fit, place, Kit.titleVoice(look, p.typo), size, maxW, size * 1.6f)
            val w = f.width + size * 1.1f
            val h = f.height + size * 0.5f
            return listOf(
                Kit.rect(cx, cy, w, h, s.ground, h / 2f * look.roundness, "place-chip"),
                Kit.words(place, f, Kit.inkOn(look, s.ground), cx, cy, at, s.ground, p.rtl, Kit.Reveal.Fade, name = "place"),
            )
        }

        private fun card(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, place: String): Built {
            val b = Kit.box(cue, minOf(cue.width, cue.height * 1.3f), cue.height)
            val clip = Kit.rect(b.centerX, b.centerY, b.width, b.height, Color.White, b.height * 0.1f * look.roundness, "clip")
            val nodes = mutableListOf<Node>(Group(map(look, b, cue.seed, p.at), mask = Mask(clip), name = "map"))
            val pinSize = b.height * 0.26f
            nodes += pin(look, b.centerX, b.centerY + b.height * 0.06f, pinSize, p.at + 0.2f, b.top)
            nodes += nameChip(cue, look, fit, p, place, b.centerX, b.bottom - b.height * 0.14f, b.width * 0.8f, b.height * 0.08f, p.at + 0.8f)
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.65f, SfxKind.Pop, 0.7f)))
        }

        private fun strip(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, place: String): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.42f))
            val clip = Kit.rect(b.centerX, b.centerY, b.width, b.height, Color.White, b.height * 0.12f * look.roundness, "clip")
            val nodes = mutableListOf<Node>(Group(map(look, b, cue.seed + 1, p.at), mask = Mask(clip), name = "map"))
            val px = Kit.startX(b, p.rtl, b.width * 0.28f)
            val py = b.centerY + b.height * 0.18f
            nodes += pin(look, px, py, b.height * 0.36f, p.at + 0.2f, b.top)
            val s = Kit.surface(look, cue)
            val f = Kit.fit(fit, place, Kit.titleVoice(look, p.typo), b.height * 0.13f, b.width * 0.46f, b.height * 0.3f, lines = 2)
            val extra = cue.label?.takeIf { cue.text.isNotBlank() }
            val ef = extra?.let { Kit.fit(fit, it, Kit.labelVoice(look, p.typo), b.height * 0.08f, b.width * 0.46f, b.height * 0.15f) }
            val w = maxOf(f.width, ef?.width ?: 0f) + b.height * 0.24f
            val h = f.height + (ef?.height?.times(1.2f) ?: 0f) + b.height * 0.16f
            val cx = Kit.fromStart(px - p.dir * (-b.height * 0.24f), w, p.rtl)
            val cy = b.top + b.height * 0.1f + h / 2f
            val t0 = p.at + 0.8f
            val parts = Kit.panel(look, cue, s, cx, cy, w, h, h * 0.2f, "place-callout").toMutableList<Node>()
            parts += Kit.words(place, f, Kit.inkOn(look, s.ground), cx, cy - (ef?.height?.times(0.5f) ?: 0f), t0 + 0.05f, s.ground, p.rtl, Kit.Reveal.Fade, name = "place")
            if (ef != null && extra != null) parts += Kit.words(
                extra, ef, Kit.quiet(look, s.ground), cx, cy + f.height * 0.5f + ef.height * 0.1f, t0 + 0.15f, s.ground, p.rtl, Kit.Reveal.Fade,
                name = "place-extra",
            )
            nodes += Kit.item(Move.Pop, Box.around(cx, cy, w, h), t0, p.dir, parts, "place-card")
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.65f, SfxKind.Pop, 0.7f)))
        }

        private fun lens(cue: Cue, look: Look, fit: Fitter, p: Kit.Plan, place: String): Built {
            val b = Kit.box(cue, cue.width, minOf(cue.height, cue.width * 0.46f))
            val d = b.height
            val lx = Kit.startX(b, p.rtl, d / 2f)
            val lensBox = Box.around(lx, b.centerY, d, d)
            val clip = ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), fill = Color.White.fill(), transform = Transform(x = lx.anim, y = b.centerY.anim), name = "clip")
            val ring = ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), stroke = Stroke(look.accent.fill(), d * 0.03f), transform = Transform(x = lx.anim, y = b.centerY.anim), name = "lens-ring")
            val nodes = mutableListOf<Node>(
                Group(map(look, Box(lensBox.left - d * 0.2f, lensBox.top, lensBox.right + d * 0.2f, lensBox.bottom), cue.seed + 2, p.at), mask = Mask(clip), name = "map"),
                ring,
            )
            nodes += pin(look, lx, b.centerY + d * 0.12f, d * 0.3f, p.at + 0.2f, b.top)
            val ground = free(look, cue)
            val room = b.width - d - b.width * 0.06f
            val f = Kit.fit(fit, place, Kit.titleVoice(look, p.typo), d * 0.2f, room, d * 0.5f, lines = 2)
            val edge = Kit.startX(b, p.rtl, d + b.width * 0.05f)
            nodes += Kit.words(place, f, inkFree(look, cue), Kit.fromStart(edge, f.width, p.rtl), b.centerY - d * 0.06f, p.at + 0.6f, ground, p.rtl, name = "place")
            val sub = cue.label?.takeIf { cue.text.isNotBlank() } ?: Kit.say(p.rtl, "موقعیت", "Location")
            val sf = Kit.fit(fit, sub, Kit.labelVoice(look, p.typo), d * 0.1f, room, d * 0.2f)
            nodes += Kit.words(
                sub, sf, ground?.let { Kit.tint(look, look.accent, it) } ?: look.accent, Kit.fromStart(edge, sf.width, p.rtl),
                b.centerY - d * 0.06f - f.height / 2f - sf.height * 0.7f, p.at + 0.5f, ground, p.rtl, Kit.Reveal.Fade, name = "place-eyebrow",
            )
            return Kit.built(cue, look, p, b, nodes, name, listOf(Sfx(p.at + 0.65f, SfxKind.Pop, 0.7f)))
        }
    }

    /** A map pin (round head, pointed foot at the bottom centre), filling a 100 box. */
    private const val PIN = "M50 100C50 100 14 60 14 36A36 36 0 0 1 86 36C86 60 50 100 50 100Z"
    private const val RAYS = 12
    private const val PIECES = 36

    val all: List<Recipe> = listOf(Notifications, Receipt, Phone, Burst, MapPin)
}
