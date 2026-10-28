package io.trimio.engine.motion

import kotlin.math.abs

/**
 * Maps a real [Composition] onto [CraftAudit]'s timeline so the craft rules run on what the
 * renderer will draw. Times come from each node's own window (parent-clipped), entry/exit from
 * the animators or transform keys that actually move, and text holds from real line word counts.
 */
object CraftGate {

    /** Export-door policy. Retry is named now so a regenerator loop can land without a rename. */
    enum class Policy { Reject, Retry }

    /** Pre-export refusal: [findings] is the whole reason the piece did not ship. */
    class Rejected(val findings: List<CraftAudit.Finding>, val retryable: Boolean) : IllegalStateException(
        findings.joinToString(prefix = "craft gate (${findings.size}): ", separator = " | ") { "${it.rule} ${it.message}" },
    )

    /**
     * The gate. [hasAudio] is not on the scene graph (voice and music live outside it), so the
     * caller that knows the mix passes it; defaulting to true keeps film_silent honest.
     */
    fun audit(composition: Composition, hasAudio: Boolean = true): CraftAudit.Result =
        CraftAudit.result(map(composition, hasAudio))

    /** The faithful Composition → [CraftAudit.Timeline] mapping, without judging it. */
    fun map(composition: Composition, hasAudio: Boolean = true): CraftAudit.Timeline {
        val flat = flatten(composition)
        val shots = shotsOf(composition, flat)
        return CraftAudit.Timeline(shots, hasAudio, composition.duration)
    }

    /**
     * Pre-export enforcement. On findings, [Policy.Reject] (default — no retry loop yet) fails
     * with [Rejected]; [Policy.Retry] fails the same way but tags the failure retryable.
     */
    fun enforce(
        composition: Composition,
        hasAudio: Boolean = true,
        policy: Policy = Policy.Reject,
    ): kotlin.Result<List<CraftAudit.Finding>> {
        val result = audit(composition, hasAudio)
        return if (result.ok) {
            kotlin.Result.success(result.findings)
        } else {
            kotlin.Result.failure(Rejected(result.findings, retryable = policy == Policy.Retry))
        }
    }

    /**
     * Words on each visual line of [text]. Explicit breaks first, then whitespace — so a flash-by
     * is judged against the words a reader must actually take in.
     */
    fun linesOf(text: String): List<String> =
        text.split('\n').map { line -> line.split(' ', '\t').filter { it.isNotBlank() && it != ZWNJ } }
            .filter { it.isNotEmpty() }
            .map { it.joinToString(" ") }

    private fun lineWordCounts(text: String): List<Int> =
        text.split('\n').map { line -> line.split(' ', '\t').count { it.isNotBlank() && it != ZWNJ } }
            .filter { it > 0 }

    // ------------------------------------------------------------------ mapping

    private class Timed(
        val name: String,
        val from: Float,
        val to: Float?,
        val type: String,
        val words: Int?,
        val textHeightPx: Float?,
        val opacityIsBouncy: Boolean,
        val entryKind: String?,
        val entrySec: Float?,
        val exitSec: Float?,
        val hasLinearMotion: Boolean,
    ) {
        fun toElement(shortSidePx: Float): CraftAudit.Element = CraftAudit.Element(
            name = name,
            appearsAtSec = from,
            disappearsAtSec = to,
            entrySec = entrySec,
            exitSec = exitSec,
            textWordCount = words,
            textHeightPx = textHeightPx,
            shortSidePx = shortSidePx,
            opacityIsBouncy = opacityIsBouncy,
            entryKind = entryKind,
            hasLinearMotion = hasLinearMotion,
        )

        fun covers(t: Float): Boolean = from <= t && (to == null || to > t)
    }

    private fun flatten(composition: Composition): List<Timed> {
        val shortSide = minOf(composition.width, composition.height).toFloat()
        val out = mutableListOf<Timed>()
        walk(composition.root, 0f, composition.duration, shortSide, "root", out)
        return out
    }

