package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * The storyboard's cards: one interface language for a talking head, as the owner's reference edit
 * set it (docs/research/REFERENCE_EDL.md). Dark glass cards over the footage, in the band above the
 * head; flat icons in coloured circles; colour that means something — [GAIN] for a win or "us",
 * [LOSS] for a loss, money or "them", [MARK] for attention and steps, [INFO] for a chat bubble.
 *
 * A card has state: it lands with the sentence that brings it, then changes as the talk goes on —
 * tiles flip to ✗ or ✓, a versus card grows lines, a step ticks, a chip turns green. The changes are
 * the beat's [Cue.items], each landing at its [Cue.itemTimes] (when it is said), so one beat can hold
 * across several sentences and pay a setup off later.
 */
object Board {
    val GROUND = Color(0xF2141519)
    val RAISED = Color(0xFF26282F)
    val RIM = Color(0x24FFFFFF)
    val INK = Color(0xFFFFFFFF)
    val MUTED = Color(0xFFB4B4BC)
    val GAIN = Color(0xFF22C55E)
    val LOSS = Color(0xFFDC2626)
    val MARK = Color(0xFFFACC15)
    val INFO = Color(0xFF2563EB)
    val DARK = Color(0xFF111214)

    /** The colour words take on a body of [c]: near-black on the bright ones, white on the deep ones. */
    fun on(c: Color): Color = if (c == GAIN || c == MARK) DARK else INK

    /** A tone as a word says it: "gain", "loss", "info", anything else is attention. */
    fun tone(name: String?): Color = when (name?.trim()?.lowercase()) {
        "gain", "us", "up", "good", "green" -> GAIN
        "loss", "them", "down", "bad", "red", "money" -> LOSS
        "info", "blue", "chat" -> INFO
        else -> MARK
    }

    /** The same tone as text on the dark card (the deep red lightened so it reads). */
    fun ink(c: Color): Color = if (c == LOSS) Color(0xFFF87171) else if (c == INFO) Color(0xFF60A5FA) else c

    private val SHADOW = Shadow(Color.Black.copy(alpha = 0.42f), 0f, 14f, 40f)

    fun voice(weight: Int) = Look.Voice(BrandFonts.Role.Ui, weight, lineHeight = 1.22f)

    fun card(cx: Float, cy: Float, w: Float, h: Float, r: Float, fill: Color = GROUND, rim: Color? = RIM, name: String = "board-card") = ShapeNode(
        ShapeSpec.Rect(w.anim, h.anim, r.anim), fill = fill.fill(), stroke = rim?.let { Stroke(it.fill(), 2f) }, shadow = SHADOW,
        transform = Transform(x = cx.anim, y = cy.anim), name = name,
    )

    /** A rounded body whose colour turns from [from] to [to] at [at]. */
    fun turning(cx: Float, cy: Float, w: Float, h: Float, r: Float, from: Color, to: Color, at: Float, name: String) = ShapeNode(
        ShapeSpec.Rect(w.anim, h.anim, r.anim), fill = Fill.Solid(ColorAnim.tween(from, to, at, at + TURN, Easing.ExpoOut)),
        transform = Transform(x = cx.anim, y = cy.anim, scale = pulse(at)), name = name,
    )

