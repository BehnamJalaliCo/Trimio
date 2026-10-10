package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Decoration
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Order
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.Shape
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.TypeSpec
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/** Shared craft rules for text recipes. */
internal object Craft {
    /** Entrances start two frames before the word so the word lands as it is spoken. */
    const val LEAD = 0.066f

    /** Calm → explosive: entrance durations shrink as energy rises. */
    fun pace(energy: Float, calm: Float, hot: Float) = calm + (hot - calm) * energy.coerceIn(0f, 1f)

    /** Headlines fill their slot: as large as the width and height allow. */
    fun big(cue: Cue, share: Float = 0.3f) = minOf(cue.width * share, cue.height * 0.6f)

    fun shadow(cue: Cue, look: Look, size: Float): Shadow? =
        if (cue.overMedia && look.textShadow.alpha > 0f) Shadow(look.textShadow, 0f, size * 0.05f, size * 0.32f) else null

    /** Unit start times from speech when known (one per word), else an even stagger from [at]. */
    fun wordStarts(cue: Cue, count: Int, stagger: Float): List<Float> =
        cue.wordTimes?.takeIf { it.size >= count }?.take(count)?.map { it - LEAD }
            ?: List(count) { cue.at - LEAD + it * stagger }

    /** The house exit: units lift, soften and fade in reading order, gone exactly at [Cue.out]. */
    fun exit(unit: TextUnit, cue: Cue, count: Int, lift: Float = -0.28f): TextAnimator {
        val duration = 0.26f
        val stagger = minOf(0.022f, 0.14f / count.coerceAtLeast(1))
        return TextAnimator(
            unit, UnitState(dy = lift, opacity = 0f, blur = 0.1f),
            at = cue.out - duration - stagger * (count - 1), duration = duration, stagger = stagger, ease = Easing.Exit, shape = Shape.Out,
        )
    }

    /** Emphasis marks on the words that matter, each when it is spoken (or after the entrance). */
    fun marks(cue: Cue, look: Look, starts: List<Float>, settle: Float): List<Decoration> {
        val style = when (cue.mark?.lowercase()) {
            "ink", "color", "colour" -> Look.Mark.Ink
            "underline", "line" -> Look.Mark.Underline
            "circle", "loop", "ring" -> Look.Mark.Circle
            "block", "highlight", "marker" -> Look.Mark.Block
            else -> look.mark
        }
        return groups(cue.emphasis).map { range ->
            val at = maxOf(starts.getOrElse(range.first) { cue.at } + settle, cue.at + settle)
            when (style) {
                Look.Mark.Block -> Decoration.Block(range, at, look.accent, look.onAccent, radius = 0.05f * look.roundness)
                Look.Mark.Ink -> Decoration.Ink(range, at, look.accent)
                Look.Mark.Underline -> Decoration.Underline(range, at, look.accent)
                Look.Mark.Circle -> Decoration.Circle(range, at, look.hot)
            }
        }
    }

    /** Adjacent emphasised words merge into one mark. */
    private fun groups(indices: Set<Int>): List<IntRange> {
        val sorted = indices.sorted()
        val out = mutableListOf<IntRange>()
        var start = -1
        var prev = -2
        for (i in sorted) {
            if (i != prev + 1 && start >= 0) out += start..prev
            if (i != prev + 1) start = i
            prev = i
        }
        if (start >= 0) out += start..prev
        return out
    }

    fun text(
        cue: Cue, type: TypeSpec, fill: Color, look: Look,
        animators: List<TextAnimator>, decorations: List<Decoration> = emptyList(), maxWidth: Float = cue.width, name: String,
    ) = TextNode(
        text = cue.text, type = type, fill = fill.fill(), maxWidth = maxWidth, align = TextAlign.Center, rtl = cue.rtl,
        animators = animators, decorations = decorations, shadow = shadow(cue, look, type.size),
        transform = Transform(x = cue.x.anim, y = cue.y.anim), start = cue.at - 0.5f, end = cue.out + 0.02f, name = name,
    )

    /** A short zoom punch: the frame "feels" the hit. */
    fun punch(at: Float, amount: Float) = CameraMove(
        zoom = io.trimio.engine.motion.anim(0f, at) { by(amount, 0.05f, Easing.ExpoOut); by(0f, 0.42f, Easing.SineInOut) },
    )

    /** A decaying handheld shake after an impact. */
    fun shake(at: Float, px: Float, seed: Int): CameraMove {
        val envelope = io.trimio.engine.motion.anim(0f, at) { by(1f, 0.02f, Easing.Linear); by(0f, 0.45f, Easing.ExpoOut) }
        return CameraMove(
            x = Anim.Noise(seed, 22f, envelope * px.anim),
            y = Anim.Noise(seed + 9, 19f, envelope * (px * 0.8f).anim),
            rotation = Anim.Noise(seed + 4, 14f, envelope * (px * 0.012f).anim),
        )
    }

