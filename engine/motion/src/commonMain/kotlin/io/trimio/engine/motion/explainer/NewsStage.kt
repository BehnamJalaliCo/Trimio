package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.recipe.Box
import io.trimio.engine.motion.explainer.FilmRules.Zone

/**
 * Where a news scene puts things: the words at the top (from the reading start), one device in the
 * middle, the hero below rising from (and allowed to cross) the frame's bottom edge; on a wide frame
 * the words and the device share the reading-start column and the hero takes the other side.
 */
internal class NewsFrame(val frame: StageFrame) {
    private val w = frame.width
    private val h = frame.height
    private val safe = frame.safe
    val portrait = frame.portrait

    val words: Box = if (portrait) Box(safe.left, safe.top + h * 0.01f, safe.right, h * 0.29f) else column(h * 0.1f, h * 0.48f)

    fun device(withHero: Boolean): Box = when {
        portrait && withHero -> Box(safe.left, h * 0.31f, safe.right, h * 0.55f)
        portrait -> Box(safe.left, h * 0.32f, safe.right, safe.bottom - h * 0.03f)
        withHero -> column(h * 0.52f, safe.bottom)
        else -> other(h * 0.12f, safe.bottom)
    }

    fun hero(withDevice: Boolean): Box = when {
        portrait && withDevice -> Box(safe.left, h * 0.58f, safe.right, h * BLEED)
        portrait -> Box(safe.left, h * 0.33f, safe.right, h * BLEED)
        else -> other(h * 0.1f, h * BLEED)
    }

    /** The reading-start column of a wide frame, and the other side. */
    private fun column(top: Float, bottom: Float) = if (frame.rtl) Box(w * 0.5f, top, safe.right, bottom) else Box(safe.left, top, w * 0.5f, bottom)
    private fun other(top: Float, bottom: Float) = if (frame.rtl) Box(safe.left, top, w * 0.48f, bottom) else Box(w * 0.52f, top, safe.right, bottom)

    companion object {
        /** A hero stands a little below the frame's bottom edge, as if rising from beyond it. */
        const val BLEED = 1.02f
    }
}

/**
 * The illustrated-news grammar (reference 2): one scene per chapter — a headline revealed word by
 * word, a hero illustration rising on a spring, at most one device — each arriving over the last by
 * a circle wipe (that becomes its ground), a push or a page peel, with corner stars as punctuation
 * and a slow push-in so nothing stands still.
 */
