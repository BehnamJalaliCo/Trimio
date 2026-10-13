package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.recipe.Box

/**
 * Renders an [ExplainerFilm] as a finished [Composition] for the engine's own renderer and
 * exporter, in the film's grammar ([FilmGrammar], rules in [FilmRules]):
 * - diagram chapters: a heading arrives under the chapter mark, the stage builds up beat by beat
 *   (elements added, built, ticked, filled, picked out, dimmed, morphed, replaced and cleared, the
 *   layout reflowing around them), captions run on their band, and between chapters the stage clears;
 * - illustrated news: one fast scene per chapter — headline, hero illustration, one device — joined
 *   by circle wipes, pushes and page peels ([NewsStage]).
 * The same film and seed always give the same frames.
 */
class ExplainerStage(
    private val text: TextLayoutEngine,
    private val icons: IconResolver = IconResolver.None,
    private val illustrations: IllustrationSource = IllustrationSource.None,
) {

    /**
     * What a run made: the composition, where everything rested (for tests and the jury), and the
     * media the composition draws illustrations from — pass [media] to the renderer or exporter.
     */
    class Output(val composition: Composition, val report: StageReport, private val illustrations: IllustrationSource = IllustrationSource.None) {
        /** A fresh media source per renderer (exports draw on several threads). */
        fun media(): MediaSource = IllustrationMedia(illustrations)
    }

    /**
     * The frame's plan, every rest of every chapter's stage, each element's life, and notes on skipped beats.
     * [bleed] names the elements allowed to cross the frame's bottom edge (news heroes).
     */
    class StageReport(
        val frame: StageFrame, val rests: List<Rest>, val elements: List<Life>, val notes: List<String>,
        val rules: GrammarRules = FilmRules.Diagram, val bleed: Set<String> = emptySet(),
        /** The transition into each chapter (the first: none). */
        val transitions: List<SceneTransition> = emptyList(),
    )

    /** One element version's life on stage. */
    data class Life(val chapter: Int, val element: String, val primitive: Primitive, val enter: Float, val leave: Float, val overlay: Boolean)

    fun compose(film: ExplainerFilm, width: Int = 1080, height: Int = 1920, fps: Int = 30): Output {
        val look = FilmLook.of(film)
        val chapters = film.chapters.filter { it.end > it.start }.sortedBy { it.start }
        val duration = (listOf(chapters.maxOfOrNull { it.end } ?: 0.0) + film.captions.map { it.end }).max().toFloat() + TAIL
        if (look.rules.grammar == FilmGrammar.IllustratedNews) {
            val news = NewsStage(text, icons, illustrations, look).compose(film, chapters, width, height, duration)
            return Output(Composition(width, height, fps, duration, look.pal.background, Group(news.first, name = "explainer"), shutterAngle = 0f), news.second, illustrations)
        }
        val pal = look.pal
        val rtl = look.rtl
        val captions = CaptionStyle.named(film.art.captionStyle)
        val frame = StageFrame(width.toFloat(), height.toFloat(), StageGrammar.named(film.art.layout), rtl, captions)
        val notes = ArrayList<String>()
        val rests = ArrayList<Rest>()
        val lives = ArrayList<Life>()
        val nodes = ArrayList<Node>()
        nodes += paper(width, height, pal.background, Anim.One)
        val header = Header(text, pal, frame, rtl, chapters, duration, ChapterMark.named(film.art.chapterMark), look.persian)
        nodes += header.marks()
        for ((ci, ch) in chapters.withIndex()) {
            nodes += header.title(ci)
            // The last chapter is the end card: nothing sweeps it away, it stays to the film's last frame.
            val last = ci == chapters.lastIndex
            val (chapterNodes, chapterRests) = chapter(ci, if (last) ch.copy(end = duration.toDouble()) else ch, film, look, frame, notes, lives, sweep = !last)
            nodes += chapterNodes
            rests += chapterRests
        }
        nodes += Captions(text, pal, frame, rtl, look.persian).nodes(captions, film.captions, duration)
        val composition = Composition(width, height, fps, duration, pal.background, Group(nodes, name = "explainer"), shutterAngle = 0f)
        return Output(composition, StageReport(frame, rests, lives, notes, look.rules, transitions = chapters.map { SceneTransition.Cut }), illustrations)
    }

    @Suppress("LongParameterList")
    private fun chapter(
        ci: Int, ch: Chapter, film: ExplainerFilm, look: FilmLook, frame: StageFrame, notes: MutableList<String>, lives: MutableList<Life>,
        sweep: Boolean = true,
    ): Pair<List<Node>, List<Rest>> {
        val rules = look.rules
        val versions = Director(ch, notes, sweep).run()
        Staging.holdTillNext(versions)
        Staging.limit(versions, rules.maxLive, { true }, notes)
        Staging.keepLit(versions)
        val seed = film.art.seed * 1_000L + ci * 97L
        val laid = HashMap<Version, Laid>()
        val hands = HashMap<Version, Hand>()
        val unit = frame.u * if (frame.portrait) rules.scaleTall else rules.scaleWide
        for (v in versions) {
            val h = look.hand(text, icons, illustrations, ch.accent, unit, frame.stage.width * ROOM, frame.stage.height * ROOM, seed + v.serial * 13L + v.element.hashCode())
            hands[v] = h
            if (!v.overlay) laid[v] = Pieces.of(v.spec.primitive).lay(v.spec, h)
        }
        // Elements rest a little inside the stage: a highlight's pulse never pushes them past it.
        val solver = Solver(inset(frame.stage, ROOM), frame.u * rules.gap * if (frame.portrait) 1f else WIDE_GAP, wide = !frame.portrait, glide = rules.glide).also { it.rtlStart = look.rtl }
        val (solved, rests) = solver.solve(ci, versions, laid)
        return Staging.nodes(ci, versions, laid, solved, hands, lives) to rests
    }

    companion object {
        const val TAIL = 0.6f

        /** The share of the stage elements rest in (the rest is room for pulses). */
        const val ROOM = 0.95f
        private const val WIDE_GAP = 0.82f

        internal fun inset(b: Box, share: Float): Box {
            val dx = b.width * (1f - share) / 2f
            val dy = b.height * (1f - share) / 2f
            return Box(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy)
        }

        /** A full-frame ground as a layer: it covers the frame at any export scale. */
        internal fun paper(width: Int, height: Int, color: androidx.compose.ui.graphics.Color, opacity: Anim): Node = ShapeNode(
            ShapeSpec.Rect(Anim.Const(width.toFloat()), Anim.Const(height.toFloat())), fill = Fill.Solid(ColorAnim.of(color)),
            transform = Transform(anchorX = 0f, anchorY = 0f, opacity = opacity), name = "paper",
        )
    }
}