    fun disc(cx: Float, cy: Float, d: Float, color: Color, name: String = "board-disc") =
        ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), fill = color.fill(), transform = Transform(x = cx.anim, y = cy.anim), name = name)

    /** A small scale kick when something changes (a tile flips, a step ticks): seen without moving the layout. */
    fun pulse(at: Float): Anim = anim(1f, at) { by(1.12f, 0.12f, Easing.ExpoOut); by(1f, 0.28f, Easing.Spring(0.5f, 2f)) }

    /** Words on a body ([ground]), fitted into [w] × [h]. */
    fun text(
        fit: Fitter, text: String, weight: Int, size: Float, w: Float, h: Float, color: Color, x: Float, y: Float, at: Float, rtl: Boolean, ground: Color,
        reveal: Kit.Reveal = Kit.Reveal.Fade, lines: Int = 1, align: TextAlign = TextAlign.Center, name: String = "board-text",
    ) = Kit.words(text, Kit.fit(fit, text, voice(weight), size, w, h, lines), color, x, y, at, ground, rtl, reveal, align, name)

    /** [node] seen from [from] to [to] (fading in and out quickly): one state of a card. */
    fun during(node: Node, from: Float, to: Float, name: String): Group = Group(
        listOf(node), Transform(anchorX = 0f, anchorY = 0f, opacity = anim(if (from <= 0f) 1f else 0f, from) { by(1f, FADE); hold(to - FADE); by(0f, FADE) }),
        start = from - 0.02f, end = to + 0.02f, name = name,
    )

    /** The beat's states: "tone:count:label" items, each with when it lands. */
    data class State(val tone: String, val count: Int, val label: String, val at: Float)

    fun states(cue: Cue): List<State> = cue.items.mapIndexedNotNull { i, s ->
        val parts = s.split(':', limit = 3)
        val at = cue.itemTimes.getOrNull(i)?.minus(Craft.LEAD) ?: return@mapIndexedNotNull null
        State(parts[0], parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, parts.getOrNull(2)?.trim().orEmpty(), at.coerceIn(cue.at, cue.out - FADE))
    }

    fun built(cue: Cue, look: Look, box: Box, children: List<Node>, name: String, sfx: List<Sfx> = emptyList()): Built =
        Built(listOf(Kit.stage(cue, look, Move.Rise, box, children, name, cue.at - Craft.LEAD)), emptyList(), listOf(Sfx(cue.at - Craft.LEAD, SfxKind.Pop, 0.45f)) + sfx, emptyList(), box)

    const val TURN = 0.22f
    const val FADE = 0.16f

}

