package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * B-roll that simulates interfaces for tech and product talk, so a line about "the app", "the
 * code", "the dashboard" or "the site" gets a picture instead of the speaker again: a feed
 * scrolling, an editor typing, a dashboard animating, notifications stacking, a page loading,
 * and an icon scene drawing itself. Every surface is generic — no real product's chrome, colours
 * or layout — and all text sits on its own body with designed contrast. On a shader stage the
 * devices sit on frosted glass ([Stages.glass]).
 */
object UiRecipes {

    /** The interface's own colours: dark looks get a dark UI, light looks a light one. */
    private class Skin(val surface: Color, val raised: Color, val ink: Color, val muted: Color, val line: Color, val dark: Boolean)

    private fun skin(look: Look, cue: Cue): Skin {
        val dark = luminance(look.canvas) < 0.4f
        // Over footage the surface is opaque; on a stage it lets the frosted glass through a little.
        val a = if (cue.overMedia || Stages.shader(look) == null) 0.97f else 0.88f
        return if (dark) {
            Skin(Color(0xFF15161A).copy(alpha = a), Color(0xFF22242A), Color(0xFFF2F2F4), Color(0xFF9A9CA6), Color(0x24FFFFFF), true)
        } else {
            Skin(Color(0xFFFFFFFF).copy(alpha = a), Color(0xFFF0EFEA), Color(0xFF111111), Color(0xFF66655F), Color(0x1F111111), false)
        }
    }

    /** [c] pulled toward [ink] until it reads at 4.5:1 on [bg] (a lime keyword on white turns olive). */
    private fun readable(c: Color, bg: Color, ink: Color): Color {
        var k = 0f
        var out = c
        while (k < 1f && ratio(out, bg) < MIN_CONTRAST) {
            k += 0.1f
            out = lerp(c, ink, k)
        }
        return out
    }

    private fun luminance(c: Color): Float {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
    }

