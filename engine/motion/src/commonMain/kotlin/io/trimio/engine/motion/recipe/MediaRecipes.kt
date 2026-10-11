package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Fit
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * The creator's own media beside the prompt — their logo, a product photo, a screenshot, a clip —
 * placed where the speaker talks about it, each in the form a senior editor would give it. The
 * picture itself comes from the [io.trimio.engine.motion.MediaSource] by [Cue.media]; the recipe
 * only frames and moves it, so a real file and a synthetic one render the same way. The browser
 * bar for a site the speaker names ([Url]) lives here too: it is the same "show the real thing" idea.
 */
object MediaRecipes {

    /** Recipes that draw a creator's own picture or clip (the jury checks every attached one is used). */
    val SHOWS_MEDIA: Set<String> = setOf("logo-mark", "product", "screenshot", "pip", "b-roll")

    /**
     * The brand-mark treatment with the creator's own logo as the mark: a tile flips in on a spring,
     * a glow in the look's heat breathes behind it, a ring bursts as it settles, the name rises
     * under it. A light logo (`mark = "dark"`) sits on a dark tile, everything else on a bright one.
     */
    val LogoMark = object : Recipe("logo-mark", Kind.Element) {
        override val preferredHeight = 0.26f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val label = cue.label?.takeIf { it.isNotBlank() }
            val labelH = if (label != null) cue.height * LABEL_SHARE else 0f
            val side = minOf(cue.height - labelH, cue.width * 0.5f) * 0.84f
            val tileW = minOf(side * cue.aspect.coerceIn(1f, 2.2f), cue.width * 0.8f)
            val x = cue.x
            val y = cue.y - labelH / 2f
            val t0 = cue.at - Craft.LEAD
            val dark = cue.mark == "dark"
            val shown = anim(0f, t0) { by(1f, 0.1f, Easing.Linear) }
            val scale = anim(0.55f, t0) { to(1f, t0 + 0.6f, Easing.Spring(0.55f, 2.1f)) }
            val flip = Anim.tween(-95f, 0f, t0, t0 + 0.65f, Easing.Spring(0.62f, 1.8f))
            fun at(op: Anim = shown) = Transform(x = x.anim, y = y.anim, scale = scale, rotationY = flip, opacity = op, perspective = side * 6f)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Ellipse((tileW * 2f).anim, (side * 2f).anim),
                fill = Fill.Radial(listOf(look.hot.copy(alpha = 0.42f), look.hot.copy(alpha = 0.12f), Color.Transparent)),
                transform = Transform(x = x.anim, y = y.anim, scale = scale, opacity = anim(0f, t0 + 0.3f) { by(1f, 0.25f, Easing.ExpoOut); by(0.6f, 0.6f) }),
                name = "logo-mark-glow",
            )
            nodes += ShapeNode(
                ShapeSpec.Rect(tileW.anim, side.anim, (side * 0.22f).anim), fill = (if (dark) DARK_TILE else LIGHT_TILE).fill(),
                stroke = Stroke(Color.White.copy(alpha = 0.22f).fill(), maxOf(1.5f, side * 0.01f)),
                shadow = Shadow(Color.Black.copy(alpha = 0.38f), 0f, side * 0.05f, side * 0.14f), transform = at(), name = "logo-mark-tile",
            )
            // The creator's own mark, contained with breathing room: never cropped, never stretched.
            nodes += MediaNode(
                media, tileW * 0.76f, side * 0.7f, Fit.Contain,
                transform = at(op = shown * Anim.tween(0f, 1f, t0 + 0.18f, t0 + 0.4f)), start = t0, name = "logo-mark-image",
            )
            nodes += ShapeNode(
                ShapeSpec.Rect(tileW.anim, side.anim, (side * 0.22f).anim), stroke = Stroke(look.hot.copy(alpha = 0.9f).fill(), maxOf(2f, side * 0.016f)),
                transform = Transform(
                    x = x.anim, y = y.anim, scale = Anim.tween(1f, 1.35f, t0 + 0.45f, t0 + 1.05f, Easing.ExpoOut),
                    opacity = Anim.tween(0.9f, 0f, t0 + 0.45f, t0 + 1.05f, Easing.ExpoOut),
                ),
                start = t0 + 0.43f, end = t0 + 1.1f, name = "logo-mark-ring",
            )
            if (label != null) nodes += rising(label, cue, look, fit, x, y + side / 2f + labelH * 0.6f, labelH, t0 + 0.45f)
            return Built(listOf(leave(cue, nodes, name)), sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.6f), Sfx(t0, SfxKind.Shimmer, 0.4f)))
        }
    }

    /**
     * A product photo as a premium card: it swings in on a slight 3D tilt with a soft drop shadow
     * under it, a sheen sweeps across once it lands, it drifts while it is talked about, and a pill
     * names it in the speaker's own words.
     */
    val Product = object : Recipe("product", Kind.Element) {
        override val preferredHeight = 0.5f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val label = cue.label?.takeIf { it.isNotBlank() } ?: cue.text.takeIf { it.isNotBlank() }
            val room = stageRoom(cue)
            val labelH = if (label != null) minOf(cue.height * 0.13f, cue.width * 0.1f) else 0f
            val (w, h) = fitAspect(cue.aspect, cue.width * 0.78f, (room.height - labelH * 1.5f) * 0.94f)
            val y = room.y - labelH * 0.75f
            val t0 = cue.at - Craft.LEAD
            val radius = minOf(w, h) * 0.07f * look.roundness.coerceIn(0.3f, 1.5f)
            val land = t0 + 0.75f
            val tilt = Easing.Spring(0.6f, 1.7f)
            val turn = anim(30f, t0) { to(-7f, land, tilt); to(-3f, cue.out, Easing.SineInOut) }
            val lean = anim(14f, t0) { to(3f, land, tilt); to(1f, cue.out, Easing.SineInOut) }
            val scale = anim(0.82f, t0) { to(1f, land, Easing.ExpoOut) }
            val lift = anim(h * 0.08f, t0) { to(0f, land, Easing.ExpoOut) } + Anim.Noise(cue.seed, 0.4f, anim(0f, land) { by(h * 0.012f, 0.6f); hold(cue.out) })
            val shown = Anim.tween(0f, 1f, t0, t0 + 0.18f)
            val nodes = mutableListOf<Node>()
            // A soft shadow on the floor under the card grounds it.
            nodes += ShapeNode(
                ShapeSpec.Rect((w * 0.9f).anim, (h * 0.92f).anim, radius.anim), fill = Color.Black.fill(),
                transform = Transform(x = cue.x.anim, y = (y + h * 0.07f).anim + lift * 0.4f.anim, scale = scale * 0.98f.anim, opacity = shown * 0.5f.anim, blur = (h * 0.06f).anim),
                name = "product-shadow",
            )
            val sheen = ShapeNode(
                ShapeSpec.Rect((w * 0.32f).anim, (h * 1.6f).anim),
                fill = Fill.Linear(listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent)),
                transform = Transform(x = Anim.tween(-w * 0.3f, w * 1.3f, land - 0.2f, land + 0.55f, Easing.SineInOut), y = (h / 2f).anim, rotation = 18f.anim),
                start = land - 0.25f, end = land + 0.6f, name = "product-sheen",
            )
            val middle = Transform(x = (w / 2f).anim, y = (h / 2f).anim)
            val card = Group(
                listOf(
                    MediaNode(media, w, h, Fit.Cover, radius = radius.anim, transform = Transform(x = (w / 2f).anim, y = (h / 2f).anim), start = t0, name = "product-photo"),
                    Group(listOf(sheen), mask = Mask(ShapeNode(ShapeSpec.Rect(w.anim, h.anim, radius.anim), fill = Color.White.fill(), transform = middle)), width = w, height = h),
                    ShapeNode(ShapeSpec.Rect(w.anim, h.anim, radius.anim), stroke = Stroke(Color.White.copy(alpha = 0.28f).fill(), maxOf(1.5f, w * 0.004f)), transform = middle),
                ),
                transform = Transform(
                    x = cue.x.anim, y = y.anim + lift, anchorX = 0.5f, anchorY = 0.5f, scale = scale,
                    rotationY = turn, rotationX = lean, opacity = shown, perspective = w * 3.2f,
                ),
                width = w, height = h, name = "product-card",
            )
            nodes += card
            if (label != null) nodes += pill(label, cue, look, fit, cue.x, y + h / 2f + labelH * 0.95f, labelH, land - 0.2f)
            return Built(listOf(leave(cue, nodes, name)), sfx = listOf(Sfx(t0, SfxKind.Whoosh, 0.5f), Sfx(land - 0.15f, SfxKind.Shimmer, 0.45f)))
        }
    }

    /**
     * A screenshot in the frame it came from: a tall one in a phone (bezel, rounded screen, the
     * camera pill), a wide one in a browser window (traffic lights, the address in a pill). It rises
     * in with a small tilt, then pushes in slowly, as if the viewer leans closer.
     */
    val Screenshot = object : Recipe("screenshot", Kind.Element) {
        override val preferredHeight = 0.55f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val t0 = cue.at - Craft.LEAD
            val land = t0 + 0.7f
            val framed = if (cue.aspect < PHONE_ASPECT) phone(media, cue) else browser(media, cue, look, fit)
            // Bounds with room for the shadow: the frame fades in through a layer of this size.
            val bw = framed.width * PAD
            val bh = framed.height * PAD
            val inner = Group(framed.parts, transform = Transform(x = (bw / 2f).anim, y = (bh / 2f).anim, anchorX = 0f, anchorY = 0f), width = 0f, height = 0f)
            val frame = Group(
                listOf(inner),
                transform = Transform(
                    x = cue.x.anim, y = stageRoom(cue).y.anim + anim(bh * 0.1f, t0) { to(0f, land, Easing.ExpoOut) }, anchorX = 0.5f, anchorY = 0.5f,
                    scale = anim(0.9f, t0) { to(1f, land, Easing.ExpoOut); to(1.035f, cue.out, Easing.SineInOut) },
                    rotationX = anim(18f, t0) { to(0f, land, Easing.Spring(0.6f, 1.6f)) },
                    opacity = Anim.tween(0f, 1f, t0, t0 + 0.2f), perspective = bh * 3f,
                ),
                width = bw, height = bh, name = "screenshot-frame",
            )
            return Built(listOf(leave(cue, listOf(frame), name)), sfx = listOf(Sfx(t0, SfxKind.Swish, 0.5f), Sfx(land, SfxKind.Click, 0.35f)))
        }

        /** A phone around the screenshot, centred on the origin. */
        private fun phone(media: String, cue: Cue): Framed {
            val (sw, sh) = fitAspect(cue.aspect, cue.width * 0.6f, stageRoom(cue).height * 0.84f)
            val bezel = sw * 0.045f
            val bw = sw + bezel * 2f
            val bh = sh + bezel * 2f
            val parts = listOf(
                ShapeNode(
                    ShapeSpec.Rect(bw.anim, bh.anim, (sw * 0.15f).anim), fill = DEVICE.fill(), stroke = Stroke(DEVICE_RIM.fill(), maxOf(2f, sw * 0.008f)),
                    shadow = Shadow(Color.Black.copy(alpha = 0.45f), 0f, bh * 0.025f, bh * 0.06f), name = "phone-body",
                ),
                MediaNode(media, sw, sh, Fit.Cover, radius = (sw * 0.11f).anim, name = "phone-screen"),
                ShapeNode(
                    ShapeSpec.Rect((sw * 0.28f).anim, (bezel * 1.3f).anim, (bezel * 0.65f).anim), fill = DEVICE.fill(),
                    transform = Transform(y = (-sh / 2f + bezel * 1.4f).anim), name = "phone-island",
                ),
            )
            return Framed(parts, bw, bh)
        }

        /** A browser window around the screenshot: the chrome bar on top, the address in a pill. */
        private fun browser(media: String, cue: Cue, look: Look, fit: Fitter): Framed {
            val maxW = cue.width * 0.92f
            val bar0 = maxW * BAR
            val (iw, ih) = fitAspect(cue.aspect, maxW, stageRoom(cue).height * 0.86f - bar0)
            val bar = iw * BAR
            val wh = ih + bar
            val top = -wh / 2f
            val r = iw * 0.025f
            val parts = mutableListOf<Node>(
                ShapeNode(
                    ShapeSpec.Rect(iw.anim, wh.anim, r.anim), fill = CHROME.fill(),
                    shadow = Shadow(Color.Black.copy(alpha = 0.4f), 0f, wh * 0.025f, wh * 0.07f), name = "browser-window",
                ),
                MediaNode(media, iw - 2f, ih, Fit.Cover, radius = (r * 0.5f).anim, transform = Transform(y = (top + bar + ih / 2f).anim), name = "browser-page"),
            )
            val dot = bar * 0.26f
            listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840).forEachIndexed { i, c ->
                parts += ShapeNode(
                    ShapeSpec.Ellipse(dot.anim, dot.anim), fill = Color(c).fill(),
                    transform = Transform(x = (-iw / 2f + bar * 0.55f + i * dot * 1.6f).anim, y = (top + bar / 2f).anim), name = "browser-light",
                )
            }
            val address = cue.label?.takeIf { it.isNotBlank() }
            parts += ShapeNode(
                ShapeSpec.Rect((iw * 0.5f).anim, (bar * 0.56f).anim, (bar * 0.28f).anim), fill = Color.White.fill(),
                transform = Transform(y = (top + bar / 2f).anim), name = "browser-address",
            )
            if (address != null) {
                val f = fit.fit(address, look.body.copy(weight = 600), bar * 0.3f, iw * 0.44f, bar * 0.5f, maxLines = 1)
                parts += Craft.onBody(TextNode(address, f.type, ADDRESS_INK.fill(), rtl = false, transform = Transform(y = (top + bar / 2f).anim), name = "browser-address-text"))
            }
            return Framed(parts, iw, wh)
        }
    }

    /**
     * A clip as picture-in-picture over the speaker: a rounded card with a white rim springs in
     * from a slight twist and plays while the speaker goes on talking (their voice stays on).
     */
    val Pip = object : Recipe("pip", Kind.Element) {
        override val preferredHeight = 0.28f
        override val minHold = 1.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val (w, h) = fitAspect(cue.aspect, cue.width * 0.62f, cue.height * 0.94f)
            val t0 = cue.at - Craft.LEAD
            val r = minOf(w, h) * 0.09f
            val transform = Transform(
                x = cue.x.anim, y = cue.y.anim,
                scale = anim(0.55f, t0) { to(1f, t0 + 0.55f, Easing.Spring(0.55f, 2f)) },
                rotation = anim(-5f, t0) { to(0f, t0 + 0.6f, Easing.Spring(0.5f, 1.8f)) },
                opacity = Anim.tween(0f, 1f, t0, t0 + 0.12f),
            )
            val nodes = listOf(
                ShapeNode(
                    ShapeSpec.Rect((w + 8f).anim, (h + 8f).anim, (r + 4f).anim), fill = Color.White.fill(),
                    shadow = Shadow(Color.Black.copy(alpha = 0.45f), 0f, h * 0.04f, h * 0.12f), transform = transform, name = "pip-rim",
                ),
                MediaNode(media, w, h, Fit.Cover, sourceOffset = cue.from ?: 0f, radius = r.anim, transform = transform, start = t0, name = "pip-clip"),
            )
            return Built(listOf(leave(cue, nodes, name)), sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.55f)))
        }
    }

    /**
     * A full-frame cut-in (B-roll) that keeps the speaker's voice: the clip fills the frame from a
     * step too close, settles, then pushes in slowly; a low scrim keeps the captions readable.
     */
    val BRoll = object : Recipe("b-roll", Kind.Element) {
        override val preferredHeight = 0.6f
        override val minHold = 1.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val media = cue.media ?: return Built(emptyList())
            val fw = cue.frameWidth.takeIf { it > 0f } ?: (cue.x * 2f)
            val fh = cue.frameHeight.takeIf { it > 0f } ?: (cue.y * 2f)
            val t0 = cue.at - Craft.LEAD
            val clip = MediaNode(
                media, fw, fh, Fit.Cover, sourceOffset = cue.from ?: 0f,
                transform = Transform(x = (fw / 2f).anim, y = (fh / 2f).anim, scale = anim(1.12f, t0) { to(1f, t0 + 0.45f, Easing.ExpoOut); to(1.05f, cue.out, Easing.Linear) }),
                start = t0, end = cue.out + 0.02f, name = "b-roll-clip",
            )
            val scrim = EffectNode(Effect.Scrim(fromBottom = true, coverage = 0.4f, opacity = 0.6f.anim), start = t0, end = cue.out + 0.02f, name = "b-roll-scrim")
            return Built(listOf(clip, scrim), sfx = listOf(Sfx(t0, SfxKind.Whoosh, 0.45f)))
        }
    }

    /**
     * A site the speaker names, as the browser shows it: a clean white address pill opens from a
     * dot, the lock (and the site's mark, when the brand library knows it) appear, the domain types
     * in letter by letter with a caret, exactly as the brief spells it.
     */
    val Url = object : Recipe("url", Kind.Element) {
        override val preferredHeight = 0.14f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val domain = cue.text.trim().ifEmpty { cue.label ?: return Built(emptyList()) }
            val ph = minOf(cue.height * 0.7f, cue.width * 0.16f)
            val f = fit.fit(domain, look.body.copy(weight = 700), ph * 0.46f, cue.width * 0.62f, ph * 0.7f, maxLines = 1)
            val mark = cue.marks.firstOrNull()?.path
            val icon = ph * 0.42f
            val gap = ph * 0.22f
            val pad = ph * 0.42f
            val inner = icon + gap + (if (mark != null) icon + gap else 0f) + f.width
            val pw = maxOf(inner + pad * 2f, ph * 3f)
            val left = cue.x - inner / 2f
            val t0 = cue.at - Craft.LEAD
            val open = t0 + 0.4f
            val chars = domain.count { !it.isWhitespace() }.coerceAtLeast(1)
            val typing = (chars * 0.045f).coerceIn(0.35f, 1.1f)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(
                ShapeSpec.Rect(Anim.tween(ph, pw, t0, open, Easing.ExpoOut), ph.anim, (ph / 2f).anim), fill = Color.White.copy(alpha = 0.97f).fill(),
                shadow = Shadow(Color.Black.copy(alpha = 0.32f), 0f, ph * 0.12f, ph * 0.35f),
                transform = Transform(x = cue.x.anim, y = cue.y.anim, scale = anim(0.4f, t0) { to(1f, t0 + 0.35f, Easing.Spring(0.6f, 2f)) }), name = "url-pill",
            )
            val lockPath = Icons.path("lock")
            if (lockPath != null) {
                nodes += ShapeNode(
                    ShapeSpec.Path(lockPath, Icons.VIEWPORT, icon), stroke = Stroke(SECURE.fill(), maxOf(2f, icon * 0.09f)),
                    transform = Transform(x = (left + icon / 2f).anim, y = cue.y.anim, opacity = Anim.tween(0f, 1f, open - 0.1f, open + 0.1f)), name = "url-lock",
                )
            }
            var x = left + icon + gap
            if (mark != null) {
                nodes += ShapeNode(
                    ShapeSpec.Path(mark, Icons.VIEWPORT, icon), fill = cue.marks.first().color.fill(),
                    transform = Transform(x = (x + icon / 2f).anim, y = cue.y.anim, scale = anim(0f, open) { to(1f, open + 0.35f, Easing.Spring(0.55f, 2.2f)) }), name = "url-mark",
                )
                x += icon + gap
            }
            val typed = TextAnimator(TextUnit.Char, UnitState(opacity = 0f, scale = 1.25f), at = open, duration = 0.06f, stagger = typing / chars, ease = Easing.QuintOut)
            val typedText = TextNode(
                domain, f.type, ADDRESS_INK.fill(), rtl = false, animators = listOf(typed), transform = Transform(x = (x + f.width / 2f).anim, y = cue.y.anim), name = "url-domain",
            )
            nodes += Craft.onBody(typedText)
            // The caret follows the typing, then blinks twice and rests.
            val done = open + typing
            nodes += ShapeNode(
                ShapeSpec.Rect(maxOf(2f, ph * 0.035f).anim, (f.type.size * 1.05f).anim), fill = look.accent.fill(),
                transform = Transform(
                    x = Anim.tween(x, x + f.width + ph * 0.06f, open, done, Easing.Linear), y = cue.y.anim,
                    opacity = anim(1f, done) { by(0f, 0.01f, Easing.Hold); hold(done + 0.25f); by(1f, 0.01f, Easing.Hold); hold(done + 0.5f); by(0f, 0.01f, Easing.Hold) },
                ),
                start = open, end = done + 0.8f, name = "url-caret",
            )
            val sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.5f), Sfx(open, SfxKind.Tick, 0.35f), Sfx(done, SfxKind.Click, 0.3f))
            return Built(listOf(leave(cue, nodes, name, rise = ph * 0.6f)), sfx = sfx)
        }
    }

    /** Where a hero card may sit: on a stage of its own it grows up from its slot to the frame's safe top; over the speaker it keeps its slot. */
    private class Room(val y: Float, val height: Float)

    private fun stageRoom(cue: Cue): Room {
        val bottom = cue.y + cue.height / 2f
        val top = if (cue.overMedia || cue.frameHeight <= 0f) cue.y - cue.height / 2f else minOf(cue.y - cue.height / 2f, cue.frameHeight * SAFE_TOP)
        return Room((top + bottom) / 2f, bottom - top)
    }

    /** A device or window frame's parts, centred on the origin, and its size. */
    private class Framed(val parts: List<Node>, val width: Float, val height: Float)

    /** [aspect] (w ÷ h) fitted inside [maxW]×[maxH]. */
    internal fun fitAspect(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val a = aspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        return if (maxW / maxH > a) (maxH * a) to maxH else maxW to (maxW / a)
    }

    /** A label that rises word by word, on the look's ink (with the footage guard when over media). */
    private fun rising(text: String, cue: Cue, look: Look, fit: Fitter, x: Float, y: Float, h: Float, at: Float): Node {
        val f = fit.fit(text, look.headline.copy(weight = 800), h * 0.62f, cue.width * 0.9f, h, maxLines = 1)
        return TextNode(
            text, f.type, look.ink.fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, f.type.size),
            animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at, duration = 0.6f, stagger = 0.06f, ease = Easing.ExpoOut, clipToLine = true)),
            transform = Transform(x = x.anim, y = y.anim), name = "media-label",
        )
    }

    /** A pill in the accent naming the thing, opening sideways from its centre. */
    private fun pill(text: String, cue: Cue, look: Look, fit: Fitter, x: Float, y: Float, h: Float, at: Float): Node {
        val f = fit.fit(text, look.headline.copy(weight = 800), h * 0.5f, cue.width * 0.8f, h * 0.8f, maxLines = 1)
        val w = f.width + h * 1.1f
        val body = ShapeNode(
            ShapeSpec.Rect(Anim.tween(h, w, at, at + 0.4f, Easing.ExpoOut), h.anim, (h / 2f * look.roundness.coerceIn(0.2f, 1f)).anim), fill = look.accent.fill(),
            transform = Transform(x = x.anim, y = y.anim, opacity = Anim.tween(0f, 1f, at, at + 0.12f)), name = "media-pill",
        )
        val label = TextNode(
            text, f.type, look.onAccent.fill(), rtl = cue.rtl,
            animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.2f), at = at + 0.15f, duration = 0.5f, stagger = 0.05f, ease = Easing.ExpoOut, clipToLine = true)),
            transform = Transform(x = x.anim, y = y.anim), name = "media-pill-text",
        )
        return Craft.onBody(Group(listOf(body, label), name = "media-pill-group"))
    }

    /** Leaves together: a small swell, then up and away, around the slot's centre. */
    private fun leave(cue: Cue, children: List<Node>, name: String, rise: Float = 0f): Group {
        val exitAt = cue.out - 0.3f
        return Group(
            children,
            transform = Transform(
                anchorX = 0.5f, anchorY = 0.5f, x = cue.x.anim, y = cue.y.anim + anim(0f, exitAt) { by(-rise * 0.4f, 0.3f, Easing.Exit) },
                scale = anim(1f, exitAt) { by(1.04f, 0.1f, Easing.ExpoOut); by(0.82f, 0.2f, Easing.Exit) },
                opacity = anim(1f, exitAt + 0.08f) { by(0f, 0.22f, Easing.Exit) },
            ),
            width = cue.x * 2f, height = cue.y * 2f,
            start = cue.at - 0.6f, end = cue.out + 0.02f, name = name,
        )
    }

    private const val LABEL_SHARE = 0.2f
    private const val PHONE_ASPECT = 0.85f
    private const val BAR = 0.075f
    private const val PAD = 1.3f
    private const val SAFE_TOP = 0.12f
    private val LIGHT_TILE = Color(0xFFF7F5F1)
    private val DARK_TILE = Color(0xFF16130F)
    private val DEVICE = Color(0xFF111214)
    private val DEVICE_RIM = Color(0xFF3A3B40)
    private val CHROME = Color(0xFFE9EAEE)
    private val ADDRESS_INK = Color(0xFF16181C)
    private val SECURE = Color(0xFF1E8E3E)

    val all: List<Recipe> = listOf(LogoMark, Product, Screenshot, Pip, BRoll, Url)
}