internal class NewsStage(
    private val text: TextLayoutEngine,
    private val icons: IconResolver,
    private val illustrations: IllustrationSource,
    private val look: FilmLook,
) {
    private val rules = look.rules

    private class Scene(val index: Int, val transition: SceneTransition, val ground: ExplainerPalette, val start: Float, var end: Float, val nodes: MutableList<Node>)

    fun compose(film: ExplainerFilm, chapters: List<Chapter>, width: Int, height: Int, duration: Float): Pair<List<Node>, ExplainerStage.StageReport> {
        val captions = CaptionStyle.named(film.art.captionStyle.ifEmpty { "none" })
        val frame = StageFrame(width.toFloat(), height.toFloat(), StageGrammar.FullBleed, look.rtl, captions)
        val zones = NewsFrame(frame)
        val notes = ArrayList<String>()
        val rests = ArrayList<Rest>()
        val lives = ArrayList<ExplainerStage.Life>()
        val bleed = HashSet<String>()
        val transitions = chapters.indices.map { i -> if (i == 0) SceneTransition.Cut else rules.transitionInto(i, chapters[i].transition) }
        val scenes = chapters.mapIndexed { i, ch ->
            val dur = (ch.end - ch.start).toFloat()
            if (dur < rules.sceneMin || dur > rules.sceneMax) notes += "scene $i lasts ${tenths(dur)}s (a news scene runs ${rules.sceneMin}–${rules.sceneMax}s)"
            val ground = if (transitions[i] == SceneTransition.Circle) look.pal.onAccentGround(ch.accent) else look.pal
            Scene(i, transitions[i], ground, ch.start.toFloat(), ch.end.toFloat(), ArrayList())
        }
        for ((i, ch) in chapters.withIndex()) {
            val scene = scenes[i]
            scene.nodes += ExplainerStage.paper(width, height, scene.ground.background, Anim.One)
            scene.nodes += content(i, ch, film, scene.ground, frame, zones, notes, rests, lives, bleed, scene.start, scenes.getOrNull(i + 1)?.start ?: duration)
            scenes.getOrNull(i + 1)?.let { next -> if (next.transition == SceneTransition.Circle) scene.nodes += circle(frame, next.ground.background, next.start) }
        }
        val nodes = arrange(scenes, frame, duration)
        val all = nodes + Captions(text, look.pal, frame, look.rtl, look.persian).nodes(captions, film.captions, duration)
        return all to ExplainerStage.StageReport(frame, rests, lives, notes, rules, bleed, transitions)
    }

    private fun tenths(v: Float) = (kotlin.math.round(v * 10f) / 10f).toString()

    /** One scene's elements: directed, limited by the grammar, laid out in their zones, drifting slowly. */
    @Suppress("LongParameterList", "LongMethod")
    private fun content(
        ci: Int, ch: Chapter, film: ExplainerFilm, pal: ExplainerPalette, frame: StageFrame, zones: NewsFrame, notes: MutableList<String>,
        rests: MutableList<Rest>, lives: MutableList<ExplainerStage.Life>, bleed: MutableSet<String>, start: Float, end: Float,
    ): Node {
        val versions = Director(withTitle(ch), notes, sweep = false).run()
        Staging.limit(versions, rules.maxDevices, { FilmRules.zone(it.spec.primitive) == Zone.Device }, notes)
        Staging.limit(versions, rules.maxLive, { FilmRules.zone(it.spec.primitive) != Zone.Corners }, notes)
        val kinds = versions.filter { !it.overlay }.map { FilmRules.zone(it.spec.primitive) }.toSet()
        val withHero = Zone.Hero in kinds
        val deviceBox = zones.device(withHero)
        val heroBox = zones.hero(Zone.Device in kinds)
        val unit = frame.u * if (frame.portrait) rules.scaleTall else rules.scaleWide
        val seed = film.art.seed * 1_000L + ci * 97L
        val hands = HashMap<Version, Hand>()
        val laid = HashMap<Version, Laid>()
        for (v in versions) {
            val box = when (FilmRules.zone(v.spec.primitive)) {
                Zone.Words -> zones.words
                Zone.Hero -> heroBox
                else -> deviceBox
            }
            val h = look.hand(text, icons, illustrations, if (pal.accentScene) 0 else ch.accent, unit, box.width, box.height, seed + v.serial * 13L + v.element.hashCode(), pal)
            h.align = TextAlign.Start
            hands[v] = h
            if (!v.overlay && FilmRules.zone(v.spec.primitive) != Zone.Corners) laid[v] = Pieces.of(v.spec.primitive).lay(v.spec, h)
        }
        val solved = HashMap<Version, Solver.Solved>()
        fun solve(zone: Box, list: List<Version>, fromTop: Boolean) {
            if (list.isEmpty()) return
            val (s, r) = Solver(zone, frame.u * rules.gap, wide = false, glide = rules.glide, fromTop = fromTop).also { it.rtlStart = look.rtl }.solve(ci, list, laid)
            solved += s
            rests += r
        }
        val placed = versions.filter { it in laid }
        val heroes = placed.filter { FilmRules.zone(it.spec.primitive) == Zone.Hero }
        val attached = placed.filter { FilmRules.zone(it.spec.primitive) == Zone.Attached && heroes.isNotEmpty() }
        solve(zones.words, placed.filter { FilmRules.zone(it.spec.primitive) == Zone.Words }.toList(), fromTop = true)
        solve(deviceBox, placed.filter { it !in heroes && it !in attached && FilmRules.zone(it.spec.primitive) != Zone.Words }, fromTop = false)
        for (v in heroes) {
            val l = laid.getValue(v)
            val k = minOf(1f, heroBox.width / l.w, heroBox.height / l.h)
            val place = Place(heroBox.centerX, heroBox.bottom - l.h * k / 2f, k)
            solved[v] = Solver.Solved(place.cx.anim, place.cy.anim, place.scale.anim)
            bleed += v.element
            rests += Rest(ci, v.enter, v.gone, mapOf(v.element to Box.around(place.cx, place.cy, l.w * k, l.h * k)))
        }
        for (v in attached) solved[v] = attach(v, laid.getValue(v), heroes, laid, solved, frame)
        val elements = Staging.nodes(ci, versions.filter { FilmRules.zone(it.spec.primitive) != Zone.Corners }, laid, solved, hands, lives)
        val stars = versions.filter { FilmRules.zone(it.spec.primitive) == Zone.Corners }.flatMap { v ->
            lives += ExplainerStage.Life(ci, v.element, v.spec.primitive, v.enter, minOf(v.leave, v.gone), true)
            corners(hands.getValue(v), frame, v.enter, ci)
        }
        // A slow push-in over the scene: the picture never stands still.
        val drift = Anim.tween(1f, DRIFT, start, end + 0.6f, Easing.Linear)
        return Group(
            listOf(Group(elements + stars, width = frame.width, height = frame.height)),
            Transform(x = (frame.width / 2f).anim, y = (frame.height * 0.55f).anim, anchorX = 0.5f, anchorY = 0.55f, scale = drift),
            width = frame.width, height = frame.height, name = "scene-$ci",
        )
    }

    /** A scene whose beats bring no words gets its chapter title as the headline. */
    private fun withTitle(ch: Chapter): Chapter {
        if (ch.title.isBlank() || ch.beats.any { b -> b.primitive?.let { FilmRules.zone(it) == Zone.Words } == true }) return ch
        val title = Beat(ch.start + 0.05, ch.start + 1.2, ch.title, StageOp.Add, "title", Primitive.Headline, BeatContent(text = ch.title))
        return ch.copy(beats = listOf(title) + ch.beats)
    }

    /** A bubble beside the hero's head, on the reading-start side, kept inside the safe area. */
    private fun attach(v: Version, l: Laid, heroes: List<Version>, laid: Map<Version, Laid>, solved: Map<Version, Solver.Solved>, frame: StageFrame): Solver.Solved {
        val hero = heroes.lastOrNull { it.enter <= v.enter } ?: heroes.first()
        val hl = laid.getValue(hero)
        val hs = solved.getValue(hero)
        val k = hs.scale.at(0f)
        val dir = if (look.rtl) -1f else 1f
        val safe = frame.safe
        val cx = (hs.x.at(0f) - dir * hl.w * k * 0.22f).coerceIn(safe.left + l.w / 2f, safe.right - l.w / 2f)
        val cy = (hs.y.at(0f) - hl.h * k / 2f + hl.h * k * 0.12f).coerceIn(safe.top + l.h / 2f, safe.bottom - l.h / 2f)
        return Solver.Solved(cx.anim, cy.anim, 1f.anim)
    }

    /** Accent stars at two corners, partly beyond the frame, as punctuation. */
    private fun corners(h: Hand, frame: StageFrame, at: Float, ci: Int): List<Node> {
        val w = frame.width
        val ht = frame.height
        val big = minOf(w, ht) * 0.2f
        val flip = ci % 2 == 0
        val spots = listOf(
            Triple(if (flip) w * 0.04f else w * 0.96f, ht * 0.03f, big * 0.8f),
            Triple(if (flip) w * 0.94f else w * 0.06f, ht * 0.96f, big),
        )
        return spots.mapIndexed { k, (x, y, d) -> NewsPieces.star(h, x, y, d, at + k * 0.12f, if (k == 0) -18f else 14f) }
    }

    /** The accent circle that wipes up from below the frame and becomes the next scene's ground. */
    private fun circle(frame: StageFrame, color: androidx.compose.ui.graphics.Color, at: Float): Node {
        val full = kotlin.math.hypot(frame.width, frame.height) * 2.3f
        val d = Anim.keys(Anim.Key(at - CIRCLE_LEAD, 0f, Easing.Hold), Anim.Key(at + CIRCLE_TAIL, full, Easing.Bezier(0.55f, 0f, 0.35f, 1f)))
        return ShapeNode(
            ShapeSpec.Ellipse(d, d), fill = Fill.Solid(ColorAnim.of(color)),
            transform = Transform(x = (frame.width / 2f).anim, y = (frame.height * 1.08f).anim), start = at - CIRCLE_LEAD, name = "circle-wipe",
        )
    }

    /** Scenes in time, each moved by the transition into it and out of it, stacked so a peel shows what is beneath. */
    private fun arrange(scenes: List<Scene>, frame: StageFrame, duration: Float): List<Node> {
        val w = frame.width
        val sx = 1f
        val z = HashMap<Int, Float>()
        val groups = scenes.map { s ->
            val next = scenes.getOrNull(s.index + 1)
            val from = when (s.transition) {
                SceneTransition.Push -> s.start - PUSH / 2f
                SceneTransition.Peel -> s.start - PEEL_LEAD
                SceneTransition.Circle -> s.start + CIRCLE_TAIL - 0.01f
                SceneTransition.Cut -> s.start
            }
            val until = when (next?.transition) {
                null -> duration + 1f
                SceneTransition.Push -> next.start + PUSH / 2f
                SceneTransition.Peel -> next.start + PEEL_TAIL
                SceneTransition.Circle -> next.start + CIRCLE_TAIL
                SceneTransition.Cut -> next.start
            }
            val xIn = if (s.transition == SceneTransition.Push) Anim.tween(sx * w, 0f, s.start - PUSH / 2f, s.start + PUSH / 2f, PUSH_EASE) else Anim.Zero
            val xOut = if (next?.transition == SceneTransition.Push) Anim.tween(0f, -sx * w, next.start - PUSH / 2f, next.start + PUSH / 2f, PUSH_EASE) else Anim.Zero
            val body = Group(s.nodes, Transform(anchorX = 0f, anchorY = 0f, x = xIn + xOut), width = w, height = frame.height, start = from, end = until, name = "scene")
            z[s.index] = if (s.transition == SceneTransition.Peel) (z[s.index - 1] ?: 0f) - 0.5f else s.index.toFloat()
            val node: Node = if (next?.transition == SceneTransition.Peel) {
                Group(listOf(NewsPieces.peel(listOf(body), w, 0f, frame.height, next.start - PEEL_LEAD, PEEL_LEAD + PEEL_TAIL, look.rtl, frame.u)), start = from, end = until, name = "scene-peel")
            } else {
                body
            }
            s.index to node
        }
        return groups.sortedBy { z.getValue(it.first) }.map { it.second }
    }

    companion object {
        const val DRIFT = 1.03f
        const val CIRCLE_LEAD = 0.5f
        const val CIRCLE_TAIL = 0.22f
        const val PUSH = 0.6f
        const val PEEL_LEAD = 0.4f
        const val PEEL_TAIL = 0.3f
        val PUSH_EASE: Easing = Easing.Bezier(0.65f, 0f, 0.25f, 1f)
    }
}