/** The storyboard's cards, in [Board]'s language. */
object BoardFamilies {
    /**
     * A grid of [Cue.value] tiles (a count said aloud: "ten signals a day"), then its states: "loss:9"
     * turns nine of them red with a ✗, "gain:1" turns one green with a ✓ (the last ones). A pill under the
     * grid says what the grid is now (the beat's text, then each state's label).
     */
    val Tiles = object : Recipe("board-tiles", Kind.Element) {
        override val preferredHeight = 0.2f
        override val minHold = 2.2f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val n = (cue.value ?: 10f).toInt().coerceIn(2, MAX)
            val cols = if (n <= 6) n else (n + 1) / 2
            val rows = (n + cols - 1) / cols
            val gap = cue.width * 0.018f
            // The grid and its pill (0.62 of a tile) together fill the slot's height at most.
            val tile = minOf((cue.width * 0.78f - gap * (cols - 1)) / cols, (cue.height * 0.95f - gap * (rows + 1)) / (rows + 0.62f))
            val pillH = tile * 0.62f
            val gridW = cols * tile + (cols - 1) * gap
            val gridH = rows * tile + (rows - 1) * gap
            val box = Kit.box(cue, gridW, gridH + gap * 2f + pillH)
            val top = box.top
            val states = Board.states(cue)
            val nodes = mutableListOf<Node>()
            // Tiles in reading order (right to left in Persian); losses take the first ones, wins the last ones.
            val order = (0 until n).toList()
            val turn = mutableMapOf<Int, Pair<Color, Float>>()
            var lossFrom = 0
            var gainFrom = n - 1
            for (s in states) {
                val c = Board.tone(s.tone)
                repeat(s.count) { k ->
                    val i = if (c == Board.GAIN) gainFrom-- else lossFrom++
                    if (i in order && i !in turn) turn[i] = c to s.at + k * STAGGER
                }
            }
            for (i in order) {
                val r = i / cols
                val col = i % cols
                val cx = if (cue.rtl) box.right - (col * (tile + gap) + tile / 2f) else box.left + col * (tile + gap) + tile / 2f
                val cy = top + r * (tile + gap) + tile / 2f
                val land = cue.at - Craft.LEAD + i * APPEAR
                val (to, at) = turn[i] ?: (Board.RAISED to Float.POSITIVE_INFINITY)
                val corner = tile * 0.22f
                val body = if (at.isFinite()) Board.turning(cx, cy, tile, tile, corner, Board.RAISED, to, at, "tile-$i") else Board.card(cx, cy, tile, tile, corner, Board.RAISED, null, "tile-$i")
                val neutral = Kit.icon("chart", cx, cy, tile * 0.46f, Board.MUTED, land)
                val parts = mutableListOf<Node>(body, if (at.isFinite()) Board.during(neutral, land, at, "tile-$i-idle") else neutral)
                if (at.isFinite()) parts += Kit.icon(if (to == Board.GAIN) "check" else "close", cx, cy, tile * 0.52f, Board.on(to), at, tile * 0.012f)
                nodes += Group(parts, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, land, land + Board.FADE)), start = land - 0.02f, name = "tile-$i-group")
            }
            // The pill: the beat's text, then each state's own words in its colour.
            val pillY = top + gridH + gap * 2f + pillH / 2f
            val labels = listOf(Triple(cue.text, Board.RAISED, cue.at - Craft.LEAD)) + states.filter { it.label.isNotBlank() }.map { Triple(it.label, Board.tone(it.tone), it.at) }
            labels.forEachIndexed { k, (words, color, from) ->
                if (words.isBlank()) return@forEachIndexed
                val to = labels.getOrNull(k + 1)?.third ?: cue.out
                val f = Kit.fit(fit, words, Board.voice(WEIGHT), pillH * 0.5f, gridW * 0.8f, pillH * 0.7f)
                val w = f.width + pillH * 0.9f
                val pill = listOf(
                    Board.card(box.centerX, pillY, w, pillH, pillH / 2f, color, null, "tiles-pill-$k"),
                    Kit.words(words, f, Board.on(color), box.centerX, pillY, from, color, cue.rtl, Kit.Reveal.Fade, name = "tiles-label-$k"),
                )
                nodes += Board.during(Group(pill, Transform(anchorX = 0f, anchorY = 0f)), from, to, "tiles-state-$k")
            }
            val sfx = turn.values.map { (_, at) -> at }.sorted().take(1).map { Sfx(it, SfxKind.Tick, 0.5f) }
            return Board.built(cue, look, box, nodes, "board-tiles", sfx)
        }

        private val MAX = 12
        private val APPEAR = 0.035f
        private val STAGGER = 0.06f
        private val WEIGHT = 800
    }

    /**
     * A small pill in the top band: an icon in a coloured circle and two to five words (a name, a place, a
     * claim said in passing). The fallback card: every sentence that has nothing richer gets one.
     */
    val Pill = object : Recipe("board-pill", Kind.Element) {
        override val preferredHeight = 0.08f
        override val minHold = 1.4f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val h = minOf(cue.height, cue.width * 0.11f)
            val tone = Board.tone(cue.label)
            val f = Kit.fit(fit, cue.text, Board.voice(800), h * 0.42f, cue.width * 0.78f, h * 0.7f)
            val w = f.width + h * 1.5f
            val box = Kit.box(cue, w, h)
            val dotX = if (cue.rtl) box.right - h * 0.5f else box.left + h * 0.5f
            val textX = if (cue.rtl) box.right - h * 0.95f - f.width / 2f else box.left + h * 0.95f + f.width / 2f
            val at = cue.at - Craft.LEAD
            val nodes = listOf(
                Board.card(box.centerX, box.centerY, box.width, box.height, h / 2f),
                Board.disc(dotX, box.centerY, h * 0.62f, tone),
                Kit.icon(cue.icon ?: "spark", dotX, box.centerY, h * 0.36f, Board.on(tone), at + 0.1f),
                Kit.words(cue.text, f, Board.INK, textX, box.centerY, at + 0.08f, Board.GROUND, cue.rtl, Kit.Reveal.Fade, name = "pill-text"),
            )
            return Board.built(cue, look, box, nodes, "board-pill")
        }
    }

    /**
     * What someone else claims, as a speech bubble: «ما داریم سود می‌دیم!» with a megaphone in a yellow
     * circle; [Cue.value] stars (their rating) fill one by one under it when given.
     */
    val Quote = object : Recipe("board-quote", Kind.Element) {
        override val preferredHeight = 0.14f
        override val minHold = 1.8f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val stars = (cue.value ?: 0f).toInt().coerceIn(0, 5)
            val h = minOf(cue.height * (if (stars > 0) 0.6f else 0.9f), cue.width * 0.13f)
            val words = "«${cue.text.trim('«', '»', '"')}»"
            val f = Kit.fit(fit, words, Board.voice(800), h * 0.36f, cue.width * 0.66f, h * 0.8f, 2)
            val bubbleW = f.width + h * 0.8f
            val totalW = bubbleW + h * 1.1f
            val starH = if (stars > 0) h * 0.42f else 0f
            val box = Kit.box(cue, totalW, h + starH * 1.4f)
            val at = cue.at - Craft.LEAD
            val iconX = if (cue.rtl) box.right - h * 0.42f else box.left + h * 0.42f
            val bubbleX = if (cue.rtl) box.left + bubbleW / 2f else box.right - bubbleW / 2f
            val y = box.top + h / 2f
            val nodes = mutableListOf<Node>(
                Board.disc(iconX, y, h * 0.78f, Board.MARK),
                Kit.icon("bell", iconX, y, h * 0.42f, Board.DARK, at + 0.1f),
                Board.card(bubbleX, y, bubbleW, h * 0.86f, h * 0.43f, Color.White, null, "quote-bubble"),
                Kit.words(words, f, Board.DARK, bubbleX, y, at + 0.12f, Color.White, cue.rtl, Kit.Reveal.Rise, name = "quote-text"),
            )
            repeat(stars) { k ->
                val sx = bubbleX + (k - (stars - 1) / 2f) * starH * 1.15f * (if (cue.rtl) -1f else 1f)
                val t = at + 0.5f + k * 0.12f
                nodes += Group(
                    listOf(Kit.icon("star", sx, box.top + h + starH * 0.9f, starH, Board.MARK, t, starH * 0.1f)),
                    Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, t, t + 0.12f)), start = t - 0.02f, name = "quote-star-$k",
                )
            }
            return Board.built(cue, look, box, nodes, "board-quote")
        }
    }

    /**
     * A figure with what it is: a small eyebrow ([Cue.label], «ورودی این کانال:») over the figure ([Cue.text],
     * «۲٬۰۰۰٬۰۰۰ تومان») in its meaning's colour ([Cue.mark]: "loss" for what costs, "gain" for what pays).
     */
    val Price = object : Recipe("board-price", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 1.8f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val tone = Board.tone(cue.mark ?: "loss")
            val h = minOf(cue.height, cue.width * 0.2f)
            val eyebrow = cue.label.orEmpty()
            val fig = Kit.fit(fit, cue.text, Board.voice(900), h * 0.42f, cue.width * 0.8f, h * 0.5f)
            val eb = if (eyebrow.isNotBlank()) Kit.fit(fit, eyebrow, Board.voice(600), h * 0.2f, cue.width * 0.8f, h * 0.26f) else null
            val w = maxOf(fig.width, eb?.width ?: 0f) + h * 0.6f
            val box = Kit.box(cue, w, h)
            val at = cue.at - Craft.LEAD
            val figY = if (eb != null) box.top + h * 0.62f else box.centerY
            val nodes = mutableListOf<Node>(Board.card(box.centerX, box.centerY, box.width, box.height, h * 0.18f, Board.GROUND, tone.copy(alpha = 0.6f)))
            if (eb != null) nodes += Kit.words(eyebrow, eb, Board.MUTED, box.centerX, box.top + h * 0.24f, at, Board.GROUND, cue.rtl, Kit.Reveal.Fade, name = "price-eyebrow")
            nodes += Kit.words(cue.text, fig, Board.ink(tone), box.centerX, figY, at + 0.12f, Board.GROUND, cue.rtl, Kit.Reveal.Pop, name = "price-figure")
            return Board.built(cue, look, box, nodes, "board-price")
        }
    }

    /**
     * Us against them: two cards with a yellow VS between them. [Cue.items] start with the two sides,
     * "tone:who|figure|what" («gain:ما|۵۰ تتر|کف ورود», «loss:اونا|۲٬۷۰۰٬۰۰۰ ت|حق عضویت»); every item
     * after them is a line the card grows under it when it is said («gain:شارژ حساب خودت»).
     */
    val Versus = object : Recipe("board-versus", Kind.Element) {
        override val preferredHeight = 0.2f
        override val minHold = 3f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val sides = cue.items.take(2).map { it.split(':', limit = 2) }.map { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "").split('|') }
            if (sides.size < 2) return Built(emptyList())
            val extras = cue.items.drop(2).mapIndexed { i, s ->
                val p = s.split(':', limit = 2)
                Triple(p[0], p.getOrNull(1).orEmpty(), (cue.itemTimes.getOrNull(i + 2)?.minus(Craft.LEAD) ?: (cue.at + 1f + i)).coerceIn(cue.at, cue.out - Board.FADE))
            }
            val w = cue.width
            val cardW = w * 0.4f
            val cardH = cardW * 0.62f
            val rowH = cardH * 0.3f
            val box = Kit.box(cue, w * 0.9f, cardH + extras.size * rowH * 1.15f + (if (extras.isEmpty()) 0f else rowH * 0.3f))
            val k = box.width / (w * 0.9f)
            val cw = cardW * k
            val ch = cardH * k
            val rh = rowH * k
            val at = cue.at - Craft.LEAD
            val cy = box.top + ch / 2f
            val nodes = mutableListOf<Node>()
            sides.forEachIndexed { i, (tone, parts) ->
                val c = Board.tone(tone)
                // The first side reads first: on the right in Persian.
                val right = (i == 0) == cue.rtl
                val cx = if (right) box.right - cw / 2f else box.left + cw / 2f
                val t = at + i * 0.25f
                val who = parts.getOrNull(0).orEmpty()
                val figure = parts.getOrNull(1).orEmpty()
                val what = parts.getOrNull(2).orEmpty()
                val card = mutableListOf<Node>(Board.card(cx, cy, cw, ch, ch * 0.14f, Board.GROUND, c.copy(alpha = 0.7f), "versus-card-$i"))
                if (who.isNotBlank()) card += Board.text(fit, who, 800, ch * 0.15f, cw * 0.8f, ch * 0.2f, Board.ink(c), cx, cy - ch * 0.3f, t, cue.rtl, Board.GROUND, name = "versus-who-$i")
                if (figure.isNotBlank()) {
                    card += Board.text(fit, figure, 900, ch * 0.22f, cw * 0.86f, ch * 0.3f, Board.ink(c), cx, cy, t + 0.1f, cue.rtl, Board.GROUND, Kit.Reveal.Pop, name = "versus-figure-$i")
                }
                if (what.isNotBlank()) card += Board.text(fit, what, 600, ch * 0.12f, cw * 0.8f, ch * 0.18f, Board.MUTED, cx, cy + ch * 0.3f, t + 0.15f, cue.rtl, Board.GROUND, name = "versus-what-$i")
                nodes += Group(card, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, t, t + Board.FADE)), start = t - 0.02f, name = "versus-side-$i")
            }
            val vs = ch * 0.34f
            nodes += Board.disc(box.centerX, cy, vs, Board.MARK, "versus-disc")
            nodes += Board.text(fit, "VS", 900, vs * 0.4f, vs * 0.8f, vs * 0.6f, Board.DARK, box.centerX, cy, at + 0.3f, false, Board.MARK, Kit.Reveal.Pop, name = "versus-vs")
            extras.forEachIndexed { i, (tone, words, t) ->
                val c = Board.tone(tone)
                val y = box.top + ch + rh * 0.3f + rh * 1.15f * i + rh / 2f
                val f = Kit.fit(fit, words, Board.voice(700), rh * 0.48f, box.width * 0.8f, rh * 0.7f)
                val rw = f.width + rh * 1.6f
                val dotX = if (cue.rtl) box.centerX + rw / 2f - rh * 0.5f else box.centerX - rw / 2f + rh * 0.5f
                val textX = if (cue.rtl) dotX - rh * 0.45f - f.width / 2f else dotX + rh * 0.45f + f.width / 2f
                val row = listOf(
                    Board.card(box.centerX, y, rw, rh, rh / 2f, Board.GROUND, null, "versus-row-$i"),
                    Board.disc(dotX, y, rh * 0.62f, c),
                    Kit.icon(if (c == Board.LOSS) "close" else "check", dotX, y, rh * 0.36f, Board.on(c), t + 0.08f),
                    Kit.words(words, f, Board.INK, textX, y, t + 0.05f, Board.GROUND, cue.rtl, Kit.Reveal.Fade, name = "versus-row-text-$i"),
                )
                nodes += Group(row, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, t, t + Board.FADE)), start = t - 0.02f, name = "versus-line-$i")
            }
            val sfx = extras.map { Sfx(it.third, SfxKind.Pop, 0.35f) }
            return Board.built(cue, look, box, nodes, "board-versus", sfx)
        }
    }

    /**
     * The ask as steps: up to four [Cue.items] («کامنت «کانال»», «چک دایرکت», «سایت، کانال، ربات») on a
     * dark bar; each step ticks green when it is said ([Cue.itemTimes]), the line to it fills green, and
     * the step being said is a yellow circle with its number.
     */
    val Tracker = object : Recipe("board-steps", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 2.4f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val steps = Kit.items(cue, MAX)
            if (steps.isEmpty()) return Built(emptyList())
            val h = minOf(cue.height, cue.width * 0.2f)
            val box = Kit.box(cue, cue.width * 0.94f, h)
            val at = cue.at - Craft.LEAD
            val n = steps.size
            val span = box.width / n
            val d = h * 0.36f
            val dotY = box.top + h * 0.36f
            val labelY = box.top + h * 0.76f
            val times = steps.indices.map { i -> Kit.itemAt(cue, i, at + 0.3f, 0.6f).coerceAtMost(cue.out - Board.FADE) }
            val xs = steps.indices.map { i -> if (cue.rtl) box.right - span * (i + 0.5f) else box.left + span * (i + 0.5f) }
            val nodes = mutableListOf<Node>(Board.card(box.centerX, box.centerY, box.width, box.height, h * 0.24f))
            for (i in 0 until n - 1) {
                nodes += Kit.line(xs[i], dotY, xs[i + 1], dotY, Board.RAISED, h * 0.04f, at, 0.01f, "steps-track-$i")
                nodes += Kit.line(xs[i], dotY, xs[i + 1], dotY, Board.GAIN, h * 0.04f, times[i + 1] - 0.3f, 0.3f, "steps-fill-$i")
            }
            steps.forEachIndexed { i, label ->
                val x = xs[i]
                val now = times[i]
                // Waiting: a dark numbered circle; being said: yellow; done (the next one is said): green with a tick.
                val done = times.getOrNull(i + 1)
                val color = ColorAnim.keys(
                    Triple(at, Board.RAISED, Easing.Hold), Triple(now, Board.MARK, Easing.ExpoOut),
                    *(done?.let { arrayOf(Triple(it, Board.GAIN, Easing.ExpoOut)) } ?: emptyArray()),
                )
                nodes += ShapeNode(ShapeSpec.Ellipse(d.anim, d.anim), fill = Fill.Solid(color), transform = Transform(x = x.anim, y = dotY.anim, scale = Board.pulse(now)), name = "steps-dot-$i")
                val digit = Kit.digits("${i + 1}", cue.rtl)
                val number = Board.text(fit, digit, 900, d * 0.5f, d * 0.8f, d * 0.7f, Board.INK, x, dotY, at, cue.rtl, Board.RAISED, Kit.Reveal.None, name = "steps-number-$i")
                nodes += if (done != null) Board.during(number, at, done, "steps-number-$i-on") else number
                if (done != null) nodes += Kit.icon("check", x, dotY, d * 0.56f, Board.DARK, done, d * 0.05f)
                nodes += Board.text(fit, label, 700, h * 0.15f, span * 0.92f, h * 0.24f, Board.INK, x, labelY, at + 0.1f, cue.rtl, Board.GROUND, name = "steps-label-$i")
            }
            val sfx = times.drop(1).map { Sfx(it, SfxKind.Tick, 0.45f) }
            return Board.built(cue, look, box, nodes, "board-steps", sfx)
        }

        private val MAX = 4
    }

    /**
     * Up to three chips that tick green as each is said («پیج ما ✓», «سایت ما ✓»), and a line under them
     * ([Cue.label], «شفاف‌ترین در کامیونیتی فارسی») when the claim they add up to is said ([Cue.value]: when).
     */
    val Chips = object : Recipe("board-chips", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 2f

        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val chips = Kit.items(cue, MAX)
            if (chips.isEmpty()) return Built(emptyList())
            val h = minOf(cue.height * 0.45f, cue.width * 0.1f)
            val fitted = Kit.uniform(fit, chips, Board.voice(800), h * 0.42f, cue.width * 0.3f, h * 0.7f)
            val widths = fitted.map { it.width + h * 1.5f }
            val gap = h * 0.35f
            val rowW = widths.sum() + gap * (chips.size - 1)
            val claim = cue.label?.takeIf { it.isNotBlank() }
            val box = Kit.box(cue, maxOf(rowW, cue.width * 0.6f), h * (if (claim != null) 2.2f else 1f))
            val at = cue.at - Craft.LEAD
            val y = box.top + h / 2f
            var edge = box.centerX + (if (cue.rtl) rowW / 2f else -rowW / 2f)
            val nodes = mutableListOf<Node>()
            chips.forEachIndexed { i, chip ->
                val w = widths[i]
                val cx = if (cue.rtl) edge - w / 2f else edge + w / 2f
                edge += if (cue.rtl) -(w + gap) else (w + gap)
                val t = Kit.itemAt(cue, i, at, 0.5f).coerceAtMost(cue.out - Board.FADE)
                val dotX = if (cue.rtl) cx - w / 2f + h * 0.5f else cx + w / 2f - h * 0.5f
                val textX = if (cue.rtl) cx + w / 2f - h * 0.35f - fitted[i].width / 2f else cx - w / 2f + h * 0.35f + fitted[i].width / 2f
                val chipNodes = listOf(
                    Board.card(cx, y, w, h, h / 2f, Board.GROUND, Board.RIM, "chip-$i"),
                    Kit.words(chip, fitted[i], Board.INK, textX, y, t, Board.GROUND, cue.rtl, Kit.Reveal.Fade, name = "chip-text-$i"),
                    Board.turning(dotX, y, h * 0.62f, h * 0.62f, h * 0.31f, Board.RAISED, Board.GAIN, t + TICK, "chip-dot-$i"),
                    Kit.icon("check", dotX, y, h * 0.36f, Board.DARK, t + TICK),
                )
                nodes += Group(chipNodes, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, t, t + Board.FADE)), start = t - 0.02f, name = "chip-group-$i")
            }
            if (claim != null) {
                val t = (cue.value ?: (at + 1f + chips.size * 0.5f)).coerceIn(at, cue.out - Board.FADE)
                val f = Kit.fit(fit, claim, Board.voice(700), h * 0.36f, box.width * 0.8f, h * 0.6f)
                val w = f.width + h * 1.4f
                val cy = box.top + h * 1.65f
                val dotX = if (cue.rtl) box.centerX + w / 2f - h * 0.42f else box.centerX - w / 2f + h * 0.42f
                val textX = if (cue.rtl) dotX - h * 0.38f - f.width / 2f else dotX + h * 0.38f + f.width / 2f
                val line = listOf(
                    Board.card(box.centerX, cy, w, h * 0.8f, h * 0.4f, Board.GROUND, null, "chips-claim"),
                    Board.disc(dotX, cy, h * 0.52f, Board.MARK),
                    Kit.icon("star", dotX, cy, h * 0.3f, Board.DARK, t + 0.08f),
                    Kit.words(claim, f, Board.INK, textX, cy, t + 0.05f, Board.GROUND, cue.rtl, Kit.Reveal.Fade, name = "chips-claim-text"),
                )
                nodes += Group(line, Transform(anchorX = 0f, anchorY = 0f, opacity = Anim.tween(0f, 1f, t, t + Board.FADE)), start = t - 0.02f, name = "chips-claim-group")
            }
            return Board.built(cue, look, box, nodes, "board-chips")
        }

        private val MAX = 3
        private val TICK = 0.35f
    }

    val all: List<Recipe> get() = listOf(Tiles, Pill, Quote, Price, Versus, Tracker, Chips)
}
