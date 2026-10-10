package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.score.Score
import kotlin.math.ceil

/**
 * The typographer reads the scene graph itself: every text layer's size once it has landed
 * (its type size times the scales above it) against the frame — captions at least [captionMin],
 * headlines [headlineMin] over the speaker and [stageMin] on a stage, labels [labelMin] (fractions
 * of the frame width; 1080 px wide → 43, 49, 43, 24 px) — lines per element (captions 2,
 * headlines 3), right-to-left set for Persian, and a contrast aid for text over footage (a shadow,
 * a stroke or the frame's scrims). What the eyes could not read ([io.trimio.engine.autopilot.VisionCritic]) counts too.
 */
class TypographyJudge(
    private val captionMin: Float = 0.04f,
    private val headlineMin: Float = 0.045f,
    private val stageMin: Float = 0.04f,
    private val labelMin: Float = 0.022f,
) : Judge {
    override val name = "typography"
    override val dimension = "type and legibility"

    /** A text layer as seen once it has landed. */
    private data class Layer(val node: TextNode, val t: Float, val size: Float, val overFootage: Boolean) {
        val kind: String get() = when (node.name) {
            Bench.CAPTIONS -> CAPTION
            in Bench.TEXT -> HEADLINE
            else -> LABEL
        }
    }

    override fun score(c: Jury.Context): Verdict {
        val width = c.compiled.composition.width.toFloat()
        val layers = layers(c)
        val f = mutableListOf<Finding>()
        f += small(layers, width).map { (l, min) ->
            Bench.finding(l.t, Severity.Major, "«${l.node.text.take(TRIM)}» (${l.node.name}) is ${l.size.toInt()} px (needs ${(min * width).toInt()})", "give it more room or a stage")
        }.capped()
        f += layers.filter { tooManyLines(c, it) }.map { l ->
            Bench.finding(l.t, Severity.Minor, "«${l.node.text.take(TRIM)}» breaks into too many lines", if (l.kind == CAPTION) "fewer words per caption" else "a shorter title")
        }.capped()
        f += layers.filter { Bench.persian(it.node.text) && !it.node.rtl }.map { l ->
            Bench.finding(l.t, Severity.Major, "«${l.node.text.take(TRIM)}» is Persian but set left-to-right", "set it right-to-left")
        }.capped()
        f += contrast(c, layers)
        f += c.looks.filter { !it.readable }.map { Bench.finding(it.beat.at, Severity.Major, "the eyes could not read ${it.beat.recipe} «${it.beat.text}»", "a full-frame stage for it") }
        return Verdict.of(this, f)
    }

    private fun small(layers: List<Layer>, width: Float) = layers.mapNotNull { l ->
        val min = when (l.kind) {
            CAPTION -> captionMin
            HEADLINE -> if (l.overFootage) headlineMin else stageMin
            else -> labelMin
        }
        (l to min).takeIf { l.node.text.isNotBlank() && l.size < min * width }
    }

    private fun tooManyLines(c: Jury.Context, l: Layer): Boolean {
        val max = when (l.kind) {
            CAPTION -> 2
            HEADLINE -> 3
            else -> return false
        }
        if (!l.node.maxWidth.isFinite() || l.node.text.isBlank()) return false
        val lines = c.measure?.lines(l.node.text, l.node.type, l.node.maxWidth, l.node.rtl)
            ?: ceil(l.node.text.length * l.node.type.size * GLYPH / l.node.maxWidth).toInt()
        // The estimate is rough: only a clear excess counts without the real text engine.
        return lines > max + (if (c.measure == null) 1 else 0)
    }

    private fun contrast(c: Jury.Context, layers: List<Layer>): List<Finding> {
        if (!c.footage) return emptyList()
        val scrims = effects(c.compiled.composition.root).count { it.effect is Effect.Scrim }
        val out = mutableListOf<Finding>()
        if (scrims == 0) out += Bench.finding(0f, Severity.Major, "no scrims over the footage", "scrims under the text bands")
        val bare = layers.filter { it.overFootage && it.node.shadow == null && it.node.stroke == null && scrims == 0 }
        bare.firstOrNull()?.let { out += Bench.finding(it.t, Severity.Minor, "${bare.size} text layers over footage without a shadow or stroke", "a soft shadow under text on footage") }
        return out
    }

    /**
     * Every text layer at its settled size: the largest it is drawn while fully visible (its own
     * and its groups' opacity), sampled when the beats have landed. A layer never fully visible is
     * not judged (it is a transition, a ghost, a glow).
     */
    private fun layers(c: Jury.Context): List<Layer> {
        val moments = c.compiled.beats.flatMap { b -> listOf(minOf(b.at + LAND_AFTER, (b.at + b.out) / 2f), (b.at + b.out) / 2f, b.out - LAND_AFTER) }.distinct().sorted()
        val out = mutableListOf<Layer>()
        fun visit(n: Node, chain: List<Node>) {
            when (n) {
                is Group -> n.children.forEach { visit(it, chain + n) }
                is TextNode -> {
                    // A layer exists only while every group above it does.
                    val all = chain + n
                    val from = all.maxOf { it.start }
                    val to = minOf(all.minOf { it.end }, c.duration)
                    val seen = (moments.filter { it in from..to } + landed(from, to)).mapNotNull { t ->
                        val opacity = all.fold(1f) { acc, p -> acc * p.transform.opacity.at(t) }
                        (t to n.type.size * all.fold(1f) { acc, p -> acc * p.transform.scale.at(t) }).takeIf { opacity >= VISIBLE }
                    }.maxByOrNull { it.second } ?: return
                    out += Layer(n, seen.first, seen.second, Bench.overFootageAt(c, seen.first))
                }
                else -> Unit
            }
        }
        visit(c.compiled.composition.root, emptyList())
        return out
    }

    /** When a layer has landed: 1.2 s after it exists (0.7 s after its beat), or its middle when shorter. */
    private fun landed(start: Float, end: Float): Float = if (end.isFinite()) start + minOf(LAND, (end - start) / 2f) else start + LAND

    private fun effects(n: Node): List<EffectNode> = when (n) {
        is Group -> n.children.flatMap { effects(it) }
        is EffectNode -> listOf(n)
        else -> emptyList()
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val width = c.compiled.composition.width.toFloat()
        val layers = layers(c)
        // Headlines too small over the speaker get the whole frame (as the eyes' critic does).
        for ((l, _) in small(layers, width).filter { it.first.kind == HEADLINE && it.first.overFootage }) {
            val k = Bench.sceneAt(score, c.words, l.t)
            val scene = score.scenes.getOrNull(k) ?: continue
            if (k == 0 || scene.bg != null || scene.beats.any { it.recipe == Bench.COMMENT }) continue
            score = score.copy(scenes = score.scenes.mapIndexed { i, s -> if (i == k) s.copy(bg = "grid", transition = s.transition ?: "zoom") else s })
        }
        if (layers.any { it.kind == CAPTION && tooManyLines(c, it) } && score.captions.maxWords > 2) {
            score = score.copy(captions = score.captions.copy(maxWords = score.captions.maxWords - 1))
        }
        return score.takeIf { it != c.score }
    }

    private companion object {
        const val CAPTION = "caption"
        const val HEADLINE = "headline"
        const val LABEL = "label"
        const val TRIM = 24
        const val LAND = 1.2f
        const val LAND_AFTER = 0.7f
        const val VISIBLE = 0.9f

        /** Average advance of a glyph, in ems, for the line estimate. */
        const val GLYPH = 0.5f
    }
}