    fun flash(at: Float, color: Color, peak: Float): Node =
        EffectNode(Effect.Flash(color, io.trimio.engine.motion.anim(0f, at - 0.001f) { by(peak, 0.001f, Easing.Hold); by(0f, 0.16f, Easing.ExpoOut) }), start = at - 0.01f, end = at + 0.2f)
}

/** The text recipes. Sizes come from the fitter; everything else is decided here. */
object TextRecipes {

    /** Words rise from behind their line, the signature headline move. */
    val MaskRise = object : Recipe("mask-rise", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline, Craft.big(cue), cue.width, cue.height, maxLines = 3)
            val n = cue.words.size
            val duration = Craft.pace(cue.energy, 0.8f, 0.5f)
            val starts = Craft.wordStarts(cue, n, Craft.pace(cue.energy, 0.09f, 0.05f))
            val rise = TextAnimator(TextUnit.Word, UnitState(dy = 1.35f, rotation = 3f), at = cue.at, duration = duration, ease = Easing.ExpoOut, times = starts, clipToLine = true)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(rise, Craft.exit(TextUnit.Word, cue, n)), Craft.marks(cue, look, starts, duration * 0.7f), name = name)
            return Built(listOf(node), sfx = listOf(Sfx(cue.at - Craft.LEAD, SfxKind.Swish, 0.5f + 0.4f * cue.energy)))
        }
    }

    /** Each word slams in huge and lands with weight; the camera and the sound feel it. */
    val Slam = object : Recipe("slam", Kind.Text) {
        override val preferredHeight = 0.3f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline, Craft.big(cue, 0.34f), cue.width, cue.height, maxLines = 2)
            val n = cue.words.size
            val starts = Craft.wordStarts(cue, n, 0.16f)
            val inDur = Craft.pace(cue.energy, 0.34f, 0.2f)
            val slam = TextAnimator(
                TextUnit.Word, UnitState(scale = 2.6f + cue.energy, opacity = 0f, blur = 0.06f), at = cue.at, duration = inDur,
                ease = Easing.BackOut(1.4f), times = starts,
            )
            val marks = Craft.marks(cue, look, starts, inDur)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(slam, Craft.exit(TextUnit.Word, cue, n)), marks, name = name)
            val lands = starts.map { it + inDur * 0.55f }
            val camera = lands.mapIndexed { i, t ->
                val strong = i in cue.emphasis || i == n - 1
                Craft.punch(t, (if (strong) 0.06f else 0.025f) * (0.5f + cue.energy))
            } + lands.filterIndexed { i, _ -> i in cue.emphasis }.map { Craft.shake(it, 10f * cue.energy, cue.seed + it.toInt()) }
            val overlays = if (cue.energy > 0.7f) lands.filterIndexed { i, _ -> i in cue.emphasis }.map { Craft.flash(it, Color.White, 0.22f) } else emptyList()
            val sfx = lands.mapIndexed { i, t -> Sfx(t, if (i in cue.emphasis) SfxKind.Boom else SfxKind.Hit, if (i in cue.emphasis) 1f else 0.6f) }
            return Built(listOf(node), camera, sfx, overlays)
        }
    }

    /** Spoken captions: each word pops in as it is said; the line clears before the next. */
    val PopCaptions = object : Recipe("pop-captions", Kind.Text) {
        override val preferredHeight = 0.16f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.caption, minOf(cue.width * 0.1f, 104f), cue.width, cue.height, maxLines = 2)
            val n = cue.words.size
            val starts = Craft.wordStarts(cue, n, 0.12f)
            val pop = TextAnimator(TextUnit.Word, UnitState(dy = 0.22f, scale = 0.55f, opacity = 0f), at = cue.at, duration = 0.34f, ease = Easing.Spring(0.5f, 2.6f), times = starts)
            val speak = TextAnimator(TextUnit.Word, UnitState(scale = 1.08f), at = cue.at, duration = 0.26f, ease = Easing.Linear, shape = Shape.Pulse, times = starts.map { it + 0.08f })
            val exit = TextAnimator(TextUnit.All, UnitState(dy = -0.12f, opacity = 0f), at = cue.out - 0.14f, duration = 0.14f, ease = Easing.Exit, shape = Shape.Out)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(pop, speak, exit), Craft.marks(cue, look, starts, 0.12f), name = name)
            return Built(listOf(node))
        }
    }

    /** Letters type on with a tiny overshoot, like a terminal with taste. */
    val TypeOn = object : Recipe("type-on", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.body, Craft.big(cue, 0.14f), cue.width, cue.height, maxLines = 3)
            val chars = cue.text.count { !it.isWhitespace() && it != '‌' }
            val span = cue.wordTimes?.let { (it.last() - it.first() + 0.25f).coerceAtLeast(0.3f) } ?: (chars * Craft.pace(cue.energy, 0.045f, 0.025f))
            val type = TextAnimator(TextUnit.Char, UnitState(scale = 1.35f, opacity = 0f), at = cue.at - Craft.LEAD, duration = 0.09f, stagger = span / chars.coerceAtLeast(1), ease = Easing.QuintOut)
            val marks = Craft.marks(cue, look, List(cue.words.size) { cue.at + span }, 0.05f)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(type, Craft.exit(TextUnit.Word, cue, cue.words.size)), marks, name = name)
            return Built(listOf(node), sfx = listOf(Sfx(cue.at, SfxKind.Tick, 0.4f)))
        }
    }

    /** Words resolve out of a soft blur, slow and expensive-looking. */
    val BlurIn = object : Recipe("blur-in", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline.copy(weight = look.headline.weight - 300), Craft.big(cue, 0.22f), cue.width, cue.height, maxLines = 3)
            val n = cue.words.size
            val starts = Craft.wordStarts(cue, n, 0.11f)
            val blur = TextAnimator(
                TextUnit.Word, UnitState(scale = 1.14f, opacity = 0f, blur = 0.3f), at = cue.at,
                duration = Craft.pace(cue.energy, 1.1f, 0.7f), ease = Easing.ExpoOut, times = starts,
            )
            val node = Craft.text(cue, f.type, look.ink, look, listOf(blur, Craft.exit(TextUnit.Word, cue, n, lift = -0.1f)), Craft.marks(cue, look, starts, 0.5f), name = name)
            return Built(listOf(node), sfx = listOf(Sfx(cue.at - 0.2f, SfxKind.Shimmer, 0.5f)))
        }
    }

    /** Words flip up in 3D around their baseline. */
    val Flip = object : Recipe("flip", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline, Craft.big(cue), cue.width, cue.height, maxLines = 3)
            val n = cue.words.size
            val starts = Craft.wordStarts(cue, n, 0.08f)
            val flip = TextAnimator(TextUnit.Word, UnitState(rotationX = -95f, dy = 0.3f, opacity = 0f), at = cue.at, duration = 0.7f, ease = Easing.Spring(0.62f, 1.7f), times = starts)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(flip, Craft.exit(TextUnit.Word, cue, n)), Craft.marks(cue, look, starts, 0.45f), name = name)
            return Built(listOf(node), sfx = listOf(Sfx(cue.at, SfxKind.Whoosh, 0.5f)))
        }
    }

    /** Letters burst from the centre of the word and settle with a snap. */
    val Spread = object : Recipe("spread", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline, Craft.big(cue), cue.width, cue.height, maxLines = 2)
            val chars = cue.text.count { !it.isWhitespace() && it != '‌' }
            val spread = TextAnimator(
                TextUnit.Char, UnitState(scale = 0.1f, opacity = 0f, blur = 0.08f, dy = 0.2f), at = cue.at - Craft.LEAD,
                duration = 0.5f, stagger = minOf(0.03f, 0.4f / chars.coerceAtLeast(1)), ease = Easing.BackOut(2.4f), order = Order.CenterOut,
            )
            val marks = Craft.marks(cue, look, List(cue.words.size) { cue.at }, 0.45f)
            val node = Craft.text(cue, f.type, look.ink, look, listOf(spread, Craft.exit(TextUnit.Word, cue, cue.words.size)), marks, name = name)
            return Built(listOf(node), sfx = listOf(Sfx(cue.at, SfxKind.Pop, 0.7f)))
        }
    }

    /**
     * Poster typography: one word per line, every line set to the same width (so short words get
     * huge), alternating weight, sliding in from alternating sides as each word is spoken.
     */
    val Stack = object : Recipe("stack", Kind.Text) {
        override val preferredHeight = 0.45f
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val words = lines(cue.words, MAX_STACK)
            val firstWord = words.runningFold(0) { acc, l -> acc + l.split(' ').size }
            val spoken = Craft.wordStarts(cue, cue.words.size, 0.14f)
            val starts = words.indices.map { spoken.getOrElse(firstWord[it]) { spoken.last() } }
            val target = cue.width
            val lines = words.mapIndexed { i, w ->
                // Alternate heavy and light cuts of the same face: contrast without a second voice.
                val voice = if (i % 2 == 0) look.headline else look.headline.copy(weight = (look.headline.weight - 500).coerceAtLeast(300))
                fit.fit(w, voice, 400f, target, cue.height / words.size * 1.6f, maxLines = 1)
            }
            // Scale every line to the stack width (justified poster), capped by the slot height.
            val heights = lines.map { it.type.size * it.type.lineHeight * (target / it.width).coerceAtMost(2.2f) }
            val shrink = (cue.height / heights.sum()).coerceAtMost(1f)
            var y = cue.y - heights.sum() * shrink / 2f
            val nodes = words.mapIndexed { i, w ->
                val l = lines[i]
                val k = (target / l.width).coerceAtMost(2.2f) * shrink
                val type = l.type.copy(size = l.type.size * k)
                val h = heights[i] * shrink
                val side = if (i % 2 == 0) 1f else -1f
                val emphasised = i in cue.emphasis
                val slide = TextAnimator(TextUnit.Line, UnitState(dx = 1.6f * side, opacity = 0f), at = starts[i], duration = 0.55f, ease = Easing.ExpoOut, times = listOf(starts[i]))
                val node = TextNode(
                    text = w, type = type, fill = (if (emphasised) look.accent else look.ink).fill(), rtl = cue.rtl,
                    animators = listOf(slide, Craft.exit(TextUnit.Line, cue, 1)), shadow = Craft.shadow(cue, look, type.size),
                    transform = Transform(x = cue.x.anim, y = (y + h / 2f).anim), start = cue.at - 0.5f, end = cue.out + 0.02f, name = "stack-$i",
                )
                y += h
                node
            }
            val sfx = starts.mapIndexed { i, t -> Sfx(t, SfxKind.Swish, 0.65f - 0.1f * i) }
            return Built(nodes, camera = starts.map { Craft.punch(it + 0.1f, 0.015f + 0.02f * cue.energy) }, sfx = sfx)
        }

        /** Groups words into at most [max] lines of similar length (short words share a line). */
        private fun lines(words: List<String>, max: Int): List<String> {
            if (words.size <= max) return words
            val target = words.sumOf { it.length } / max.toFloat()
            val out = mutableListOf<String>()
            var current = ""
            for ((i, w) in words.withIndex()) {
                current = if (current.isEmpty()) w else "$current $w"
                val wordsLeft = words.size - i - 1
                val linesLeft = max - out.size - 1
                val full = current.length >= target || wordsLeft == linesLeft
                if (full && wordsLeft > 0 && linesLeft > 0) { out += current; current = "" }
            }
            if (current.isNotEmpty()) out += current
            return out
        }
    }

    /** Lands with a burst of RGB split and jitter, then snaps clean. */
    val Glitch = object : Recipe("glitch", Kind.Text) {
        override fun build(cue: Cue, look: Look, fit: Fitter): Built {
            val f = fit.fit(cue.text, look.headline, Craft.big(cue), cue.width, cue.height, maxLines = 3)
            val at = cue.at - Craft.LEAD
            val burst = io.trimio.engine.motion.anim(1f, at) { by(1f, 0.28f, Easing.Hold); by(0f, 0.12f, Easing.ExpoOut) }
            fun ghost(color: Color, seed: Int) = TextNode(
                cue.text, f.type, color.fill(), maxWidth = cue.width, rtl = cue.rtl,
                transform = Transform(
                    x = (cue.x.anim + Anim.Noise(seed, 24f, burst * (f.type.size * 0.14f).anim)),
                    y = (cue.y.anim + Anim.Noise(seed + 3, 30f, burst * (f.type.size * 0.05f).anim)),
                    opacity = burst * 0.9f.anim,
                ),
                start = at, end = at + 0.42f, blend = BlendMode.Screen, name = "glitch-ghost",
            )
            val base = Craft.text(
                cue, f.type, look.ink, look,
                listOf(TextAnimator(TextUnit.Word, UnitState(opacity = 0f), at = at, duration = 0.001f, stagger = 0.05f, ease = Easing.Hold), Craft.exit(TextUnit.Word, cue, cue.words.size)),
                Craft.marks(cue, look, List(cue.words.size) { cue.at }, 0.35f), name = name,
            )
            val nodes = listOf(ghost(Color(0xFFFF2E4D), cue.seed), ghost(Color(0xFF2EE6FF), cue.seed + 11), base)
            return Built(listOf(Group(nodes, name = "glitch")), camera = listOf(Craft.shake(at, 6f, cue.seed)), sfx = listOf(Sfx(at, SfxKind.Click, 0.8f)))
        }
    }

    private const val MAX_STACK = 3

    val all: List<Recipe> = listOf(MaskRise, Slam, PopCaptions, TypeOn, BlurIn, Flip, Spread, Stack, Glitch)
}
