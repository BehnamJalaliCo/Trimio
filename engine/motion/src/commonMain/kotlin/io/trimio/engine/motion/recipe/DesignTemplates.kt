package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
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

object DesignTemplates {
    private const val ENTRY_SNAP = 0.22f
    private const val ENTRY_LIFT = 0.28f
    private const val ENTRY_GLIDE = 0.34f
    private const val EXIT_SHARE = 0.7f
    private const val STAGGER_TIGHT = 0.035f
    private const val STAGGER_SOFT = 0.055f
    private val SpringPremium = Easing.Spring(0.8f, 2.0f)
    private val SpringPlayful = Easing.Spring(0.55f, 2.2f)
    private const val FADE_LEAD = 0.05f
    private const val FOLLOW_LAG = 0.06f
    private const val FOLLOW_DAMP = 0.65f

    private fun exitSpan(entry: Float) = entry * EXIT_SHARE

    private fun layers(at: Float, entry: Float, primary: Anim, trailPeak: Float = 1.2f): Transform {
        val fade = anim(0f, at - FADE_LEAD) { by(1f, FADE_LEAD + entry * 1.5f, Easing.Linear) }
        val trail = Anim.Follow(anim(0f, at) { by(trailPeak, entry * 0.4f, Easing.ExpoOut); by(0f, entry * 0.6f, Easing.ExpoOut) }, FOLLOW_LAG, FOLLOW_DAMP)
        return Transform(x = primary, opacity = fade, blur = trail)
    }

    private fun exitOf(out: Float, entry: Float): Transform {
        val span = exitSpan(entry)
        val t = out - span
        return Transform(
            y = anim(0f, t) { by(-22f, span, Easing.Exit) },
            opacity = anim(1f, t) { by(0f, span, Easing.Exit) },
            blur = anim(0f, t) { by(2.5f, span, Easing.Exit) },
        )
    }