    private fun walk(
        node: Node,
        windowStart: Float,
        windowEnd: Float,
        shortSide: Float,
        path: String,
        out: MutableList<Timed>,
    ) {
        val from = maxOf(node.start, windowStart, 0f)
        val to = minOf(node.end, windowEnd).takeIf { it.isFinite() }
        val name = "$path/${node.name}"
        when (node) {
            is Group -> node.children.forEachIndexed { i, child ->
                walk(child, from, to ?: windowEnd, shortSide, "$name[$i]", out)
            }
            is TextNode -> out += textTimed(node, name, from, to, shortSide)
            is CounterNode -> out += simpleTimed(node, name, from, to, "text", 1, node.type.size, shortSide)
            is ShapeNode -> out += simpleTimed(node, name, from, to, "graphic", null, null, shortSide)
            is VectorNode -> out += simpleTimed(node, name, from, to, "graphic", null, node.size, shortSide)
            is MediaNode -> out += simpleTimed(node, name, from, to, "media", null, null, shortSide)
            // Texture overlays are not story elements: grain/vignette never carry a rule.
            is EffectNode -> if (node.effect !is Effect.Grain && node.effect !is Effect.Vignette) {
                out += simpleTimed(node, name, from, to, "graphic", null, null, shortSide)
            }
        }
    }

    private fun simpleTimed(
        node: Node,
        name: String,
        from: Float,
        to: Float?,
        type: String,
        words: Int?,
        heightPx: Float?,
        shortSide: Float,
    ): Timed {
        val motion = motionOf(node.transform, from, to)
        return Timed(
            name, from, to, type, words, heightPx,
            motion.bouncy, motion.entryKind, motion.entrySec, motion.exitSec, motion.linear,
        ).also { check(shortSide > 0f) { "composition short side must be positive" } }
    }

    /**
     * Text becomes one [Timed] per visual line only when those lines already carry their own
     * start times (word/line animators or word boxes); otherwise the node is one element and its
     * word count is the sum of its lines — the hold a reader needs for the whole block.
     */
    private fun textTimed(node: TextNode, name: String, from: Float, to: Float?, shortSide: Float): List<Timed> {
        val counts = lineWordCounts(node.text)
        val height = node.type.size
        val motion = motionOf(node.transform, from, to)
        val anim = textMotion(node, from, to)
        val entryKind = anim.entryKind ?: motion.entryKind
        val entrySec = anim.entrySec ?: motion.entrySec
        val exitSec = anim.exitSec ?: motion.exitSec
        val bouncy = anim.bouncy || motion.bouncy
        val linear = anim.linear || motion.linear
        val lineStarts = lineStarts(node, counts.size)
        return if (lineStarts != null && counts.size > 1) {
            counts.indices.map { i ->
                Timed(
                    "$name#line$i", lineStarts[i], to, "text", counts[i], height,
                    bouncy, entryKind, entrySec, exitSec, linear,
                )
            }
        } else {
            listOf(
                Timed(name, from, to, "text", counts.sum(), height, bouncy, entryKind, entrySec, exitSec, linear),
            )
        }
    }

    private class Motion(
        val entrySec: Float?,
        val entryKind: String?,
        val exitSec: Float?,
        val bouncy: Boolean,
        val linear: Boolean,
    )

    private fun textMotion(node: TextNode, from: Float, to: Float?): Motion {
        val ins = node.animators.filter { it.shape == Shape.In }
        val outs = node.animators.filter { it.shape == Shape.Out }
        val words = lineWordCounts(node.text).sum()
        val entrySec = ins.maxOfOrNull { it.span(unitsOf(it, node, words)) }
        val exitSec = outs.maxOfOrNull { it.span(unitsOf(it, node, words)) }
        val kind = ins.firstNotNullOfOrNull { entryKindOf(it) }
        val bouncy = node.animators.any { it.bouncesOpacity() }
        val linear = node.animators.any { it.carriesLinearMotion() }
        return Motion(entrySec, kind, exitSec, bouncy, linear)
    }