    private fun ratio(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /** The device: lifts in tilted back (3D), lands, and leaves with the house exit at [Cue.out]. */
    private fun enter(cue: Cue, at: Float) = Transform(
        x = cue.x.anim, y = cue.y.anim,
        scale = Anim.tween(0.9f, 1f, at, at + 0.6f, Easing.Land),
        opacity = Anim.tween(0f, 1f, at, at + 0.16f),
        rotationX = Anim.tween(22f, 0f, at, at + 0.65f, Easing.ExpoOut),
    )

    private fun group(cue: Cue, children: List<Node>, name: String): Group {
        val exitAt = cue.out - 0.28f
        return Group(
            children,
            transform = Transform(
                anchorX = 0f, anchorY = 0f,
                y = io.trimio.engine.motion.anim(0f, exitAt) { by(-28f, 0.28f, Easing.Exit) },
                opacity = io.trimio.engine.motion.anim(1f, exitAt) { by(0f, 0.28f, Easing.Exit) },
            ),
            start = cue.at - 0.2f, end = cue.out, name = name,
        )
    }

    /**
     * A device body ([w] × [h], centred on the cue) with everything inside it in one group that
     * shares its entrance, on frosted glass on a shader stage.
     */
    private fun device(cue: Cue, look: Look, skin: Skin, at: Float, w: Float, h: Float, radius: Float, inside: List<Node>, name: String): List<Node> {
        val e = enter(cue, at)
        val body = ShapeNode(
            ShapeSpec.Rect(w.anim, h.anim, radius.anim), fill = skin.surface.fill(), stroke = Stroke(skin.line.fill(), 2f),
            shadow = Shadow(Color.Black.copy(alpha = if (skin.dark) 0.45f else 0.18f), 0f, 22f, 48f),
            transform = Transform(x = (w / 2f).anim, y = (h / 2f).anim), name = "$name-body",
        )
        val pad = w * GLASS_PAD
        val glass = Stages.glass(look, cue, cue.x - w / 2f - pad, cue.y - h / 2f - pad, w + pad * 2f, h + pad * 2f, radius + pad, e.opacity)
        val unit = Group(
            listOf(body) + inside,
            transform = e.copy(anchorX = 0.5f, anchorY = 0.5f), width = w, height = h, name = name,
        )
        return listOfNotNull(glass, unit)
    }

    private fun text(s: String, f: Fitted, color: Color, x: Float, y: Float, rtl: Boolean, animators: List<TextAnimator> = emptyList(), name: String) =
        Craft.onBody(
            TextNode(
                s, f.type, color.fill(), rtl = rtl, align = if (rtl) TextAlign.End else TextAlign.Start, animators = animators,
                transform = Transform(x = x.anim, y = y.anim), name = name,
            ),
        )

    /** A skeleton line (placeholder text) [w] wide, [h] tall, its top-left at [x], [y]. */
    private fun bar(x: Float, y: Float, w: Float, h: Float, color: Color, opacity: Anim = Anim.One, name: String = "bar") = ShapeNode(
        ShapeSpec.Rect(w.anim, h.anim, (h / 2f).anim), fill = color.fill(),
        transform = Transform(x = (x + w / 2f).anim, y = (y + h / 2f).anim, opacity = opacity), name = name,
    )

    /** A line icon centred at [x], [y], [size] px, drawing on from [at] over [draw] seconds. */
    private fun icon(name: String, x: Float, y: Float, size: Float, color: Color, at: Float, draw: Float = 0.5f, width: Float = size * 0.085f): ShapeNode? {
        val d = Icons.path(name) ?: return null
        return ShapeNode(
            ShapeSpec.Path(d, Icons.VIEWPORT, size), stroke = Stroke(color.fill(), width),
            trimEnd = Anim.tween(0f, 1f, at, at + draw, Easing.Move), transform = Transform(x = x.anim, y = y.anim), name = "icon-$name",
        )
    }

    private fun persian(s: String) = s.any { it in '\u0600'..'\u06FF' }

    /** A type size, never under what a phone reads (the jury's typography floor is 23 px). */
    private fun px(size: Float) = maxOf(size, MIN_TEXT)

    // ------------------------------------------------------------------ feed

    /**
     * A generic app screen: an app bar and a feed of posts (avatar, a line of text, a picture
     * on every other post) that the thumb flicks up twice; a like pops on the first picture.
     * [Cue.items] are the posts' lines (else skeleton lines), [Cue.label] the app bar's title.
     */
    val Feed = object : Recipe("ui-feed", Kind.Element) {
        override val preferredHeight = 0.5f
        override val minHold = 2.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val sk = skin(look, cue)
            val h = minOf(cue.height * 0.98f, cue.width * 1.7f)
            val w = minOf(h * 0.56f, cue.width * 0.8f)
            val rtl = cue.rtl
            val pad = w * 0.06f
            val barH = w * 0.2f
            val inside = mutableListOf<Node>()
            // App bar: a title (or a placeholder), and a round avatar on the other side.
            val titleF = cue.label?.let { fit.fit(it, look.headline, px(w * 0.07f), w * 0.6f, barH * 0.5f, 1) }
            if (titleF != null) {
                inside += text(cue.label!!, titleF, sk.ink, if (rtl) w - pad - titleF.width / 2f else pad + titleF.width / 2f, barH * 0.6f, persian(cue.label!!), name = "feed-title")
            } else {
                inside += bar(if (rtl) w - pad - w * 0.35f else pad, barH * 0.5f, w * 0.35f, w * 0.04f, sk.ink.copy(alpha = 0.7f))
            }
            inside += ShapeNode(
                ShapeSpec.Ellipse((w * 0.08f).anim, (w * 0.08f).anim), fill = Fill.Linear(listOf(look.accent, look.hot), 45f.anim),
                transform = Transform(x = (if (rtl) pad + w * 0.04f else w - pad - w * 0.04f).anim, y = (barH * 0.6f).anim), name = "feed-avatar",
            )
            inside += ShapeNode(ShapeSpec.Rect(w.anim, 2f.anim), fill = sk.line.fill(), transform = Transform(x = (w / 2f).anim, y = barH.anim))
            // The feed, scrolling inside the screen below the app bar.
            val rows = mutableListOf<Node>()
            var y = barH + pad * 0.6f
            val tones = listOf(look.accent, look.hot, look.positive)
            var likeAt: Pair<Float, Float>? = null
            for (i in 0 until FEED_ROWS) {
                val av = w * 0.11f
                val ax = if (rtl) w - pad - av / 2f else pad + av / 2f
                rows += ShapeNode(
                    ShapeSpec.Ellipse(av.anim, av.anim), fill = Fill.Linear(listOf(tones[i % 3], tones[(i + 1) % 3]), 30f.anim),
                    transform = Transform(x = ax.anim, y = (y + av / 2f).anim), name = "post-avatar",
                )
                val tx = if (rtl) pad else pad + av + pad * 0.6f
                val tw = w - pad * 2.6f - av
                val line = cue.items.getOrNull(i)
                if (line != null) {
                    // Phone-readable posts: at most a few words, never under the floor size.
                    val post = line.split(' ').take(POST_WORDS).joinToString(" ")
                    val f = fit.fit(post, look.body.copy(weight = minOf(look.body.weight, 700)), px(w * 0.062f), tw, av * 2.4f, 3)
                    rows += text(post, f, sk.ink, if (rtl) tx + tw - f.width / 2f else tx + f.width / 2f, y + f.height / 2f, persian(line), name = "post-text")
                    y += maxOf(av, f.height) + pad * 0.5f
                } else {
                    rows += bar(if (rtl) tx + tw * 0.3f else tx, y + av * 0.12f, tw * 0.7f, w * 0.032f, sk.ink.copy(alpha = 0.55f))
                    rows += bar(if (rtl) tx + tw * 0.55f else tx, y + av * 0.6f, tw * 0.45f, w * 0.028f, sk.muted.copy(alpha = 0.6f))
                    y += av + pad * 0.5f
                }
                if (i % 2 == 0) {
                    val ph = w * 0.5f
                    rows += ShapeNode(
                        ShapeSpec.Rect((w - pad * 2).anim, ph.anim, (w * 0.04f).anim),
                        fill = Fill.Linear(listOf(lerp(sk.raised, tones[i % 3], 0.55f), lerp(sk.raised, tones[(i + 2) % 3], 0.35f)), 120f.anim),
                        transform = Transform(x = (w / 2f).anim, y = (y + ph / 2f).anim), name = "post-picture",
                    )
                    if (likeAt == null) likeAt = (if (rtl) pad * 2.2f else w - pad * 2.2f) to (y + ph - pad * 1.2f)
                    y += ph + pad * 0.5f
                }
                rows += ShapeNode(ShapeSpec.Rect((w - pad * 2).anim, 1.5f.anim), fill = sk.line.fill(), transform = Transform(x = (w / 2f).anim, y = (y + pad * 0.2f).anim))
                y += pad * 0.8f
            }
            val view = h - barH
            val travel = (y - h).coerceAtLeast(0f)
            val flick1 = at + 0.9f
            val flick2 = maxOf(flick1 + 1.2f, minOf(cue.out - 1.2f, at + 2.4f))
            val scroll = Anim.keys(
                Anim.Key(flick1, 0f, Easing.Hold), Anim.Key(flick1 + 0.75f, -travel * 0.3f, Easing.Move),
                Anim.Key(flick2, -travel * 0.3f, Easing.Hold), Anim.Key(flick2 + 0.8f, -travel * 0.6f, Easing.Move),
            )
            likeAt?.let { (lx, ly) ->
                val pop = at + 0.55f
                Icons.path("heart")?.let { d ->
                    rows += ShapeNode(
                        ShapeSpec.Path(d, Icons.VIEWPORT, w * 0.1f), fill = look.hot.fill(), stroke = Stroke(Color.White.fill(), w * 0.006f),
                        transform = Transform(
                            x = lx.anim, y = ly.anim,
                            scale = io.trimio.engine.motion.anim(0f, pop) { by(1.25f, 0.18f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) },
                        ),
                        name = "feed-like",
                    )
                }
            }
            val screen = ShapeNode(ShapeSpec.Rect(w.anim, view.anim, (w * 0.09f).anim), fill = Color.White.fill(), transform = Transform(x = (w / 2f).anim, y = (barH + view / 2f).anim))
            inside += Group(listOf(Group(rows, transform = Transform(anchorX = 0f, anchorY = 0f, y = scroll), name = "feed-rows")), mask = Mask(screen), name = "feed-screen")
            val nodes = device(cue, look, sk, at, w, h, w * 0.09f, inside, "ui-feed")
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(flick1 + 0.2f, 0.01f)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(at + 0.55f, SfxKind.Pop, 0.45f), Sfx(flick1, SfxKind.Swish, 0.35f), Sfx(flick2, SfxKind.Swish, 0.35f)),
            )
        }
    }

    // ------------------------------------------------------------------ code

    private val KEYWORDS = setOf(
        "fun", "val", "var", "if", "else", "return", "for", "in", "while", "class", "const", "let", "def", "import", "from", "async", "await", "new", "true", "false",
    )

    /** One coloured piece of a code line. */
    private class Token(val text: String, val kind: Char)

    /** Splits a code line into keyword (k), string (s), number (n), comment (c) and plain (p) tokens, spaces kept with the next one. */
    private fun tokens(line: String): List<Token> {
        val out = mutableListOf<Token>()
        val c = line.indexOf("//").takeIf { it >= 0 }
        val code = if (c != null) line.substring(0, c) else line
        val re = Regex("\\s*(\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*|[0-9][0-9_.]*|.)")
        for (m in re.findAll(code)) {
            val t = m.value
            val core = t.trim()
            val kind = when {
                core.startsWith('"') -> 's'
                core in KEYWORDS -> 'k'
                core.firstOrNull()?.isDigit() == true -> 'n'
                else -> 'p'
            }
            if (kind == 'p' && out.lastOrNull()?.kind == 'p') out[out.lastIndex] = Token(out.last().text + t, 'p') else out += Token(t, kind)
        }
        if (c != null) out += Token(line.substring(c), 'c')
        return out
    }

    private val DEFAULT_CODE = listOf(
        "// ship it",
        "fun launch(app: App) {",
        "    val users = app.signups()",
        "    if (users > 1000) notify(\"full\")",
        "    return deploy(app, \"prod\")",
        "}",
    )

    /**
     * A code editor: a tab with a file name, a gutter of line numbers and code typed in at a
     * fast, even pace with syntax colours (keywords in the accent, strings and numbers in the
     * look's second voices), a caret that follows, and a green check when it builds.
     * [Cue.items] are the code lines (else a small generic function), [Cue.label] the file name.
     */
    val Code = object : Recipe("ui-code", Kind.Element) {
        override val preferredHeight = 0.42f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val sk = skin(look, cue)
            val lines = cue.items.filter { it.isNotBlank() }.ifEmpty { DEFAULT_CODE }.take(MAX_CODE_LINES)
            // The editor keeps its proportions: a short slot makes it smaller, never squashed.
            val w = minOf(cue.width * 0.94f, cue.height / (0.09f + 0.066f * (lines.size + 1.6f)))
            val tabH = w * 0.09f
            val lineH = w * 0.066f
            val h = tabH + lineH * (lines.size + 1.6f)
            val size = px(minOf(w * 0.038f, (h - tabH) / (lines.size + 1.6f) * 0.58f))
            val mono = look.label.copy(weight = 600, tracking = 0f)
            val pad = w * 0.04f
            val gutter = w * 0.085f
            val bg = if (sk.dark) Color(0xFF15161A) else Color.White
            val colors = mapOf(
                'k' to readable(look.accent, bg, sk.ink), 's' to readable(look.positive.takeIf { it != look.accent } ?: look.hot, bg, sk.ink),
                'n' to readable(look.hot, bg, sk.ink), 'c' to sk.muted, 'p' to sk.ink,
            )
            val inside = mutableListOf<Node>()
            // Tab bar: one tab with the file name and an accent underline.
            val file = cue.label?.takeIf { it.isNotBlank() } ?: "main.kt"
            val ff = fit.fit(file, mono, size * 0.9f, w * 0.5f, tabH * 0.6f, 1)
            inside += ShapeNode(ShapeSpec.Rect(w.anim, 2f.anim), fill = sk.line.fill(), transform = Transform(x = (w / 2f).anim, y = tabH.anim))
            inside += text(file, ff, sk.ink, pad + ff.width / 2f, tabH * 0.52f, false, name = "code-file")
            inside += ShapeNode(
                ShapeSpec.Rect((ff.width + pad).anim, (w * 0.006f).anim), fill = look.accent.fill(),
                transform = Transform(x = (pad + ff.width / 2f).anim, y = (tabH - w * 0.004f).anim, scaleX = Anim.tween(0f, 1f, at + 0.2f, at + 0.6f, Easing.ExpoOut)),
            )
            val space = fit.fit("x x", mono, size, w, lineH, 1).width - fit.fit("xx", mono, size, w, lineH, 1).width
            val chars = lines.sumOf { it.length }.coerceAtLeast(1)
            val typing = ((cue.out - at - 1.6f).coerceAtLeast(0.8f)).coerceAtMost(chars / MIN_CPS)
            val cps = chars / typing
            var t = at + 0.45f
            val caretKeys = mutableListOf<Anim.Key>()
            val caretY = mutableListOf<Anim.Key>()
            for ((i, line) in lines.withIndex()) {
                val y = tabH + lineH * (i + 0.9f)
                val num = "${i + 1}"
                val nf = fit.fit(num, mono, size, gutter, lineH, 1)
                inside += text(num, nf, sk.muted, gutter - pad * 0.4f - nf.width / 2f, y, false, name = "code-gutter")
                var x = gutter + pad * 0.3f
                caretY += Anim.Key(t, y, Easing.Hold)
                for (tok in tokens(line)) {
                    val lead = tok.text.length - tok.text.trimStart().length
                    val core = tok.text.trimStart()
                    x += lead * space
                    t += lead / cps
                    if (core.isEmpty()) continue
                    val f = fit.fit(core, mono, size, w * 2f, lineH, 1)
                    val start = t
                    val typed = TextAnimator(TextUnit.Char, UnitState(opacity = 0f), at = start, duration = 0.01f, stagger = 1f / cps, ease = Easing.Hold)
                    inside += text(core, f.copy(type = f.type.copy(size = size)), colors.getValue(tok.kind), x + f.width / 2f, y, false, listOf(typed), "code-token")
                    x += f.width
                    t += core.length / cps
                    caretKeys += Anim.Key(t, x + size * 0.1f, Easing.Linear)
                }
                t += LINE_PAUSE
                caretKeys += Anim.Key(t, gutter + pad * 0.3f, Easing.Hold)
            }
            val done = t
            inside += ShapeNode(
                ShapeSpec.Rect((size * 0.12f).anim, (size * 1.1f).anim), fill = look.accent.fill(),
                transform = Transform(
                    x = Anim.keys(listOf(Anim.Key(at, gutter + pad * 0.3f, Easing.Hold)) + caretKeys), y = Anim.keys(caretY),
                    opacity = Anim.keys((0 until 24).map { Anim.Key(done + it * 0.26f, if (it % 2 == 0) 1f else 0f, Easing.Hold) }),
                ),
                start = at + 0.4f, name = "code-caret",
            )
            // The build passes: a check in a positive disc in the corner.
            val ok = readable(look.positive, bg, sk.ink)
            val r = tabH * 0.62f
            inside += ShapeNode(
                ShapeSpec.Ellipse(r.anim, r.anim), fill = ok.fill(),
                transform = Transform(
                    x = (w - pad - r / 2f).anim, y = (tabH * 0.5f).anim,
                    scale = io.trimio.engine.motion.anim(0f, done + 0.1f) { by(1.15f, 0.2f, Easing.ExpoOut); by(1f, 0.3f, Easing.Land) },
                ),
                name = "code-ok",
            )
            icon("check", w - pad - r / 2f, tabH * 0.5f, r * 0.62f, if (luminance(ok) > 0.4f) Color(0xFF111111) else Color.White, done + 0.2f, 0.3f, r * 0.08f)?.let { inside += it }
            val nodes = device(cue, look, sk, at, w, h, w * 0.035f * maxOf(look.roundness, 0.4f), inside, "ui-code")
            return Built(
                listOf(group(cue, nodes, name)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(at + 0.45f, SfxKind.Tick, 0.35f), Sfx(done + 0.1f, SfxKind.Pop, 0.7f)),
            )
        }
    }

    // ------------------------------------------------------------------ dashboard

    /**
     * An analytics dashboard: three KPI tiles counting up, a trend line drawing across with its
     * last point pulsing, bars rising in a wave and a ring filling. [Cue.value] leads the first
     * tile, [Cue.points] are the trend, [Cue.items] the tiles' labels, [Cue.label] the title.
     */
    val Dashboard = object : Recipe("ui-dashboard", Kind.Element) {
        override val preferredHeight = 0.5f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val sk = skin(look, cue)
            val w = minOf(cue.width * 0.94f, cue.height / DASH_ASPECT)
            val h = w * DASH_ASPECT
            val pad = w * 0.045f
            val rtl = cue.rtl
            val bg = if (sk.dark) Color(0xFF15161A) else Color.White
            val up = readable(look.positive, bg, sk.ink)
            val accent = readable(look.accent, bg, sk.ink)
            val inside = mutableListOf<Node>()
            val head = h * 0.1f
            val title = cue.label ?: cue.text.takeIf { it.isNotBlank() && it.length <= MAX_TITLE }
            if (title != null) {
                val tf = fit.fit(title, look.headline, px(w * 0.055f), w * 0.6f, head * 0.6f, 1)
                inside += text(title, tf, sk.ink, if (rtl) w - pad - tf.width / 2f else pad + tf.width / 2f, head * 0.6f, persian(title), name = "dash-title")
            } else {
                inside += bar(if (rtl) w - pad - w * 0.3f else pad, head * 0.45f, w * 0.3f, w * 0.03f, sk.ink.copy(alpha = 0.7f))
            }
            inside += bar(if (rtl) pad else w - pad - w * 0.18f, head * 0.4f, w * 0.18f, w * 0.045f, sk.raised)
            // KPI tiles.
            val tileW = (w - pad * 4f) / 3f
            val tileH = h * 0.2f
            val tileY = head + pad * 0.3f
            val lead = cue.value ?: 12480f
            val values = listOf(lead, (lead * 0.31f).coerceAtLeast(1f), 68f)
            for (i in 0 until 3) {
                val k = if (rtl) 2 - i else i
                val tx = pad + k * (tileW + pad)
                val t0 = at + 0.35f + i * 0.12f
                inside += ShapeNode(
                    ShapeSpec.Rect(tileW.anim, tileH.anim, (w * 0.025f).anim), fill = sk.raised.fill(),
                    transform = Transform(x = (tx + tileW / 2f).anim, y = (tileY + tileH / 2f).anim, opacity = Anim.tween(0f, 1f, t0, t0 + 0.25f)), name = "dash-tile",
                )
                val label = cue.items.getOrNull(i)
                if (label != null) {
                    val lf = fit.fit(label, look.label, px(w * 0.034f), tileW * 0.86f, tileH * 0.3f, 1)
                    inside += text(label, lf, sk.muted, tx + tileW / 2f, tileY + tileH * 0.27f, persian(label), name = "dash-label")
                } else {
                    inside += bar(tx + tileW * 0.2f, tileY + tileH * 0.22f, tileW * 0.6f, w * 0.022f, sk.muted.copy(alpha = 0.5f))
                }
                val suffix = if (i == 2) (if (rtl) "٪" else "%") else if (i == 0) cue.suffix else ""
                val nf = fit.fit("${values[i].toInt()}$suffix", look.number, w * 0.065f, tileW * 0.86f, tileH * 0.45f, 1)
                inside += Craft.onBody(CounterNode(
                    value = Anim.tween(0f, values[i], t0 + 0.1f, t0 + 1.3f, Easing.ExpoOut), decimals = 0, prefix = if (i == 0) cue.prefix else "", suffix = suffix,
                    persianDigits = rtl, type = nf.type, fill = (if (i == 1) accent else sk.ink).fill(),
                    transform = Transform(x = (tx + tileW / 2f).anim, y = (tileY + tileH * 0.64f).anim, opacity = Anim.tween(0f, 1f, t0 + 0.1f, t0 + 0.3f)), name = "dash-kpi",
                ))
            }
            // Trend panel.
            val panelY = tileY + tileH + pad * 0.6f
            val panelH = h * 0.32f
            inside += ShapeNode(
                ShapeSpec.Rect((w - pad * 2f).anim, panelH.anim, (w * 0.025f).anim), fill = sk.raised.fill(),
                transform = Transform(x = (w / 2f).anim, y = (panelY + panelH / 2f).anim), name = "dash-panel",
            )
            val pts = cue.points?.takeIf { it.size >= 3 } ?: listOf(3f, 4.2f, 3.8f, 5.1f, 4.9f, 6.4f, 6.1f, 7.6f, 8.9f)
            val lo = pts.min()
            val hi = pts.max()
            val cw = w - pad * 4f
            val ch = panelH * 0.62f
            // Time runs left to right even in Persian: a rising trend must look like one.
            val xy = pts.mapIndexed { i, v -> cw * i / (pts.size - 1f) to ch * (1f - (v - lo) / (hi - lo).coerceAtLeast(1e-6f)) }
            val ox = pad * 2f
            val oy = panelY + panelH * 0.2f
            for (g in 0..2) inside += ShapeNode(ShapeSpec.Rect(cw.anim, 1.5f.anim), fill = sk.line.fill(), transform = Transform(x = (w / 2f).anim, y = (oy + ch * g / 2f).anim))
            val draw0 = at + 0.7f
            inside += ShapeNode(
                ShapeSpec.Polyline(xy), stroke = Stroke(up.fill(), w * 0.008f), trimEnd = Anim.tween(0f, 1f, draw0, draw0 + 1.2f, Easing.Move),
                transform = Transform(x = ox.anim, y = oy.anim, anchorX = 0f, anchorY = 0f), name = "dash-line",
            )
            val last = xy.last()
            inside += ShapeNode(
                ShapeSpec.Ellipse((w * 0.025f).anim, (w * 0.025f).anim), fill = up.fill(), stroke = Stroke(sk.raised.fill(), w * 0.005f),
                transform = Transform(
                    x = (ox + last.first).anim, y = (oy + last.second).anim,
                    scale = io.trimio.engine.motion.anim(0f, draw0 + 1.15f) { by(1.4f, 0.2f, Easing.ExpoOut); by(1f, 0.35f, Easing.Land) },
                ),
                name = "dash-dot",
            )
            // Bars and a ring.
            val lowY = panelY + panelH + pad * 0.6f
            val lowH = h - lowY - pad
            val barsW = (w - pad * 3f) * 0.6f
            val bx0 = if (rtl) w - pad - barsW else pad
            val heights = listOf(0.45f, 0.7f, 0.55f, 0.85f, 0.62f, 0.95f)
            val bw = barsW / (heights.size * 1.6f)
            for ((i, v) in heights.withIndex()) {
                val t0 = at + 0.9f + i * 0.08f
                val bh = lowH * 0.8f * v
                inside += ShapeNode(
                    ShapeSpec.Rect(bw.anim, bh.anim, (bw * 0.25f).anim), fill = (if (i == heights.lastIndex) accent else sk.muted.copy(alpha = 0.45f)).fill(),
                    transform = Transform(
                        x = (bx0 + bw * 0.8f + i * bw * 1.6f).anim, y = (lowY + lowH * 0.9f).anim, anchorY = 1f,
                        scaleY = Anim.tween(0f, 1f, t0, t0 + 0.6f, Easing.Land),
                    ),
                    name = "dash-bar",
                )
            }
            val ring = minOf(lowH * 0.78f, (w - pad * 3f) * 0.36f)
            val rx = if (rtl) pad + (w - pad * 3f) * 0.2f else w - pad - (w - pad * 3f) * 0.2f
            val ry = lowY + lowH * 0.5f
            inside += ShapeNode(ShapeSpec.Ellipse(ring.anim, ring.anim), stroke = Stroke(sk.line.fill(), ring * 0.12f), transform = Transform(x = rx.anim, y = ry.anim))
            inside += ShapeNode(
                ShapeSpec.Ellipse(ring.anim, ring.anim), stroke = Stroke(accent.fill(), ring * 0.12f),
                trimEnd = Anim.tween(0f, 0.68f, at + 1.1f, at + 2.2f, Easing.Move), transform = Transform(x = rx.anim, y = ry.anim, rotation = (-90f).anim), name = "dash-ring",
            )
            val nodes = device(cue, look, sk, at, w, h, w * 0.04f * maxOf(look.roundness, 0.4f), inside, "ui-dashboard")
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(draw0 + 1.15f, 0.012f)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(at + 0.45f, SfxKind.Tick, 0.4f), Sfx(draw0, SfxKind.Riser, 0.35f), Sfx(draw0 + 1.15f, SfxKind.Pop, 0.6f)),
            )
        }
    }

    // ------------------------------------------------------------------ notifications

    /**
     * Notifications stacking: each [Cue.items] line arrives as a generic notification card (an
     * app glyph, [Cue.label] as the sender, the line) dropping in from the top with a spring,
     * pushing the earlier ones down. A thread with replies (items starting with ">") is a chat:
     * the message recipe tells it.
     */
    val Chat = object : Recipe("ui-chat", Kind.Element) {
        override val preferredHeight = 0.42f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            if (cue.items.any { it.startsWith(">") }) return MessageRecipes.Message.build(cue, look, fit)
            val at = cue.at - Craft.LEAD
            val sk = skin(look, cue)
            val items = cue.items.ifEmpty { listOfNotNull(cue.text.takeIf { it.isNotBlank() }) }.take(MAX_NOTES)
            if (items.isEmpty()) return Built(emptyList())
            val w = cue.width * 0.92f
            val size = px(w * 0.042f)
            val pad = w * 0.045f
            val glyph = w * 0.12f
            val gap = pad * 0.5f
            val fitted = items.map { fit.fit(it, look.body.copy(weight = minOf(look.body.weight, 700)), size, w - glyph - pad * 3.4f, size * 3f, 2) }
            val cardH = fitted.maxOf { it.height } + size * 1.6f + pad * 1.4f
            val visible = minOf(items.size, ((cue.height + gap) / (cardH + gap)).toInt().coerceAtLeast(1))
            val top = cue.y - (visible * (cardH + gap) - gap) / 2f
            val lands = items.indices.map { i -> cue.itemTimes.getOrNull(i)?.minus(Craft.LEAD) ?: (at + 0.2f + i * NOTE_GAP) }.runningReduce { a, b -> maxOf(b, a + 0.45f) }
            val rtl = cue.rtl
            val nodes = mutableListOf<Node>()
            for ((i, item) in items.withIndex()) {
                val land = lands[i]
                // Slot k: how many cards landed after this one (each pushes it one place down).
                val keys = mutableListOf(Anim.Key(land - 0.45f, top - cardH * 0.9f, Easing.Hold), Anim.Key(land, top, Easing.Spring(0.6f, 2f)))
                for (j in i + 1 until items.size) keys += Anim.Key(lands[j] + 0.4f, top + (j - i) * (cardH + gap), Easing.ExpoOut)
                val gone = lands.getOrNull(i + visible)?.plus(0.3f)
                val shown = listOf(Anim.Key(land - 0.45f, 0f, Easing.Hold), Anim.Key(land - 0.25f, 1f, Easing.Linear))
                val op = Anim.keys(if (gone != null) shown + listOf(Anim.Key(gone - 0.25f, 1f, Easing.Hold), Anim.Key(gone, 0f, Easing.Linear)) else shown)
                val inner = mutableListOf<Node>()
                inner += ShapeNode(
                    ShapeSpec.Rect(w.anim, cardH.anim, (w * 0.05f * maxOf(look.roundness, 0.4f)).anim), fill = sk.surface.fill(), stroke = Stroke(sk.line.fill(), 2f),
                    shadow = Shadow(Color.Black.copy(alpha = if (sk.dark) 0.4f else 0.16f), 0f, 12f, 30f), transform = Transform(x = (w / 2f).anim, y = (cardH / 2f).anim),
                )
                val gx = if (rtl) w - pad - glyph / 2f else pad + glyph / 2f
                inner += ShapeNode(
                    ShapeSpec.Rect(glyph.anim, glyph.anim, (glyph * 0.26f).anim), fill = Fill.Linear(listOf(look.accent, look.hot), 45f.anim),
                    transform = Transform(x = gx.anim, y = (pad + glyph / 2f).anim),
                )
                icon("bell", gx, pad + glyph / 2f, glyph * 0.56f, if (luminance(look.accent) > 0.4f) Color(0xFF111111) else Color.White, land - 0.2f, 0.4f)?.let { inner += it }
                val tx0 = if (rtl) pad else glyph + pad * 2f
                val tw = w - glyph - pad * 3f
                val sender = cue.label
                if (sender != null) {
                    val sf = fit.fit(sender, look.label, px(size * 0.8f), tw * 0.7f, size * 1.2f, 1)
                    inner += text(sender, sf, sk.muted, if (rtl) tx0 + tw - sf.width / 2f else tx0 + sf.width / 2f, pad + sf.height / 2f, persian(sender), name = "note-sender")
                } else {
                    inner += bar(if (rtl) tx0 + tw * 0.6f else tx0, pad * 1.1f, tw * 0.4f, size * 0.5f, sk.muted.copy(alpha = 0.5f))
                }
                val f = fitted[i]
                inner += text(item, f, sk.ink, if (rtl) tx0 + tw - f.width / 2f else tx0 + f.width / 2f, pad + size * 1.5f + f.height / 2f, persian(item), name = "note-text")
                nodes += Group(
                    inner, transform = Transform(anchorX = 0f, anchorY = 0f, x = (cue.x - w / 2f).anim, y = Anim.keys(keys), opacity = op),
                    width = w, height = cardH, name = "note",
                )
            }
            return Built(
                listOf(group(cue, nodes, name)),
                sfx = lands.map { Sfx(it - 0.05f, SfxKind.Pop, 0.5f) },
            )
        }
    }

    // ------------------------------------------------------------------ browser

    /**
     * A browser page loading: a generic window with an address bar typing the address
     * ([Cue.label], else a neutral one), a progress bar racing, stalling and finishing, a
     * skeleton breathing, then the page: a hero picture, the headline ([Cue.text]) and a call to
     * action ([Cue.items] first) that a pointer clicks.
     */
    val Browser = object : Recipe("ui-browser", Kind.Element) {
        override val preferredHeight = 0.5f
        override val minHold = 3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val sk = skin(look, cue)
            val w = minOf(cue.width * 0.94f, cue.height / PAGE_ASPECT)
            val h = w * PAGE_ASPECT
            val pad = w * 0.045f
            val bar = h * 0.1f
            val inside = mutableListOf<Node>()
            for (i in 0 until 3) {
                inside += ShapeNode(
                    ShapeSpec.Ellipse((bar * 0.22f).anim, (bar * 0.22f).anim), fill = sk.muted.copy(alpha = 0.5f).fill(),
                    transform = Transform(x = (pad + i * bar * 0.36f).anim, y = (bar * 0.5f).anim),
                )
            }
            val addrX = pad + bar * 1.1f
            val addrW = w - addrX - pad
            inside += ShapeNode(
                ShapeSpec.Rect(addrW.anim, (bar * 0.56f).anim, (bar * 0.28f).anim), fill = sk.raised.fill(),
                transform = Transform(x = (addrX + addrW / 2f).anim, y = (bar * 0.5f).anim),
            )
            icon("lock", addrX + bar * 0.32f, bar * 0.5f, bar * 0.3f, sk.muted, at + 0.2f, 0.3f)?.let { inside += it }
            val url = cue.label?.takeIf { it.isNotBlank() }?.lowercase() ?: "your-site.com"
            val uf = fit.fit(url, look.label.copy(tracking = 0f), px(bar * 0.26f), addrW - bar * 0.8f, bar * 0.45f, 1)
            val typing = (url.length * 0.04f).coerceIn(0.3f, 0.9f)
            inside += text(
                url, uf, sk.ink, addrX + bar * 0.6f + uf.width / 2f, bar * 0.5f, false,
                listOf(TextAnimator(TextUnit.Char, UnitState(opacity = 0f), at = at + 0.3f, duration = 0.01f, stagger = typing / url.length, ease = Easing.Hold)), "browser-url",
            )
            // Progress: races to 70 %, stalls, finishes, fades.
            val go = at + 0.3f + typing
            val loaded = go + 1.0f
            inside += ShapeNode(
                ShapeSpec.Rect(w.anim, (w * 0.008f).anim), fill = look.accent.fill(),
                transform = Transform(
                    x = 0f.anim, y = bar.anim, anchorX = 0f,
                    scaleX = Anim.keys(Anim.Key(go, 0f, Easing.Hold), Anim.Key(go + 0.35f, 0.7f, Easing.ExpoOut), Anim.Key(loaded - 0.2f, 0.78f, Easing.Linear), Anim.Key(loaded, 1f, Easing.ExpoOut)),
                    opacity = Anim.keys(Anim.Key(go, 1f, Easing.Hold), Anim.Key(loaded + 0.1f, 1f, Easing.Hold), Anim.Key(loaded + 0.35f, 0f, Easing.Linear)),
                ),
                name = "browser-progress",
            )
            // Skeleton until the page lands.
            val heroY = bar + pad
            val heroH = h * 0.36f
            val breathe = Anim.keys((0 until 8).map { Anim.Key(go + it * 0.3f, if (it % 2 == 0) 0.55f else 1f, Easing.SineInOut) }) *
                Anim.keys(Anim.Key(loaded, 1f, Easing.Hold), Anim.Key(loaded + 0.25f, 0f, Easing.Linear))
            inside += ShapeNode(
                ShapeSpec.Rect((w - pad * 2f).anim, heroH.anim, (w * 0.03f).anim), fill = sk.raised.fill(),
                transform = Transform(x = (w / 2f).anim, y = (heroY + heroH / 2f).anim, opacity = breathe), name = "skeleton",
            )
            for (k in 0 until 3) inside += bar(pad, heroY + heroH + pad * (1f + k * 0.9f), (w - pad * 2f) * (1f - k * 0.2f), pad * 0.45f, sk.raised, breathe, "skeleton")
            // The page.
            val show = Anim.tween(0f, 1f, loaded, loaded + 0.35f)
            inside += ShapeNode(
                ShapeSpec.Rect((w - pad * 2f).anim, heroH.anim, (w * 0.03f).anim),
                fill = Fill.Linear(listOf(lerp(sk.raised, look.accent, 0.5f), lerp(sk.raised, look.hot, 0.55f)), 135f.anim),
                transform = Transform(x = (w / 2f).anim, y = (heroY + heroH / 2f).anim, opacity = show, scale = Anim.tween(1.04f, 1f, loaded, loaded + 0.8f, Easing.ExpoOut)), name = "page-hero",
            )
            icon("image", w / 2f, heroY + heroH / 2f, heroH * 0.3f, sk.ink.copy(alpha = 0.55f), loaded + 0.1f, 0.5f)?.let { inside += it }
            var y = heroY + heroH + pad
            val headline = cue.text.takeIf { it.isNotBlank() }
            if (headline != null) {
                val hf = fit.fit(headline, look.headline, w * 0.07f, w - pad * 2f, h * 0.16f, 2)
                inside += text(
                    headline, hf, sk.ink, if (cue.rtl) w - pad - hf.width / 2f else pad + hf.width / 2f, y + hf.height / 2f, persian(headline),
                    listOf(TextAnimator(TextUnit.Line, UnitState(dy = 0.4f, opacity = 0f), at = loaded + 0.1f, duration = 0.5f, stagger = 0.08f, ease = Easing.ExpoOut)), "page-headline",
                )
                y += hf.height + pad * 0.6f
            }
            for (k in 0 until 2) {
                val bw = (w - pad * 2f) * (0.9f - k * 0.25f)
                inside += bar(if (cue.rtl) w - pad - bw else pad, y + k * pad * 0.8f, bw, pad * 0.4f, sk.muted.copy(alpha = 0.35f), show)
            }
            y += pad * 2.2f
            val cta = cue.items.firstOrNull()
            val btnH = w * 0.1f
            val cf = cta?.let { fit.fit(it, look.body.copy(weight = minOf(look.body.weight, 800)), btnH * 0.42f, w * 0.6f, btnH * 0.7f, 1) }
            val btnW = (cf?.width ?: w * 0.25f) + btnH * 1.2f
            val bx = if (cue.rtl) w - pad - btnW / 2f else pad + btnW / 2f
            val by = minOf(y + btnH / 2f, h - pad - btnH / 2f)
            val click = loaded + 1.0f
            val press = Anim.keys(Anim.Key(click, 1f, Easing.Hold), Anim.Key(click + 0.08f, 0.92f, Easing.ExpoOut), Anim.Key(click + 0.4f, 1f, Easing.Spring()))
            inside += ShapeNode(
                ShapeSpec.Rect(btnW.anim, btnH.anim, (btnH / 2f).anim), fill = look.accent.fill(),
                transform = Transform(x = bx.anim, y = by.anim, opacity = Anim.tween(0f, 1f, loaded + 0.3f, loaded + 0.5f), scale = press), name = "page-cta",
            )
            if (cta != null && cf != null) {
                inside += Craft.onBody(TextNode(
                    cta, cf.type, look.onAccent.fill(), rtl = persian(cta),
                    transform = Transform(x = bx.anim, y = by.anim, opacity = Anim.tween(0f, 1f, loaded + 0.35f, loaded + 0.55f), scale = press), name = "page-cta-text",
                ))
            }
            Icons.path("pointer")?.let { d ->
                val ps = btnH * 0.75f
                inside += ShapeNode(
                    ShapeSpec.Path(d, Icons.VIEWPORT, ps), fill = sk.ink.fill(), stroke = Stroke(sk.surface.copy(alpha = 1f).fill(), ps * 0.06f),
                    transform = Transform(
                        // The tip lands on the button's far end, clear of its label.
                        x = Anim.tween(w * 0.8f, bx + (if (cue.rtl) -1f else 1f) * btnW * 0.5f + ps * 0.3f, loaded + 0.2f, click, Easing.Move),
                        y = Anim.tween(h * 0.95f, by + ps * 0.4f, loaded + 0.2f, click, Easing.Move),
                        opacity = Anim.tween(0f, 1f, loaded + 0.2f, loaded + 0.4f), scale = press,
                    ),
                    name = "page-pointer",
                )
            }
            val nodes = device(cue, look, sk, at, w, h, w * 0.035f * maxOf(look.roundness, 0.4f), inside, "ui-browser")
            return Built(
                listOf(group(cue, nodes, name)),
                camera = listOf(Craft.punch(click, 0.012f)),
                sfx = listOf(Sfx(at, SfxKind.Swish, 0.5f), Sfx(at + 0.3f, SfxKind.Tick, 0.35f), Sfx(loaded, SfxKind.Shimmer, 0.45f), Sfx(click, SfxKind.Click, 0.8f)),
            )
        }
    }

    // ------------------------------------------------------------------ icon scene

    /**
     * The vector element generator: an icon composed into a small scene for any topic — a hero
     * drawing itself on a soft disc, a badge popping onto its corner (a calendar with a check, a
     * phone with a bell, a chart with a trend), small satellites orbiting in, and an optional
     * morph (the hero un-draws as the next draws in). [Cue.icon] is "hero+badge", "a>b" (morph)
     * or both ("phone>chat+check"); a vocabulary picture ([Cue.visual]) becomes the hero and
     * assembles. [Cue.items] name satellites; [Cue.text] is a caption under it.
     */
    val Scene = object : Recipe("ui-scene", Kind.Element) {
        override val preferredHeight = 0.42f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val spec = (cue.icon ?: "spark").lowercase()
            val badge = spec.substringAfter('+', "").takeIf { it.isNotBlank() }
            val heroes = spec.substringBefore('+').split('>').map { it.trim() }.filter { Icons.path(it) != null }.ifEmpty { listOf("spark") }
            val caption = cue.text.takeIf { it.isNotBlank() }
            val capF = caption?.let { fit.fit(it, look.headline, cue.width * 0.075f, cue.width * 0.9f, cue.height * 0.22f, 2) }
            val room = cue.height - (capF?.height ?: 0f) * 1.3f
            val d = minOf(cue.width * 0.62f, room * 0.86f)
            val cx = cue.x
            val cy = cue.y - (capF?.height ?: 0f) * 0.65f
            val ink = if (cue.overMedia) Color.White else look.ink
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Ellipse(d.anim, d.anim), fill = Fill.Radial(listOf(look.accent.copy(alpha = 0.26f), look.accent.copy(alpha = 0.08f), Color.Transparent)),
                transform = Transform(x = cx.anim, y = cy.anim, scale = Anim.tween(0.4f, 1f, at, at + 0.8f, Easing.ExpoOut), opacity = Anim.tween(0f, 1f, at, at + 0.3f)), name = "scene-disc",
            )
            nodes += ShapeNode(
                ShapeSpec.Ellipse((d * 0.78f).anim, (d * 0.78f).anim), stroke = Stroke(ink.copy(alpha = 0.18f).fill(), 2f),
                trimEnd = Anim.tween(0f, 1f, at + 0.1f, at + 0.9f, Easing.Move), transform = Transform(x = cx.anim, y = cy.anim, rotation = (-90f).anim), name = "scene-ring",
            )
            val heroSize = d * 0.48f
            val stroke = heroSize * 0.07f
            val shadow = if (cue.overMedia) Shadow(Color.Black.copy(alpha = 0.45f), 0f, 4f, 18f) else null
            val visual = cue.visual
            val step = ((cue.out - at - 1.4f) / heroes.size).coerceIn(0.9f, 1.8f)
            if (visual != null) {
                nodes += VectorNode(
                    visual, heroSize * 1.1f, tint = ink.let { io.trimio.engine.motion.ColorAnim.of(it) }, assemble = Anim.tween(0f, 1f, at + 0.2f, at + 1.0f, Easing.ExpoOut),
                    shadow = shadow, transform = Transform(x = cx.anim, y = cy.anim), name = "scene-hero",
                )
            } else {
                for ((i, hero) in heroes.withIndex()) {
                    val on = at + 0.2f + i * step
                    val off = if (i < heroes.lastIndex) on + step else Float.POSITIVE_INFINITY
                    nodes += ShapeNode(
                        ShapeSpec.Path(Icons.path(hero)!!, Icons.VIEWPORT, heroSize), stroke = Stroke(ink.fill(), stroke), shadow = shadow,
                        trimEnd = Anim.tween(0f, 1f, on, on + 0.75f, Easing.Move),
                        // The morph: the outgoing icon un-draws from its start as the next draws in.
                        trimStart = if (off.isFinite()) Anim.tween(0f, 1f, off - 0.1f, off + 0.5f, Easing.Move) else Anim.Zero,
                        transform = Transform(
                            x = cx.anim, y = cy.anim,
                            scale = if (off.isFinite()) Anim.tween(1f, 0.9f, off - 0.1f, off + 0.5f, Easing.Move) else Anim.tween(0.9f, 1f, on, on + 0.6f, Easing.ExpoOut),
                        ),
                        end = if (off.isFinite()) off + 0.55f else Float.POSITIVE_INFINITY, name = "scene-hero",
                    )
                }
            }
            val settled = at + 0.2f + (heroes.size - 1) * step + 0.7f
            if (badge != null && Icons.path(badge) != null) {
                val b = d * 0.24f
                val bx = cx + heroSize * 0.48f
                val by = cy - heroSize * 0.42f
                val dark = luminance(look.accent) > 0.4f
                nodes += ShapeNode(
                    ShapeSpec.Ellipse(b.anim, b.anim), fill = look.accent.fill(), stroke = Stroke((if (cue.overMedia) Color.Black.copy(alpha = 0.3f) else look.canvas).fill(), b * 0.08f),
                    transform = Transform(
                        x = bx.anim, y = by.anim,
                        scale = io.trimio.engine.motion.anim(0f, settled) { by(1.2f, 0.22f, Easing.ExpoOut); by(1f, 0.35f, Easing.Land) },
                    ),
                    name = "scene-badge",
                )
                icon(badge, bx, by, b * 0.6f, if (dark) Color(0xFF111111) else Color.White, settled + 0.12f, 0.35f, b * 0.07f)?.let { nodes += it }
            }
            // Satellites orbit in from the hero and settle around the ring.
            val sats = cue.items.mapNotNull { n -> n.lowercase().takeIf { Icons.path(it) != null } }.take(MAX_SATELLITES)
            for ((i, s) in sats.withIndex()) {
                val a = (-150f + i * (300f / maxOf(1, sats.size - 1).coerceAtLeast(1))) * (kotlin.math.PI.toFloat() / 180f)
                val r = d * 0.5f
                val t0 = settled + 0.1f + i * 0.12f
                val px = cx + cos(a) * r
                val py = cy + sin(a) * r * 0.9f
                nodes += ShapeNode(
                    ShapeSpec.Ellipse((d * 0.17f).anim, (d * 0.17f).anim), fill = (if (cue.overMedia) Color.Black.copy(alpha = 0.55f) else look.card).fill(),
                    stroke = Stroke(look.cardRim.fill(), 2f),
                    transform = Transform(x = Anim.tween(cx, px, t0, t0 + 0.6f, Easing.ExpoOut), y = Anim.tween(cy, py, t0, t0 + 0.6f, Easing.ExpoOut), opacity = Anim.tween(0f, 1f, t0, t0 + 0.2f)),
                    name = "scene-satellite",
                )
                icon(s, px, py, d * 0.09f, ink, t0 + 0.3f, 0.4f)?.let { nodes += it }
            }
            if (caption != null && capF != null) {
                nodes += TextNode(
                    caption, capF.type, ink.fill(), maxWidth = cue.width * 0.9f, rtl = persian(caption), shadow = Craft.shadow(cue, look, capF.type.size),
                    animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 0.4f, opacity = 0f), at = at + 0.35f, duration = 0.5f, stagger = 0.06f, ease = Easing.ExpoOut)),
                    transform = Transform(x = cx.anim, y = (cy + d / 2f + capF.height * 0.75f).anim), name = "scene-caption",
                )
            }
            return Built(
                listOf(group(cue, nodes, name)),
                sfx = listOf(Sfx(at + 0.2f, SfxKind.Shimmer, 0.4f)) + (if (badge != null) listOf(Sfx(settled, SfxKind.Pop, 0.7f)) else emptyList()),
            )
        }
    }

    val all: List<Recipe> = listOf(Feed, Code, Dashboard, Chat, Browser, Scene)

    private const val MIN_CONTRAST = 4.6f
    private const val MIN_TEXT = 27f
    private const val POST_WORDS = 7
    private const val DASH_ASPECT = 1.05f
    private const val PAGE_ASPECT = 1.12f
    private const val GLASS_PAD = 0.035f
    private const val FEED_ROWS = 6
    private const val MAX_CODE_LINES = 9
    private const val MIN_CPS = 14f
    private const val LINE_PAUSE = 0.12f
    private const val MAX_TITLE = 28
    private const val MAX_NOTES = 5
    private const val NOTE_GAP = 0.75f
    private const val MAX_SATELLITES = 4
}
