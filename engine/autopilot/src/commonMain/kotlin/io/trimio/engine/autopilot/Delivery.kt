package io.trimio.engine.autopilot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.Paint
import io.trimio.engine.motion.visual.VectorIcon
import io.trimio.engine.motion.visual.VectorLayer
import kotlinx.serialization.Serializable

/**
 * A delivered production as it is kept on disk: everything the director decided (the words as
 * spelled, their lines and reading, the score, the cut, the music, the levels, the marks and
 * pictures found online, the jury's verdict), and nothing that can be made again from the
 * creator's own files (the voice, the footage, the attachments) or is derived (the compiled
 * layout, the mastered mix). [Autopilot.restore] makes the same edit from it without a model,
 * without recognition and without the web, so a project opens in the editor and exports after the
 * app restarts exactly as it was delivered.
 *
 * Fields only grow, with defaults; a change that older files cannot follow raises [VERSION], and
 * a file of another version is no longer restored (the project can still make a new version).
 */
@Serializable
data class Delivery(
    val version: Int = VERSION,
    val prompt: String,
    val seed: Long,
    /** The footage id the renderer draws (null for a voice recording). */
    val footage: String? = null,
    val fps: Int = DEFAULT_FPS,
    /** Where the speaker is in the frame. */
    val subject: Frame? = null,
    /** The recognised words as the edit spelled them, on the source clock. */
    val transcript: Transcript,
    val lines: List<Lines.Line>,
    /** Silences the voice really has, in source seconds. */
    val pauses: List<Gap> = emptyList(),
    val understanding: Understanding,
    /** The delivered score (after the critics and the jury). */
    val score: Score,
    /** The planner's score before pictures and critics ([Planner.Plan.score]). */
    val planned: Score,
    /** The music mood id, or null for none. */
    val music: String? = null,
    val choices: Choices,
    val notes: List<String> = emptyList(),
    val cut: Cut,
    val levels: Soundtrack.Levels = Soundtrack.Levels(),
    /** Brand marks found online, by library key. */
    val marks: Map<String, BrandLibrary.Entry> = emptyMap(),
    /** Pictures fetched from the web for this piece. */
    val icons: List<Icon> = emptyList(),
    /** The jury's verdicts on the delivered edit (null when no jury sat). */
    val panel: List<Verdict>? = null,
    val report: List<String> = emptyList(),
) {
    /** The speaker's place as fractions of the frame. */
    @Serializable
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun subject() = Subject(left, top, right, bottom)

        companion object {
            fun of(s: Subject) = Frame(s.left, s.top, s.right, s.bottom)
        }
    }

    /** A silence [from]..[to], source seconds. */
    @Serializable
    data class Gap(val from: Float, val to: Float)

    /** The cut list ([EditPlan]): what plays, the cold open, the end hold. */
    @Serializable
    data class Cut(val segments: List<Span>, val opening: List<Span> = emptyList(), val outro: Hold? = null) {
        fun plan() = EditPlan(segments.map { it.segment() }, opening.map { it.segment() }, outro?.let { EditPlan.Outro(it.length, it.sourceStart, it.running) })

        companion object {
            fun of(e: EditPlan) = Cut(e.segments.map(Span::of), e.opening.map(Span::of), e.outro?.let { Hold(it.length, it.sourceStart, it.running) })
        }
    }

    @Serializable
    data class Span(val sourceStart: Float, val sourceEnd: Float, val outStart: Float) {
        fun segment() = EditPlan.Segment(sourceStart, sourceEnd, outStart)

        companion object {
            fun of(s: EditPlan.Segment) = Span(s.sourceStart, s.sourceEnd, s.outStart)
        }
    }

    @Serializable
    data class Hold(val length: Float, val sourceStart: Float, val running: Float)

    /** A vector picture ([VectorIcon]); colours as ARGB; an animated one keeps its document ([motion]). */
    @Serializable
    data class Icon(
        val name: String,
        val set: String,
        val width: Float,
        val height: Float,
        val layers: List<Layer>,
        val keywords: List<String> = emptyList(),
        val motion: Motion? = null,
    ) {
        fun icon() = VectorIcon(name, set, width, height, layers.map { it.layer() }, keywords, motion?.art(width, height))

        companion object {
            fun of(i: VectorIcon) = Icon(i.name, i.set, i.width, i.height, i.layers.map(Layer::of), i.keywords, i.motion?.let(Motion::of))
        }
    }

    /** An animated picture's document ([io.trimio.engine.motion.art.MotionArt]): drawn again with no network. */
    @Serializable
    data class Motion(val format: String, val document: String, val loops: Boolean) {
        fun art(width: Float, height: Float) = io.trimio.engine.motion.art.MotionArts.read(format, document, width, height, loops)

        companion object {
            fun of(m: io.trimio.engine.motion.art.MotionArt) = Motion(m.format, m.document, m.loops)
        }
    }

    @Serializable
    data class Layer(val d: String, val paint: Fill, val opacity: Float = 1f, val evenOdd: Boolean = false, val strokeWidth: Float = 0f) {
        fun layer() = VectorLayer(d, paint.paint(), opacity, evenOdd, strokeWidth)

        companion object {
            fun of(l: VectorLayer) = Layer(l.d, Fill.of(l.paint), l.opacity, l.evenOdd, l.strokeWidth)
        }
    }

    /** [Paint]: a colour, the node's tint (no colour, no gradient), or a gradient. */
    @Serializable
    data class Fill(
        val color: Int? = null,
        val linear: Boolean = true,
        val coords: List<Float> = emptyList(),
        val matrix: List<Float> = emptyList(),
        val stops: List<Stop> = emptyList(),
    ) {
        fun paint(): Paint = when {
            color != null -> Paint.Solid(Color(color))
            stops.isNotEmpty() -> Paint.Gradient(linear, coords.toFloatArray(), matrix.toFloatArray(), stops.map { it.at to Color(it.color) })
            else -> Paint.Current
        }

        companion object {
            fun of(p: Paint) = when (p) {
                is Paint.Solid -> Fill(color = p.color.toArgb())
                Paint.Current -> Fill()
                is Paint.Gradient -> Fill(linear = p.linear, coords = p.coords.toList(), matrix = p.matrix.toList(), stops = p.stops.map { (at, c) -> Stop(at, c.toArgb()) })
            }
        }
    }

    @Serializable
    data class Stop(val at: Float, val color: Int)

    companion object {
        const val VERSION = 1
        private const val DEFAULT_FPS = 30

        /** What to keep of [p], a production as the director delivered it; null when it kept no [Workbench]. */
        fun of(p: Autopilot.Production): Delivery? {
            val bench = p.bench ?: return null
            val r = bench.request
            return Delivery(
                prompt = r.prompt, seed = r.seed, footage = r.footage, fps = r.fps, subject = r.subject?.let(Frame::of),
                transcript = bench.transcript, lines = bench.lines, pauses = bench.pauses.map { Gap(it.start, it.endInclusive) },
                understanding = p.understanding, score = p.score, planned = p.plan.score, music = p.plan.music?.id,
                choices = p.plan.choices, notes = p.plan.notes, cut = Cut.of(p.edit), levels = bench.levels,
                marks = bench.marks, icons = bench.icons.map(Icon::of), panel = p.panel?.verdicts, report = p.report,
            )
        }
    }
}