    private fun unitsOf(a: TextAnimator, node: TextNode, words: Int): Int = when (a.unit) {
        TextUnit.Char -> node.text.count { !it.isWhitespace() && it != ZWNJ }
        TextUnit.Word -> words
        TextUnit.Line -> lineWordCounts(node.text).size
        TextUnit.All -> 1
    }

    private fun TextAnimator.span(units: Int): Float = duration + stagger * (units - 1).coerceAtLeast(0)

    private fun entryKindOf(a: TextAnimator): String? {
        val s = a.state
        val fade = s.opacity < RestOpacity
        val rise = abs(s.dy) > MoveEps
        val slide = abs(s.dx) > MoveEps
        val scale = abs(s.scale - 1f) > ScaleEps
        val blur = s.blur > BlurEps
        val flip = abs(s.rotationX) > RotEps
        return when {
            flip -> "flip"
            fade && (rise || slide) -> "fade-rise"
            fade && scale -> "fade-scale"
            fade && blur -> "blur"
            fade -> "fade"
            rise -> "rise"
            slide -> "slide"
            scale -> "scale"
            blur -> "blur"
            else -> null
        }
    }

    private fun TextAnimator.bouncesOpacity(): Boolean {
        if (state.opacity >= RestOpacity) return false
        return (ease is Easing.Spring && ease.damping < 1f) || ease is Easing.BackOut
    }

    /** A pulse is emphasis, not travel: only In/Out at constant speed read as linear motion. */
    private fun TextAnimator.carriesLinearMotion(): Boolean {
        if (shape == Shape.Pulse || ease != Easing.Linear) return false
        return abs(state.dx) > MoveEps || abs(state.dy) > MoveEps ||
            abs(state.scale - 1f) > ScaleEps || abs(state.rotation) > RotEps || abs(state.rotationX) > RotEps
    }

    private fun lineStarts(node: TextNode, lines: Int): List<Float>? {
        if (lines <= 1) return null
        val timed = node.animators.firstOrNull { it.shape == Shape.In && it.times?.size == lines }?.times
        if (timed != null) return timed
        val staggered = node.animators.firstOrNull { it.shape == Shape.In && it.unit == TextUnit.Line }
            ?.takeIf { it.times == null } ?: return null
        return List(lines) { i -> staggered.startOf(i, lines) }
    }

    // ------------------------------------------------------------------ transform

    private fun motionOf(t: Transform, from: Float, to: Float?): Motion {
        val end = to ?: (from + EntryWindow)
        val spanEnd = minOf(from + EntryWindow, end)
        val fade = t.opacity.at(from) < RestOpacity && t.opacity.at(spanEnd) >= RestOpacity
        val dx = abs(t.x.at(spanEnd) - t.x.at(from))
        val dy = abs(t.y.at(spanEnd) - t.y.at(from))
        val ds = abs(t.scale.at(spanEnd) - t.scale.at(from))
        val kind = when {
            fade && (dy > MoveEps || dx > MoveEps) -> "fade-rise"
            fade -> "fade"
            dy > MoveEps -> "rise"
            dx > MoveEps -> "slide"
            ds > ScaleEps -> "scale"
            else -> null
        }
        val hasEntry = kind != null
        val entrySec = if (hasEntry) settleSec(t, from, spanEnd) else null
        val exitSec = if (to != null && to > from) exitSecOf(t, from, to) else null
        return Motion(
            entrySec = entrySec,
            entryKind = kind,
            exitSec = exitSec,
            bouncy = t.opacity.overshoots(from, end),
            linear = t.x.isLinear(from, end) || t.y.isLinear(from, end) ||
                t.scale.isLinear(from, end) || t.rotation.isLinear(from, end),
        )
    }

    private fun settleSec(t: Transform, from: Float, end: Float): Float {
        if (end <= from) return 0f
        var last = from
        val steps = SampleSteps
        for (i in 1..steps) {
            val u = from + (end - from) * i / steps
            val prev = from + (end - from) * (i - 1) / steps
            val moving = abs(t.opacity.at(u) - t.opacity.at(prev)) > SettleEps ||
                abs(t.x.at(u) - t.x.at(prev)) > SettleEps ||
                abs(t.y.at(u) - t.y.at(prev)) > SettleEps ||
                abs(t.scale.at(u) - t.scale.at(prev)) > SettleEps
            if (moving) last = u
        }
        return (last - from).coerceAtLeast(0f)
    }