    private fun staged(cue: Cue, entry: Float, primary: Anim, children: List<Node>, name: String, at: Float, trailPeak: Float = 1.2f): Group {
        val enter = layers(at, entry, primary, trailPeak)
        val leave = exitOf(cue.out, entry)
        val box = Box.around(cue.x, cue.y, cue.width, cue.height)
        val pad = maxOf(40f, minOf(box.width, box.height) * 0.12f)
        val b = Box(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
        val inner = Group(children, Transform(x = (-b.left).anim, y = (-b.top).anim, anchorX = 0f, anchorY = 0f), name = "$name-body")
        val t = enter.copy(
            x = b.centerX.anim + enter.x, y = b.centerY.anim + leave.y,
            anchorX = 0.5f, anchorY = 0.5f,
            opacity = enter.opacity * leave.opacity, blur = enter.blur + leave.blur,
        )
        return Group(listOf(inner), transform = t, width = b.width, height = b.height, start = at - 0.05f, end = cue.out + 0.02f, name = name)
    }

    private fun edge(cue: Cue): Float = if (cue.rtl) cue.x + cue.width / 2f else cue.x - cue.width / 2f
    private fun dir(cue: Cue): Float = if (cue.rtl) -1f else 1f

    val NameBar = object : Recipe("name-bar", Kind.Element) {
        override val preferredHeight = 0.12f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_LIFT
            val d = dir(cue)
            val e = edge(cue)
            val barW = maxOf(6f, cue.height * 0.07f)
            val nameF = fit.fit(name, look.headline, cue.height * 0.42f, cue.width * 0.82f, cue.height * 0.5f, maxLines = 1)
            val roleF = role?.let { fit.fit(it, look.body, nameF.type.size * 0.42f, cue.width * 0.82f, cue.height * 0.28f, maxLines = 1) }
            val textEdge = e + d * (barW * 2.2f)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(ShapeSpec.Rect(barW.anim, Anim.tween(0f, cue.height * 0.88f, at, at + entry, Easing.ExpoOut), (barW / 2f).anim), fill = look.accent.fill(), transform = Transform(x
                = e.anim, y = cue.y.anim),
                name = "name-bar")
            nodes += TextNode(name, nameF.type, look.ink.fill(), rtl = cue.rtl, animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.3f), at = at + 0.06f, duration = entry * 1.6f,
                stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, clipToLine = true)),
                shadow = Craft.shadow(cue, look, nameF.type.size), transform = Transform(x = (textEdge + d * nameF.width / 2f).anim, y = (cue.y - cue.height * 0.12f).anim), name = "name")
            if (role != null && roleF != null) {
                nodes += TextNode(role, roleF.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, roleF.type.size), transform = Transform(x = (textEdge + d *
                    roleF.width / 2f).anim + Anim.tween(-d * 24f, 0f, at + 0.12f, at + 0.12f + entry * 1.4f, Easing.ExpoOut), y = (cue.y + cue.height * 0.24f).anim, opacity = Anim.tween(0f,
                        1f, at +
                    0.12f, at + 0.12f + entry * 1.2f, Easing.ExpoOut)),
                    name = "role")
                nodes += ShapeNode(ShapeSpec.Rect((roleF.width * 0.35f).anim, 3f.anim, 1.5f.anim), fill = look.accent.fill(), transform = Transform(x = (textEdge + d * roleF.width * 0.18f).anim,
                    y = (cue.y + cue.height * 0.4f).anim, scaleX = Anim.tween(0f, 1f, at + 0.22f, at + 0.22f + entry, Easing.ExpoOut)),
                    name = "name-tick")
            }
            val travel = Anim.tween(d * 36f, 0f, at, at + entry, Easing.ExpoOut)
            val box = Box(minOf(e, e + d * (barW * 2.2f + maxOf(nameF.width, roleF?.width ?: 0f))), cue.y - cue.height * 0.5f, maxOf(e, e + d * (barW * 2.2f + maxOf(nameF.width, roleF?.width ?:
                0f))),
                cue.y + cue.height * 0.5f)
            return Built(listOf(staged(cue, entry, travel, nodes, name, at)), sfx = listOf(Sfx(cue.at, SfxKind.Swish, 0.45f)), box = box)
        }
    }

    val NamePlate = object : Recipe("name-plate", Kind.Element) {
        override val preferredHeight = 0.14f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_GLIDE
            val d = dir(cue)
            val h = minOf(cue.height * 0.92f, cue.width * 0.2f)
            val nameF = fit.fit(name, look.headline, h * (if (role != null) 0.32f else 0.4f), cue.width * 0.72f, h * 0.48f, maxLines = 1)
            val roleF = role?.let { fit.fit(it, look.body, h * 0.2f, cue.width * 0.72f, h * 0.3f, maxLines = 1) }
            val stripeW = h * 0.12f
            val pad = h * 0.22f
            val w = stripeW + pad + maxOf(nameF.width, roleF?.width ?: 0f) + pad
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val left = if (cue.rtl) slot.right - w else slot.left
            val plate = Box(left, cue.y - h / 2f, left + w, cue.y + h / 2f)
            val s = Kit.surface(look, cue)
            val ink = Kit.inkOn(look, s.ground)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(ShapeSpec.Rect(w.anim, h.anim, (h * 0.18f * look.roundness).anim), fill = if (cue.overMedia) look.canvas.copy(alpha = 0.92f).fill() else s.fill, stroke =
                Stroke(look.cardRim.fill(), 2f),
                shadow = Shadow(Color.Black.copy(alpha = if (Kit.light(look.canvas)) 0.18f else 0.36f), 0f, 14f, 32f), transform = Transform(x = plate.centerX.anim, y = plate.centerY.anim),
                name = "plate")
            val stripeX = if (cue.rtl) plate.right - stripeW / 2f else plate.left + stripeW / 2f
            nodes += ShapeNode(ShapeSpec.Rect(stripeW.anim, (h * 0.72f).anim, (stripeW / 2f).anim), fill = look.accent.fill(), transform = Transform(x = stripeX.anim, y = plate.centerY.anim),
                name = "plate-stripe")
            val textEdge = stripeX + d * (stripeW / 2f + pad * 0.6f)
            val ground = if (cue.overMedia) Kit.over(look.canvas.copy(alpha = 0.92f), look.canvas) else s.ground
            nodes += TextNode(name, nameF.type, (if (cue.overMedia) look.ink else ink).fill(), rtl = cue.rtl, animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.2f), at = at + 0.08f,
                duration = entry * 1.3f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, clipToLine = true)),
                shadow = Craft.shadow(cue, look, nameF.type.size), transform = Transform(x = (textEdge + d * nameF.width / 2f).anim, y = (if (role != null) plate.centerY - h * 0.12f else
                    plate.centerY).anim),
                name = "name")
            if (role != null && roleF != null) {
                nodes += TextNode(role, roleF.type, (if (cue.overMedia) look.muted.copy(alpha = 0.95f) else Kit.quiet(look, ground)).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look,
                    roleF.type.size),
                    transform = Transform(x = (textEdge + d * roleF.width / 2f).anim, y = (plate.centerY + h * 0.18f).anim, opacity = Anim.tween(0f, 1f, at + 0.16f, at + 0.16f + entry,
                        Easing.ExpoOut)),
                    name = "role")
            }
            val travel = Anim.tween(-d * minOf(w * 0.55f, 90f), 0f, at, at + entry, SpringPremium)
            return Built(listOf(staged(cue, entry, travel, nodes, name, at, trailPeak = 0.8f)), sfx = listOf(Sfx(cue.at, SfxKind.Swish, 0.5f)), box = plate)
        }
    }

    val NameHero = object : Recipe("name-hero", Kind.Element) {
        override val preferredHeight = 0.16f
        override val minHold = 2.8f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val name = cue.text.ifBlank { cue.label ?: return Built(emptyList()) }
            val role = cue.label?.takeIf { cue.text.isNotBlank() }
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_SNAP
            val d = dir(cue)
            val h = minOf(cue.height * 0.95f, cue.width * 0.22f)
            val nameF = fit.fit(name, look.headline, h * 0.38f, cue.width * 0.78f, h * 0.5f, maxLines = 1)
            val roleF = role?.let { fit.fit(it, look.body, h * 0.2f, cue.width * 0.7f, h * 0.28f, maxLines = 1) }
            val pad = h * 0.28f
            val blockW = nameF.width + pad * 2f
            val blockH = h * (if (role != null) 0.58f else 0.7f)
            val slot = Box.around(cue.x, cue.y, cue.width, cue.height)
            val left = if (cue.rtl) slot.right - blockW else slot.left
            val blockY = cue.y - (if (role != null) h * 0.14f else 0f)
            val accent = Kit.surface(look, cue, Kit.Tone.Accent)
            val nodes = mutableListOf<Node>()
            nodes += ShapeNode(ShapeSpec.Rect(Anim.tween(h * 0.3f, blockW, at, at + entry * 1.2f, Easing.ExpoOut), blockH.anim, (h * 0.08f * look.roundness).anim), fill = accent.fill,
                transform = Transform(x = (if (cue.rtl) left + blockW else left).anim, y = blockY.anim, anchorX = if (cue.rtl) 1f else 0f), name = "hero-block")
            val nameCx = if (cue.rtl) left + blockW - pad - nameF.width / 2f else left + pad + nameF.width / 2f
            nodes += TextNode(name, nameF.type, Kit.inkOn(look, accent.ground).fill(), rtl = cue.rtl, animators = listOf(
                    TextAnimator(TextUnit.Word, UnitState(scale = 1.35f), at = at + 0.04f, duration = entry * 1.4f, stagger = STAGGER_TIGHT, ease = SpringPlayful),
                    TextAnimator(TextUnit.Word, UnitState(opacity = 0f), at = at + 0.04f, duration = entry * 1.4f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut),
                ),
                transform = Transform(x = nameCx.anim, y = blockY.anim), name = "name")
            if (role != null && roleF != null) {
                nodes += TextNode(role, roleF.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, roleF.type.size), transform = Transform(x = nameCx.anim, y =
                    (blockY + blockH / 2f + roleF.height * 0.9f).anim, opacity = Anim.tween(0f, 1f, at + 0.14f, at + 0.14f + entry * 1.3f, Easing.ExpoOut)),
                    name = "role")
            }
            val tag = h * 0.16f
            nodes += ShapeNode(ShapeSpec.Rect(tag.anim, tag.anim, (tag * 0.25f).anim), fill = look.hot.fill(), transform = Transform(x = (if (cue.rtl) left + blockW + tag * 0.2f else left - tag *
                0.2f).anim, y = (blockY - blockH / 2f - tag * 0.2f).anim, scale = Anim.tween(0f, 1f, at + 0.18f, at + 0.18f + entry * 1.2f, SpringPlayful), rotation = Anim.tween(-30f, -12f, at +
                0.18f, at + 0.18f + entry * 1.5f, Easing.ExpoOut)),
                name = "hero-tag")
            val travel = Anim.tween(d * 28f, 0f, at, at + entry, SpringPlayful)
            val box = Box(minOf(left, left + blockW), blockY - blockH / 2f - tag, maxOf(left, left + blockW), blockY + blockH / 2f + (roleF?.height?.times(1.8f) ?: 0f))
            return Built(listOf(staged(cue, entry, travel, nodes, name, at, trailPeak = 1.0f)), camera = listOf(Craft.punch(at + entry * 0.7f, 0.018f + 0.012f * cue.energy)), sfx =
                listOf(Sfx(cue.at, SfxKind.Hit, 0.55f)),
                box = box)
        }
    }

    val WordCascade = object : Recipe("word-cascade", Kind.Text) {
        override val preferredHeight = 0.28f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_LIFT
            val words = cue.words
            val f = fit.fit(cue.text, look.headline, Craft.big(cue, 0.3f), cue.width, cue.height, maxLines = 3)
            val starts = Craft.wordStarts(cue, words.size, STAGGER_SOFT)
            val cascade = TextAnimator(TextUnit.Word, UnitState(dy = 1.35f, rotation = 2.5f), at = at, duration = entry * 1.7f, stagger = STAGGER_SOFT, ease = Easing.ExpoOut, times = starts,
                clipToLine = true)
            val leave = Craft.exit(TextUnit.Word, cue, words.size)
            val marks = Craft.marks(cue, look, starts, entry * 0.8f)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(cascade, leave), marks, name = name)
            val travel = Anim.tween(dir(cue) * 24f, 0f, at, at + entry, Easing.ExpoOut)
            return Built(listOf(staged(cue, entry, travel, listOf(node), name, at)), sfx = starts.mapIndexed { i, t -> Sfx(t, SfxKind.Swish, 0.35f + 0.05f * (i % 2)) }, box = Box.around(cue.x,
                cue.y, cue.width * 0.92f, cue.height))
        }
    }

    val KeywordPop = object : Recipe("keyword-pop", Kind.Text) {
        override val preferredHeight = 0.26f
        override val minHold = 2.2f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_SNAP
            val words = cue.words
            val key = cue.emphasis.firstOrNull() ?: (words.size - 1).coerceAtLeast(0)
            val f = fit.fit(cue.text, look.headline, Craft.big(cue, 0.3f), cue.width, cue.height, maxLines = 2)
            val starts = Craft.wordStarts(cue, words.size, STAGGER_TIGHT)
            val quiet = TextAnimator(TextUnit.Word, UnitState(dy = 0.35f, opacity = 0f), at = at, duration = entry * 1.5f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, times = starts)
            val keyAt0 = starts.getOrElse(key) { at }
            val pop = TextAnimator(TextUnit.Word, UnitState(scale = 0.55f), at = keyAt0, duration = entry * 1.8f, ease = SpringPlayful, times = listOf(keyAt0))
            val popFade = TextAnimator(TextUnit.Word, UnitState(opacity = 0f), at = keyAt0, duration = entry * 1.8f, ease = Easing.ExpoOut, times = listOf(keyAt0))
            val leave = Craft.exit(TextUnit.Word, cue, words.size)
            val marks = Craft.marks(cue.copy(emphasis = setOf(key)), look, starts, entry * 0.7f)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(quiet, pop, popFade, leave), marks, name = name)
            val lineWidth = f.width * 0.22f
            val keyAt = starts.getOrElse(key) { at }
            val underline = ShapeNode(ShapeSpec.Rect(lineWidth.anim, 4f.anim, 2f.anim), fill = look.accent.fill(), transform = Transform(x = cue.x.anim, y = (cue.y + f.height * 0.62f).anim,
                scaleX = Anim.tween(0f, 1f, keyAt + entry * 0.5f, keyAt + entry * 1.5f, Easing.ExpoOut)),
                start = at, end = cue.out + 0.02f, name = "keyword-underline")
            val travel = Anim.tween(dir(cue) * 24f, 0f, at, at + entry, Easing.ExpoOut)
            return Built(listOf(staged(cue, entry, travel, listOf(node, underline), name, at)), sfx = listOf(Sfx(keyAt + Craft.LEAD, SfxKind.Pop, 0.55f)), box = Box.around(cue.x, cue.y, cue.width
                * 0.92f, cue.height))
        }
    }

    val WordBlocks = object : Recipe("word-blocks", Kind.Text) {
        override val preferredHeight = 0.3f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_GLIDE
            val words = cue.words.take(4)
            val d = dir(cue)
            val rowH = cue.height / words.size
            val nodes = mutableListOf<Node>()
            val starts = mutableListOf<Float>()
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for ((i, w) in words.withIndex()) {
                val t0 = at + i * STAGGER_SOFT
                starts += t0
                val weight = (look.headline.weight - 200 + (i % 2) * 300).coerceIn(400, 1000)
                val f = fit.fit(w, look.headline.copy(weight = weight), rowH * 0.42f, cue.width * 0.85f, rowH * 0.75f, maxLines = 1)
                val blockW = f.width + rowH * 0.5f
                val blockH = rowH * 0.72f
                val y = cue.y - cue.height / 2f + rowH * (i + 0.5f)
                val x = cue.x - d * (cue.width / 2f - blockW / 2f - rowH * 0.15f) + d * (i % 2) * cue.width * 0.04f
                left = minOf(left, x - blockW / 2f)
                right = maxOf(right, x + blockW / 2f)
                top = minOf(top, y - blockH / 2f)
                bottom = maxOf(bottom, y + blockH / 2f)
                val s = if (i % 2 == 0) Kit.surface(look, cue, Kit.Tone.Accent) else Kit.surface(look, cue)
                val ink = Kit.inkOn(look, s.ground)
                nodes += Group(listOf(ShapeNode(ShapeSpec.Rect(blockW.anim, blockH.anim, (blockH * 0.16f * look.roundness).anim), fill = s.fill, stroke = s.stroke, shadow =
                    Shadow(Color.Black.copy(alpha = 0.28f), 0f, 10f, 22f), transform = Transform(x = 0f.anim, y = 0f.anim), name = "word-block"), Kit.words(w, f, ink, 0f, 0f, t0 + 0.06f,
                        s.ground,
                    cue.rtl, Kit.Reveal.None, name = "word")),
                    transform = Transform(x = x.anim + Anim.tween(-d * 50f, 0f, t0, t0 + entry * 1.4f, SpringPremium), y = y.anim, rotation = Anim.tween((if (i % 2 == 0) -4f else 3f) * d, 0f, t0,
                        t0 + entry * 1.6f, Easing.ExpoOut),
                        opacity = Anim.tween(0f, 1f, t0 - FADE_LEAD, t0 + entry * 0.5f, Easing.ExpoOut), blur = Anim.Follow(anim(0f, t0) { by(1.0f, entry * 0.4f, Easing.ExpoOut); by(0f, entry *
                            0.7f, Easing.ExpoOut) },
                            FOLLOW_LAG, FOLLOW_DAMP)),
                    start = t0 - 0.05f, end = cue.out + 0.02f, name = "word-card-$i")
            }
            val leaveSpan = exitSpan(entry)
            val leaveT = cue.out - leaveSpan
            val enterY = Anim.tween(-18f, 0f, at, at + entry, Easing.ExpoOut)
            val enterOp = anim(0f, at - FADE_LEAD) { by(1f, FADE_LEAD + entry * 1.5f, Easing.Linear) }
            val leaveY = anim(0f, leaveT) { by(-18f, leaveSpan, Easing.Exit) }
            val leaveOp = anim(1f, leaveT) { by(0f, leaveSpan, Easing.Exit) }
            val root = Group(nodes, transform = Transform(y = enterY + leaveY, opacity = enterOp * leaveOp), start = at - 0.05f, end = cue.out + 0.02f, name = name)
            return Built(listOf(root), sfx = starts.map { Sfx(it, SfxKind.Click, 0.35f) }, box = Box(left, top, right, bottom))
        }
    }

    val Payoff = object : Recipe("payoff", Kind.Element) {
        override val preferredHeight = 0.32f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_SNAP
            val headline = cue.label ?: cue.text.ifBlank { return Built(emptyList()) }
            val context = cue.text.takeIf { !cue.label.isNullOrBlank() && it.isNotBlank() }
            val value = cue.value
            val nodes = mutableListOf<Node>()
            val h = cue.height
            if (value != null) {
                val sample = cue.prefix + Kit.format(value, cue.decimals, false) + cue.suffix
                val nf = fit.fit(sample, look.number, h * 0.42f, cue.width * 0.9f, h * 0.5f, maxLines = 1)
                nodes += CounterNode(value = Anim.tween(value * 0.35f, value, at, at + entry * 2.2f, Easing.ExpoOut), decimals = cue.decimals, prefix = cue.prefix, suffix = cue.suffix,
                    persianDigits = cue.rtl, type = nf.type, fill = look.ink.fill(), shadow = Craft.shadow(cue, look, nf.type.size), transform = Transform(x = cue.x.anim, y = (cue.y - h *
                        0.12f).anim,
                        scale = anim(0.7f, at) { by(1f, entry * 1.8f, SpringPlayful) }, opacity = Anim.tween(0f, 1f, at - FADE_LEAD, at + entry * 0.5f, Easing.ExpoOut)),
                    name = "payoff-number")
                nodes += ShapeNode(ShapeSpec.Rect((nf.width * 0.55f).anim, 6f.anim, 3f.anim), fill = look.accent.fill(), transform = Transform(x = cue.x.anim, y = (cue.y + h * 0.12f).anim, scaleX
                    = Anim.tween(0f, 1f, at + entry * 0.6f, at + entry * 1.8f, Easing.ExpoOut)),
                    name = "payoff-rule")
            } else {
                val hf = fit.fit(headline, look.headline, h * 0.36f, cue.width * 0.92f, h * 0.48f, maxLines = 2)
                nodes += TextNode(headline, hf.type, look.ink.fill(), rtl = cue.rtl, animators = listOf(
                    TextAnimator(TextUnit.Word, UnitState(scale = 1.4f), at = at, duration = entry * 1.5f, stagger = STAGGER_TIGHT, ease = SpringPlayful),
                    TextAnimator(TextUnit.Word, UnitState(opacity = 0f), at = at, duration = entry * 1.5f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut),
                ),
                    shadow = Craft.shadow(cue, look, hf.type.size), transform = Transform(x = cue.x.anim, y = (cue.y - h * 0.08f).anim), name = "payoff-headline")
            }
            val under = context ?: (if (value != null) headline.takeIf { it != cue.label } else null)
            if (!under.isNullOrBlank()) {
                val cf = fit.fit(under, look.body, h * 0.14f, cue.width * 0.85f, h * 0.22f, maxLines = 2)
                nodes += TextNode(under, cf.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, cf.type.size), animators = listOf(TextAnimator(TextUnit.Word,
                    UnitState(dy = 0.4f, opacity = 0f), at = at + 0.18f, duration = entry * 1.6f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut)),
                    transform = Transform(x = cue.x.anim, y = (cue.y + h * 0.28f).anim), name = "payoff-context")
            }
            val travel = Anim.tween(0f, 0f, at, at + entry, Easing.ExpoOut)
            return Built(listOf(staged(cue, entry, travel, nodes, name, at, trailPeak = 0.6f)), camera = listOf(Craft.punch(at + entry * 0.8f, 0.03f + 0.025f * cue.energy)), sfx =
                listOf(Sfx(cue.at, SfxKind.Hit, 0.7f), Sfx(at + entry * 0.9f, SfxKind.Shimmer, 0.35f)),
                box = Box.around(cue.x, cue.y, cue.width * 0.95f, cue.height))
        }
    }

    val TitleSlam = object : Recipe("title-slam", Kind.Text) {
        override val preferredHeight = 0.3f
        override val minHold = 2.4f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            if (cue.text.isBlank()) return Built(emptyList())
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_SNAP
            val words = cue.words
            val f = fit.fit(cue.text, look.headline, Craft.big(cue, 0.34f), cue.width, cue.height, maxLines = 2)
            val starts = Craft.wordStarts(cue, words.size, STAGGER_TIGHT)
            val slam = TextAnimator(TextUnit.Word, UnitState(scale = 2.2f + cue.energy * 0.6f), at = at, duration = entry * 1.3f, stagger = STAGGER_TIGHT, ease = SpringPlayful,
                times = starts)
            val slamFade = TextAnimator(TextUnit.Word, UnitState(opacity = 0f), at = at, duration = entry * 1.3f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut, times = starts)
            val leave = Craft.exit(TextUnit.Word, cue, words.size)
            val marks = Craft.marks(cue, look, starts, entry * 0.6f)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(slam, slamFade, leave), marks, name = name)
            val land = starts.last() + entry * 0.7f
            val travel = Anim.tween(dir(cue) * 26f, 0f, at, at + entry, Easing.ExpoOut)
            return Built(listOf(staged(cue, entry, travel, listOf(node), name, at)), camera = listOf(Craft.punch(land, 0.04f + 0.03f * cue.energy)), sfx = listOf(Sfx(land, SfxKind.Hit, 0.75f)),
                overlays = if (cue.energy > 0.55f) listOf(Craft.flash(land, look.accent, 0.14f)) else emptyList(),
                box = Box.around(cue.x, cue.y, f.width, f.height))
        }
    }

    val StatPulse = object : Recipe("stat-pulse", Kind.Element) {
        override val preferredHeight = 0.24f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_LIFT
            val v = cue.value ?: return Built(emptyList())
            val label = cue.label ?: cue.text.takeIf { it.isNotBlank() }
            val sample = cue.prefix + Kit.format(v, cue.decimals, false) + cue.suffix
            val nf = fit.fit(sample, look.number, cue.height * 0.52f, cue.width * 0.85f, cue.height * 0.55f, maxLines = 1)
            val lf = label?.let { fit.fit(it, look.body, nf.type.size * 0.28f, cue.width * 0.85f, cue.height * 0.22f, maxLines = 1) }
            val numberY = if (lf != null) cue.y - cue.height * 0.1f else cue.y
            val nodes = mutableListOf<Node>()
            nodes += CounterNode(value = Anim.tween(v * 0.2f, v, at, at + entry * 2.4f, Easing.ExpoOut), decimals = cue.decimals, prefix = cue.prefix, suffix = cue.suffix, persianDigits =
                cue.rtl,
                type = nf.type, fill = look.ink.fill(), shadow = Craft.shadow(cue, look, nf.type.size), transform = Transform(x = cue.x.anim, y = numberY.anim, scale = anim(0.82f, at) { by(1f,
                    entry * 1.8f, SpringPremium) },
                    opacity = Anim.tween(0f, 1f, at - FADE_LEAD, at + entry * 0.5f, Easing.ExpoOut)),
                name = "stat-number")
            val d = dir(cue)
            nodes += ShapeNode(ShapeSpec.Rect((nf.width * 0.4f).anim, 5f.anim, 2.5f.anim), fill = look.accent.fill(), transform = Transform(x = (cue.x + d * (nf.width * 0.62f)).anim, y =
                numberY.anim, scaleX = Anim.tween(0f, 1f, at + entry * 0.5f, at + entry * 1.6f, Easing.ExpoOut)),
                name = "stat-rule")
            if (label != null && lf != null) {
                nodes += TextNode(label, lf.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size), animators = listOf(TextAnimator(TextUnit.Word,
                    UnitState(dy = 0.35f, opacity = 0f), at = at + 0.12f, duration = entry * 1.5f, stagger = STAGGER_TIGHT, ease = Easing.ExpoOut)),
                    transform = Transform(x = cue.x.anim, y = (numberY + nf.height * 0.62f + lf.height * 0.55f).anim), name = "stat-label")
            }
            val land = at + entry * 2.4f
            val pulse = anim(1f, land + 0.35f) { by(1.025f, 0.12f, Easing.SineInOut); by(1f, 0.28f, Easing.SineInOut) }
            val travel = Anim.tween(0f, 0f, at, at + entry, Easing.ExpoOut)
            val group = staged(cue, entry, travel, nodes, name, at, trailPeak = 0.5f)
            val enterOp = anim(0f, at - FADE_LEAD) { by(1f, FADE_LEAD + entry * 1.5f, Easing.Linear) }
            return Built(listOf(Group(listOf(group), transform = Transform(x = cue.x.anim, y = cue.y.anim, anchorX = 0.5f, anchorY = 0.5f, scale = pulse, opacity = enterOp), start = at - 0.05f,
                end = cue.out + 0.02f, name = "stat-pulse")),
                sfx = listOf(Sfx(at, SfxKind.Riser, 0.35f), Sfx(land, SfxKind.Pop, 0.55f)),
                box = Box.around(cue.x, cue.y, cue.width * 0.9f, cue.height))
        }
    }

    val StatBars = object : Recipe("stat-bars", Kind.Element) {
        override val preferredHeight = 0.26f
        override val minHold = 2.6f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val values = cue.points?.takeIf { it.isNotEmpty() } ?: listOf(3f, 5f, 4f, 8f)
            val at = cue.at - Craft.LEAD
            val entry = ENTRY_GLIDE
            val maxV = values.max().coerceAtLeast(1e-6f)
            val n = values.size
            val gap = cue.width * 0.045f
            val bw = (cue.width - gap * (n - 1)) / n
            val base = cue.y + cue.height / 2f
            val best = values.indexOf(values.max())
            val nodes = mutableListOf<Node>()
            val labels = cue.items
            for (i in 0 until n) {
                val v = values[i]
                val bh = cue.height * 0.82f * v / maxV
                val t0 = at + i * STAGGER_SOFT
                val grow = entry * (2.0f + 0.15f * i)
                val x = cue.x - cue.width / 2f + bw / 2f + i * (bw + gap)
                val isBest = i == best
                val color = if (isBest) look.accent else look.ink.copy(alpha = 0.82f)
                nodes += ShapeNode(ShapeSpec.Rect(bw.anim, Anim.tween(0f, bh, t0, t0 + grow, SpringPremium), (bw * 0.14f * look.roundness).anim), fill = color.fill(), transform = Transform(x =
                    x.anim, y = base.anim, anchorY = 1f),
                    name = "bar-$i")
                if (isBest && !Kit.light(look.canvas)) {
                    nodes += ShapeNode(ShapeSpec.Ellipse((bw * 0.55f).anim, (bw * 0.55f).anim), fill = Fill.Radial(listOf(look.accent.copy(alpha = 0.55f), look.accent.copy(alpha = 0.18f),
                        Color.Transparent)),
                        transform = Transform(x = x.anim, y = base.anim + Anim.tween(0f, -bh, t0, t0 + grow, SpringPremium), opacity = anim(0f, t0) { by(1f, entry * 0.4f); hold(t0 + grow); by(0f,
                            0.4f) }),
                        start = t0, end = t0 + grow + 0.4f, name = "bar-head")
                }
                val label = labels.getOrNull(i)
                if (label != null) {
                    val lf = fit.fit(label, look.body, maxOf(bw * 0.28f, cue.width * Craft.MIN_LABEL), bw * 0.95f, cue.height * 0.12f, maxLines = 1)
                    nodes += TextNode(label, lf.type, Craft.secondary(cue, look).fill(), rtl = cue.rtl, shadow = Craft.shadow(cue, look, lf.type.size), animators =
                        listOf(TextAnimator(TextUnit.All, UnitState(dy = 0.25f, opacity = 0f), at = t0 + 0.1f, duration = entry * 1.2f, ease = Easing.ExpoOut)),
                        transform = Transform(x = x.anim, y = (base + cue.height * 0.08f).anim), name = "bar-label-$i")
                }
            }
            val leaveSpan = exitSpan(entry)
            val leaveT = cue.out - leaveSpan
            val enterY = Anim.tween(-16f, 0f, at, at + entry, Easing.ExpoOut)
            val enterOp = anim(0f, at - FADE_LEAD) { by(1f, FADE_LEAD + entry * 1.5f, Easing.Linear) }
            val leaveY = anim(0f, leaveT) { by(-16f, leaveSpan, Easing.Exit) }
            val leaveOp = anim(1f, leaveT) { by(0f, leaveSpan, Easing.Exit) }
            val leaveBlur = anim(0f, leaveT) { by(2f, leaveSpan, Easing.Exit) }
            val root = Group(nodes, transform = Transform(y = enterY + leaveY, opacity = enterOp * leaveOp, blur = leaveBlur), start = at - 0.05f, end = cue.out + 0.02f, name = name)
            val ticks = (0 until n).map { Sfx(at + it * STAGGER_SOFT, SfxKind.Tick, 0.32f) } + Sfx(at + entry * 2.2f, SfxKind.Pop, 0.5f)
            return Built(listOf(root), sfx = ticks, box = Box(cue.x - cue.width / 2f, cue.y - cue.height * 0.35f, cue.x + cue.width / 2f, cue.y + cue.height * 0.7f))
        }
    }

    val all: List<Recipe> = listOf(NameBar, NamePlate, NameHero, WordCascade, KeywordPop, WordBlocks, Payoff, TitleSlam, StatPulse, StatBars)

    val aliases: Map<String, String> = mapOf(
        "lt-simple" to "name-bar", "lower-third-simple" to "name-bar", "namebar" to "name-bar",
        "lt-mid" to "name-plate", "lower-third-plate" to "name-plate", "nameplate" to "name-plate",
        "lt-hero" to "name-hero", "lower-third-hero" to "name-hero",
        "cascade" to "word-cascade", "word-cascade-type" to "word-cascade",
        "pop-keyword" to "keyword-pop", "keyword" to "keyword-pop",
        "text-blocks" to "word-blocks", "word-cards" to "word-blocks",
        "payoff-first" to "payoff", "hook-payoff" to "payoff", "result-first" to "payoff",
        "hook-slam" to "title-slam", "title-hit" to "title-slam",
        "counter-pulse" to "stat-pulse", "stat-micro" to "stat-pulse",
        "bar-chart" to "stat-bars", "bars-micro" to "stat-bars",
    )
}