    private fun exitSecOf(t: Transform, from: Float, to: Float): Float? {
        val start = maxOf(from, to - EntryWindow)
        val fading = t.opacity.at(to) < RestOpacity && t.opacity.at(start) >= RestOpacity
        val leaving = abs(t.x.at(to) - t.x.at(start)) > MoveEps || abs(t.y.at(to) - t.y.at(start)) > MoveEps
        if (!fading && !leaving) return null
        return (to - start).coerceAtLeast(0f)
    }

    private fun Anim.overshoots(from: Float, to: Float): Boolean {
        if (to <= from) return false
        val a = at(from)
        val b = at(to)
        val lo = minOf(a, b) - OvershootEps
        val hi = maxOf(a, b) + OvershootEps
        for (i in 0..SampleSteps) {
            val v = at(from + (to - from) * i / SampleSteps)
            if (v < lo || v > hi) return true
        }
        return keyframes()?.any { it.ease is Easing.BackOut || (it.ease is Easing.Spring && it.ease.damping < 1f) } == true
    }

    private fun Anim.isLinear(from: Float, to: Float): Boolean {
        val keys = keyframes()
        if (keys != null) {
            return keys.zipWithNext().any { (a, b) -> b.t > a.t && b.v != a.v && b.ease == Easing.Linear }
        }
        if (to - from <= 0f) return false
        val dt = (to - from) / SampleSteps
        val slope = (at(from + dt) - at(from)) / dt
        if (abs(slope) < SettleEps) return false
        for (i in 2..SampleSteps) {
            val s = (at(from + i * dt) - at(from + (i - 1) * dt)) / dt
            if (abs(s - slope) > abs(slope) * LinearTol + SettleEps) return false
        }
        return true
    }

    private fun Anim.keyframes(): List<Anim.Key>? = (this as? Anim.Keys)?.keys

    // ------------------------------------------------------------------ shots

    /**
     * Shots are the compiler's `scene-N` groups when present (a cut is the start of the next
     * scene), else one shot over the whole piece.
     */
    private fun shotsOf(composition: Composition, flat: List<Timed>): List<CraftAudit.Shot> {
        val duration = composition.duration
        val sceneStarts = composition.root.children
            .filterIsInstance<Group>()
            .mapNotNull { g -> SCENE_NAME.matchEntire(g.name)?.groupValues?.get(1)?.toFloatOrNull() to g.start }
            .filter { it.first != null }
            .sortedBy { it.first }
            .map { maxOf(it.second, 0f) }
            .distinct()
        val starts = if (sceneStarts.isEmpty()) listOf(0f) else listOf(0f) + sceneStarts.filter { it > 0f }
        val bounds = starts.distinct().sorted()
        return bounds.mapIndexed { i, start ->
            val end = bounds.getOrNull(i + 1) ?: duration
            val name = if (sceneStarts.isEmpty()) "shot" else "scene-$i"
            val elements = flat.filter { it.from < end && (it.to == null || it.to > start) }
                .map { it.toElement(minOf(composition.width, composition.height).toFloat()) }
            CraftAudit.Shot(
                name = name,
                startSec = start,
                endSec = end,
                elements = elements,
                firstFrameHasContent = flat.any { it.covers(start) },
            )
        }
    }

    private const val SampleSteps = 16
    private const val EntryWindow = 2f
    private const val RestOpacity = 0.95f
    private const val MoveEps = 1f
    private const val ScaleEps = 0.02f
    private const val BlurEps = 0.01f
    private const val RotEps = 0.5f
    private const val SettleEps = 0.01f
    private const val OvershootEps = 0.02f
    private const val LinearTol = 0.15f
    private const val ZWNJ = '\u200C'
    private val SCENE_NAME = Regex("scene-(\\d+)")
}
