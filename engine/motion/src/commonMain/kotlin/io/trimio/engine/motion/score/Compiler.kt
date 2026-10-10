package io.trimio.engine.motion.score

import androidx.compose.ui.graphics.Color
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Camera
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Fit
import io.trimio.engine.motion.Grade
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.recipe.Built
import io.trimio.engine.motion.recipe.CameraMove
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Fitter
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipe
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.recipe.TextRecipes
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a [Score] into a finished [Composition]. The model says *what*; the compiler decides
 * *when* (word timing, two-frame lead, beat snapping, readable durations), *where* (safe zones,
 * collision-free placement, text fitted to its slot), and adds the polish a senior editor would:
 * captions that step aside for headlines, one highlight per line, scrims under text on footage,
 * camera life, transitions with sound, grain and vignette from the look.
 */
class Compiler(private val text: TextLayoutEngine, private val brands: BrandLibrary = BrandLibrary.Empty) {

    data class Input(
        val score: Score,
        val transcript: Transcript?,
        /** Seconds; defaults to the end of speech plus a short tail. */
        val duration: Float? = null,
        /** Footage id for the [io.trimio.engine.motion.MediaSource], or null for audio-only pieces. */
        val footage: String? = null,
        val fps: Int = 30,
        /** The cut list (silence removed); the transcript is given on the source clock. */
        val edit: EditPlan? = null,
        /** Where the speaker is: graphics over their footage keep off the face. */
        val subject: Subject? = null,
    )

    data class Placed(val recipe: String, val zone: String, val at: Float, val out: Float, val text: String)

    data class Output(val composition: Composition, val sfx: List<Sfx>, val beats: List<Placed>, val notes: List<String>)

    private class Beat(
        val id: Int,
        val recipe: Recipe,
        val cue: Cue,
        val zone: Layout.Zone,
        val scene: Int,
        val minOut: Float,
    ) {
        var out: Float = cue.out
    }

    private val fitter = Fitter { s, voice, preferred, maxW, maxH, maxLines ->
        var size = preferred
        var last: Fitted? = null
        val rtl = isRtl(s)
        repeat(FIT_STEPS) {
            val type = voice.at(size)
            val block = text.layout(s, type, maxW, TextAlign.Center, rtl)
            val fitted = Fitted(type, block.width, block.height, block.lines.size)
            last = fitted
            if (block.width <= maxW + 0.5f && block.height <= maxH + 0.5f && block.lines.size <= maxLines) return@Fitter fitted
            size *= 0.92f
        }
        last!!
    }

    fun compile(input: Input): Output = Session(input).run()

    /** One compilation: the transcript, scenes and layout every step shares. */
    private inner class Session(val input: Input) {
        val score = input.score
        val notes = mutableListOf<String>()
        val look = Look.named(score.look)
        val w: Int
        val h: Int
        val transcript = input.transcript?.let { t -> input.edit?.remap(t) ?: t }
        val words = transcript?.words.orEmpty()
        val norm = words.map { NumberWords.normalize(it.text) }
        val duration = input.duration ?: input.edit?.duration ?: ((words.lastOrNull()?.range?.endMs ?: 3000L) / 1000f + TAIL)
        val layout: Layout
        val scenes = score.scenes.ifEmpty { autoScenes(transcript).also { notes += "auto scenes: ${it.size}" } }
        val sceneStarts: List<Float>
        val sceneEnds: List<Float>

        init {
            frameOf(score.format).let { (fw, fh) -> w = fw; h = fh }
            layout = Layout(w, h, input.subject)
            sceneStarts = scenes.mapIndexed { k, sc ->
                if (k == 0) 0f else (sc.time ?: sc.from?.let { start(it) - Craft.SCENE_LEAD } ?: 0f).coerceIn(0f, duration)
            }
            sceneEnds = sceneStarts.drop(1) + duration
        }

        fun start(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.startMs?.div(1000f) ?: 0f
        fun end(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.endMs?.div(1000f) ?: 0f

        /** Footage is on screen behind this scene (not covered by a full-frame takeover). */
        fun overFootage(scene: SceneScore) = input.footage != null && sceneBg(scene, input) == "media"

        fun sceneWords(k: Int): IntRange {
            val a = words.indexOfFirst { it.range.startMs / 1000f >= sceneStarts[k] }.let { if (it < 0) words.size else it }
            val b = words.indexOfLast { it.range.startMs / 1000f < sceneEnds[k] }
            return a..b
        }

        fun run(): Output {
            val beats = mutableListOf<Beat>()
            for ((k, scene) in scenes.withIndex()) {
                val range = sceneWords(k)
                val requested = scene.beats.ifEmpty { if (score.auto) AutoDirector.beats(words, norm, range, k == 0, notes) else emptyList() }
                requested.forEach { beats += resolve(it, k, scene, range, beats.size) }
            }
            // Occupants that had to leave early.
            for (b in beats) b.out = layout.outOf(b.id) ?: b.out
            val captionBeats = if (score.captions.show && words.isNotEmpty()) captions(this, beats) else emptyList()
            val built = (beats + captionBeats).associateWith { b -> b.recipe.build(b.cue.copy(out = b.out), look, fitter) }

            val transitions = mutableListOf<Node>()
            val transitionSfx = mutableListOf<Sfx>()
            val sceneGroups = scenes.indices.map { k -> sceneGroup(k, built, transitions, transitionSfx) }
            // Footage already has its own texture: lighter grain and vignette over it.
            val texture = if (input.footage != null) FOOTAGE_TEXTURE else 1f
            val overlays = built.values.flatMap { it.overlays } + transitions + listOf(
                EffectNode(Effect.Vignette((look.vignette * texture).anim)),
                EffectNode(Effect.Grain(look.grain * texture)),
            )
            val root = Group(base() + sceneGroups + captionBeats.flatMap { built.getValue(it).nodes } + overlays, name = "root")
            val camera = camera(scenes, sceneStarts, sceneEnds, built.values.flatMap { it.camera }, footage = input.footage != null)
            val sfx = mixSfx(built.values.flatMap { it.sfx } + transitionSfx)
            val placed = (beats + captionBeats).sortedBy { it.cue.at }.map { Placed(it.recipe.name, it.zone.name.lowercase(), it.cue.at, it.out, it.cue.text) }
            return Output(Composition(w, h, input.fps, duration, look.canvas, root, camera), sfx, placed, notes)
        }

        /** What, when and where for one requested beat. */
        private fun resolve(b: BeatScore, k: Int, scene: SceneScore, range: IntRange, id: Int): Beat {
            val recipe = Recipes.named(b.recipe) ?: guess(b).also { notes += "recipe '${b.recipe}' → ${it.name}" }
            val quoted = b.text?.let { locate(it, norm, range) }
            val atIndex = b.at ?: quoted?.first
            val at = snap(b.time ?: atIndex?.let { start(it) } ?: (sceneStarts[k] + 0.2f), recipe, score, notes)
            val lastIndex = b.until ?: quoted?.last ?: atIndex
            val spokenText = if (recipe.kind == Recipe.Kind.Text && atIndex != null) (atIndex..(lastIndex ?: atIndex)).joinToString(" ") { words[it].text } else ""
            val beatText = clean(b.text ?: spokenText)
            val readable = maxOf(MIN_READ, 0.35f + 0.055f * beatText.length, recipe.minHold)
            val out = minOf(maxOf(naturalEnd(b, recipe, at, lastIndex, sceneEnds[k], readable), at + readable), sceneEnds[k] + 0.05f, at + MAX_HOLD)
            val words = beatText.split(' ').count { it.isNotEmpty() }
            val minOut = minOf(at + readable, out)
            val over = overFootage(scene)
            val wanted = layout.zoneOf(b.place, defaultZone(recipe)).let { if (over) layout.zoneOverFootage(it) else it }
            val zone = layout.place(wanted, at, out, minOut, id, locked = recipe.name == "lower-third", allowed = if (over) layout.footageZones else Layout.Zone.entries.toSet())
            val slot = layout.slot(zone, recipe.preferredHeight, aroundSubject = over)
            val cue = Cue(
                text = beatText, at = at, out = out, x = slot.x, y = slot.y, width = slot.w, height = slot.h,
                energy = (b.energy ?: defaultEnergy(recipe)).coerceIn(0f, 1f), emphasis = emphasisOf(beatText, b.emphasis, quoted, this.words),
                wordTimes = quoted?.takeIf { it.count() == words }?.map { start(it) },
                value = b.value, from = b.from, prefix = b.prefix ?: "", suffix = b.suffix ?: "", decimals = b.decimals ?: 0,
                points = b.points, icon = b.icon, label = b.label, mark = b.mark, overMedia = over,
                items = b.items, itemTimes = b.items.map { item -> locate(item, norm, range)?.let { start(it.first) } },
                marks = b.items.map { brands.mark(it) },
                rtl = isRtl(beatText.ifBlank { b.label ?: "" }) || (beatText.isBlank() && this.words.any { isRtl(it.text) }), seed = id * 7 + 3,
            )
            return Beat(id, recipe, cue, zone, k, minOut)
        }

        /** Elements not tied to words stay for the scene; spoken text stays while it is said. */
        private fun naturalEnd(b: BeatScore, recipe: Recipe, at: Float, lastIndex: Int?, sceneEnd: Float, readable: Float): Float {
            b.hold?.let { return at + it }
            val spokenEnd = lastIndex?.let { end(it) + LINGER }
            val tied = recipe.kind == Recipe.Kind.Text || b.text != null || b.until != null
            return when {
                spokenEnd != null && tied -> spokenEnd
                recipe.kind == Recipe.Kind.Element -> sceneEnd
                else -> at + readable
            }
        }

        /** A scene: its background from the cut, its beats, and its transition moves. */
        private fun sceneGroup(k: Int, built: Map<Beat, Built>, transitions: MutableList<Node>, sfx: MutableList<Sfx>): Group {
            val scene = scenes[k]
            val s = sceneStarts[k]
            val e = sceneEnds[k]
            val nodes = mutableListOf<Node>()
            // Backgrounds exist only from the cut: before it, the previous scene is still on screen.
            when (sceneBg(scene, input)) {
                // Over footage an aurora scene needs its own field; without footage the base already is one.
                "aurora" -> if (input.footage != null) nodes += EffectNode(Effect.Aurora(look.aurora, seed = k + 1), start = s)
                "grid" -> {
                    nodes += EffectNode(Effect.Aurora(look.aurora, seed = k + 1), start = s)
                    nodes += EffectNode(Effect.Grid(w / 9f, look.ink, 0.07f.anim), start = s)
                    nodes += EffectNode(Effect.Vignette(0.55f.anim), start = s)
                }
                "plain" -> nodes += EffectNode(Effect.Flash(look.canvas, Anim.One), start = s)
            }
            built.filterKeys { it.scene == k }.values.forEach { nodes += it.nodes }
            val enter = scene.transition?.lowercase().takeIf { k > 0 }
            val (tx, sc, op) = sceneMotion(enter, scenes.getOrNull(k + 1)?.transition?.lowercase(), s, e, w.toFloat())
            transitionOverlay(enter, s, look, transitions, sfx)
            val t = Transform(anchorX = 0.5f, anchorY = 0.5f, x = (w / 2f).anim + tx, y = (h / 2f).anim, scale = sc, opacity = op)
            return Group(nodes, transform = t, start = s - 0.6f, end = e + 0.3f, name = "scene-$k")
        }

        /**
         * Footage, cut to the edit, graded, with scrims so text never depends on the picture, and
         * punched in on the speaker scene by scene (jump-cut rhythm); or a living gradient.
         */
        private fun base(): List<Node> = if (input.footage != null) {
            val zoom = footageZoom()
            val sub = input.subject ?: Subject(0.3f, 0.3f, 0.7f, 0.6f)
            val segments = input.edit?.segments ?: listOf(EditPlan.Segment(0f, duration, 0f))
            segments.mapIndexed { i, seg ->
                MediaNode(
                    input.footage, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = seg.sourceStart, grade = gradeOf(look),
                    transform = Transform(x = (w * sub.centerX).anim, y = (h * sub.centerY).anim, anchorX = sub.centerX, anchorY = sub.centerY, scale = zoom),
                    start = seg.outStart, end = if (i == segments.lastIndex) Float.POSITIVE_INFINITY else seg.outEnd, name = "footage-$i",
                )
            } + listOf(
                EffectNode(Effect.Scrim(fromBottom = true, coverage = 0.4f, opacity = 0.5f.anim)),
                EffectNode(Effect.Scrim(fromBottom = false, coverage = 0.36f, opacity = 0.42f.anim)),
            )
        } else {
            listOf(EffectNode(Effect.Aurora(look.aurora)))
        }

        /** Punch-ins on footage scenes: alternate tight and wide at each cut, with a slow push. */
        private fun footageZoom(): Anim {
            val keys = mutableListOf<Anim.Key>()
            var tight = false
            for ((k, scene) in scenes.withIndex()) {
                if (!overFootage(scene)) continue
                val kind = scene.camera?.lowercase() ?: "punch"
                val (from, to) = when (kind) {
                    "still" -> 1f to 1f
                    "pull-out", "pull", "zoom-out" -> 1.12f to 1.03f
                    "push-in", "push", "zoom-in" -> 1f to 1.07f
                    else -> (if (tight) 1.13f else 1.0f).let { it to it + 0.03f }
                }
                tight = !tight
                keys += Anim.Key(sceneStarts[k], from, Easing.Hold)
                keys += Anim.Key(sceneEnds[k] - 0.001f, to, Easing.SineInOut)
            }
            return if (keys.isEmpty()) Anim.One else Anim.keys(keys)
        }
    }

    // ------------------------------------------------------------------ captions

    private fun captions(session: Session, beats: List<Beat>): List<Beat> {
        val score = session.score
        val layout = session.layout
        val input = session.input
        val recipe = Recipes.named(score.captions.recipe) ?: TextRecipes.PopCaptions
        val zone = layout.zoneOf(score.captions.place, Layout.Zone.Lower)
        val lines = Words.captionLines(session.transcript!!.words, score.captions.maxWords.coerceIn(1, 8))
        val emphasisWords = beats.flatMap { b -> b.cue.emphasis.mapNotNull { b.cue.words.getOrNull(it) } }.map(NumberWords::normalize).toSet()
        val out = mutableListOf<Beat>()
        var skipped = 0
        for ((i, line) in lines.withIndex()) {
            val at = line.first().range.startMs / 1000f
            val next = lines.getOrNull(i + 1)?.first()?.range?.startMs?.div(1000f) ?: Float.MAX_VALUE
            val end = minOf(line.last().range.endMs / 1000f + 0.35f, next - 0.03f)
            val headline = beats.any { b -> b.recipe.kind == Recipe.Kind.Text && overlap(b.cue.at, b.out, at, end) > (end - at) * 0.35f }
            if (headline || layout.busy(zone, at, end)) { skipped++; continue }
            val lineWords = line.map { it.text }
            // One highlight per line: the strongest word (prosody, a number, or the director's pick).
            val strongest = line.indices.filter { Words.isContent(lineWords[it]) }.maxByOrNull { j ->
                val n = NumberWords.normalize(lineWords[j])
                line[j].emphasis + (if (n in emphasisWords) 1f else 0f) + (if (NumberWords.at(lineWords, j) != null) 0.5f else 0f)
            }
            val strong = strongest?.takeIf { j -> stands(line[j].emphasis, NumberWords.normalize(lineWords[j]) in emphasisWords, NumberWords.at(lineWords, j)) }
            val k = session.sceneStarts.indexOfLast { it <= at }.coerceAtLeast(0)
            val over = session.overFootage(session.scenes[k])
            val slot = layout.slot(zone, recipe.preferredHeight, aroundSubject = over)
            val cue = Cue(
                text = clean(lineWords.joinToString(" ")), at = at, out = end, x = slot.x, y = slot.y, width = slot.w, height = slot.h,
                energy = 0.5f, emphasis = setOfNotNull(strong), wordTimes = line.map { it.range.startMs / 1000f },
                overMedia = over, rtl = lineWords.any(::isRtl), seed = i,
            )
            out += Beat(CAPTION_ID_BASE + i, recipe, cue, zone, -1, end)
        }
        if (skipped > 0) session.notes += "captions: $skipped lines yield to headlines or busy zones"
        return out
    }

    // ------------------------------------------------------------------ helpers

    /** Whether a caption word earns the line's one highlight. */
    private fun stands(prosody: Float, picked: Boolean, number: NumberWords.Found?) =
        prosody >= 0.6f || picked || (number != null && (number.percent || number.value >= 10))

    /** Motion text drops sentence punctuation (periods, commas), keeping ? and !. */
    private fun clean(s: String) = s.split(' ').filter { it.isNotEmpty() }
        .map { it.trimEnd('.', ',', '،', '؛', ';', ':', '…') }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0f)

    private fun frameOf(format: String): Pair<Int, Int> = when (format.trim()) {
        "1:1" -> 1080 to 1080
        "4:5" -> 1080 to 1350
        "16:9" -> 1920 to 1080
        else -> 1080 to 1920
    }

    private fun sceneBg(scene: SceneScore, input: Input): String =
        scene.bg?.lowercase()?.takeIf { it in setOf("media", "aurora", "grid", "plain") }?.let { if (it == "media" && input.footage == null) "aurora" else it }
            ?: if (input.footage != null) "media" else "aurora"

    private fun defaultZone(r: Recipe) = when (r.name) {
        "lower-third" -> Layout.Zone.Lower
        "ticker", "icon", "stamp" -> Layout.Zone.Top
        "stack" -> Layout.Zone.Full
        else -> Layout.Zone.Center
    }

    private fun defaultEnergy(r: Recipe) = when (r.name) {
        "slam", "stamp", "glitch" -> 0.85f
        "blur-in" -> 0.3f
        else -> 0.6f
    }

    /** A recipe for a beat whose name did not resolve: by what the beat carries. */
    private fun guess(b: BeatScore): Recipe = when {
        b.points != null -> Recipes.named("chart")!!
        b.value != null -> Recipes.named("counter")!!
        b.icon != null -> Recipes.named("icon")!!
        (b.energy ?: 0f) > 0.75f -> TextRecipes.Slam
        else -> TextRecipes.MaskRise
    }

    /** Hits land on the music's beat when one is within reach. */
    private fun snap(at: Float, recipe: Recipe, score: Score, notes: MutableList<String>): Float {
        val bpm = score.bpm ?: return at
        if (recipe.name !in setOf("slam", "stamp", "icon", "counter", "glitch", "stack")) return at
        val period = 60f / bpm
        val k = ((at - score.beatOffset) / period).roundToInt()
        val beat = score.beatOffset + k * period
        return if (abs(beat - at) <= SNAP_WINDOW) beat.also { if (abs(beat - at) > 0.01f) notes += "snapped ${recipe.name} to beat" } else at
    }

    /** Where quoted text sits in the transcript: the best window starting in (or near) the scene. */
    private fun locate(quote: String, norm: List<String>, range: IntRange): IntRange? {
        val q = quote.split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }
        if (q.isEmpty() || norm.isEmpty()) return null
        fun same(a: String, b: String) = a == b || (a.length > 2 && b.startsWith(a)) || (b.length > 2 && a.startsWith(b))
        fun score(i: Int) = q.indices.count { j -> norm.getOrNull(i + j)?.let { same(it, q[j]) } == true }
        val candidates = (maxOf(0, range.first - 2)..minOf(norm.size - 1, maxOf(range.last, range.first) + 2)).toList() + norm.indices
        val best = candidates.maxByOrNull { score(it) * 10 - (if (it in range) 0 else 5) } ?: return null
        val hits = score(best)
        return if (hits * 2 >= q.size) best..minOf(norm.size - 1, best + q.size - 1) else null
    }

    private fun emphasisOf(text: String, wanted: List<String>, quoted: IntRange?, words: List<io.trimio.core.model.transcript.Word>): Set<Int> {
        val tokens = text.split(' ').filter { it.isNotEmpty() }.map(NumberWords::normalize)
        val picked = wanted.flatMap { phrase ->
            val parts = phrase.split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }
            val i = tokens.indices.firstOrNull { s -> parts.indices.all { j -> tokens.getOrNull(s + j) == parts[j] } }
            if (i == null) emptyList() else (i until i + parts.size).toList()
        }.toSet()
        if (picked.isNotEmpty() || quoted == null) return picked
        // No explicit pick: the most stressed spoken word, if it really stands out.
        val strongest = quoted.maxByOrNull { words[it].emphasis } ?: return emptySet()
        return if (words[strongest].emphasis >= 0.6f) setOf(strongest - quoted.first) else emptySet()
    }

    private fun gradeOf(look: Look) = when (look.name) {
        "paper" -> Grade(contrast = 1.04f.anim, saturation = 0.85f.anim)
        "lumen" -> Grade(contrast = 1.06f.anim, saturation = 0.95f.anim, temperature = (-0.4f).anim)
        else -> Grade(exposure = 0.04f.anim, contrast = 1.06f.anim, saturation = 1.0f.anim, temperature = 0.15f.anim)
    }

    /** Slide/scale/opacity of a scene group for its incoming and outgoing transitions. */
    private fun sceneMotion(enter: String?, exit: String?, s: Float, e: Float, w: Float): Triple<Anim, Anim, Anim> {
        val xKeys = mutableListOf(Anim.Key(s - 1f, 0f, Easing.Hold))
        val scaleKeys = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        val opKeys = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        when (enter) {
            "whip" -> { xKeys += Anim.Key(s, w * 1.1f, Easing.Hold); xKeys += Anim.Key(s + 0.24f, 0f, Easing.ExpoOut) }
            "zoom" -> {
                scaleKeys += Anim.Key(s, 0.82f, Easing.Hold); scaleKeys += Anim.Key(s + 0.4f, 1f, Easing.ExpoOut)
                opKeys += Anim.Key(s, 0f, Easing.Hold); opKeys += Anim.Key(s + 0.12f, 1f, Easing.Linear)
            }
        }
        when (exit) {
            "whip" -> { xKeys += Anim.Key(e - 0.16f, 0f, Easing.Hold); xKeys += Anim.Key(e, -w * 1.1f, Easing.ExpoIn) }
            "zoom" -> {
                scaleKeys += Anim.Key(e - 0.2f, 1f, Easing.Hold); scaleKeys += Anim.Key(e, 1.4f, Easing.ExpoIn)
                opKeys += Anim.Key(e - 0.12f, 1f, Easing.Hold); opKeys += Anim.Key(e, 0f, Easing.Linear)
            }
        }
        return Triple(Anim.keys(xKeys), Anim.keys(scaleKeys), Anim.keys(opKeys))
    }

    private fun transitionOverlay(kind: String?, s: Float, look: Look, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        when (kind) {
            "whip" -> sfx += Sfx(s - 0.12f, SfxKind.Whoosh, 0.9f)
            "zoom" -> sfx += Sfx(s - 0.15f, SfxKind.Whoosh, 0.8f)
            "flash" -> {
                val flash = io.trimio.engine.motion.anim(0f, s - 0.001f) { by(0.85f, 0.001f, Easing.Hold); by(0f, 0.22f, Easing.ExpoOut) }
                overlays += EffectNode(Effect.Flash(Color.White, flash), start = s - 0.01f, end = s + 0.25f)
                sfx += Sfx(s, SfxKind.Hit, 0.9f)
            }
            "leak" -> {
                val strength = io.trimio.engine.motion.anim(0f, s - 0.35f) { by(0.85f, 0.35f, Easing.SineInOut); by(0f, 0.8f, Easing.SineInOut) }
                overlays += EffectNode(Effect.LightLeak(look.hot, Anim.tween(0f, 1f, s - 0.35f, s + 0.8f, Easing.SineInOut), strength), start = s - 0.4f, end = s + 0.9f)
                sfx += Sfx(s - 0.3f, SfxKind.Shimmer, 0.7f)
            }
        }
    }

    private fun camera(scenes: List<SceneScore>, starts: List<Float>, ends: List<Float>, moves: List<CameraMove>, footage: Boolean): Camera {
        val keys = mutableListOf<Anim.Key>()
        val drift = mutableListOf<Anim>()
        for ((k, scene) in scenes.withIndex()) {
            val s = starts[k]
            val e = ends[k]
            // Footage zooms itself (punch-ins on the speaker); the camera keeps graphics steady.
            val kind = if (footage) "still" else scene.camera?.lowercase() ?: "drift"
            val (from, to) = when (kind) {
                "push-in", "push", "zoom-in" -> (if (k % 2 == 0) 1f else 1.05f) to (if (k % 2 == 0) 1.06f else 1.11f)
                "pull-out", "pull", "zoom-out" -> 1.1f to 1.02f
                "still" -> 1f to 1f
                else -> 1.02f to 1.02f
            }
            keys += Anim.Key(s, from, Easing.Hold)
            keys += Anim.Key(e - 0.001f, to, Easing.SineInOut)
            if (kind == "drift") {
                drift += Anim.Noise(k + 5, 0.25f, io.trimio.engine.motion.anim(0f, s) { by(14f, 0.5f); hold(e - 0.5f); by(0f, 0.5f) })
            }
        }
        val zoom = moves.fold(Anim.keys(keys)) { acc, m -> acc + m.zoom }
        val x = moves.fold(drift.fold(Anim.Zero) { a, d -> a + d }) { acc, m -> acc + m.x }
        val y = moves.fold(Anim.Zero) { acc, m -> acc + m.y }
        val r = moves.fold(Anim.Zero) { acc, m -> acc + m.rotation }
        return Camera(zoom, x, y, r)
    }

    private fun mixSfx(all: List<Sfx>): List<Sfx> {
        val out = mutableListOf<Sfx>()
        for (s in all.sortedWith(compareBy({ it.at }, { -it.gain }))) {
            val prev = out.lastOrNull()
            when {
                prev == null || s.at - prev.at >= MIN_SFX_GAP -> out += s
                s.gain > prev.gain -> out[out.size - 1] = s
            }
        }
        return out
    }

    private object Craft {
        const val SCENE_LEAD = 0.12f
    }

    companion object {
        private const val FIT_STEPS = 28
        private const val FOOTAGE_TEXTURE = 0.35f
        private const val MIN_READ = 1.0f
        private const val MAX_HOLD = 7f
        private const val LINGER = 0.55f
        private const val TAIL = 0.8f
        private const val SNAP_WINDOW = 0.1f
        private const val MIN_SFX_GAP = 0.12f
        private const val CAPTION_ID_BASE = 100_000

        fun isRtl(s: String) = s.any { it in '؀'..'ۿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }

        /** Scenes from sentences, each about 3–6 seconds. */
        fun autoScenes(transcript: Transcript?): List<SceneScore> {
            val words = transcript?.words.orEmpty()
            if (words.isEmpty()) return listOf(SceneScore())
            val scenes = mutableListOf(SceneScore(from = 0))
            var sceneStart = words.first().range.startMs
            for ((i, w) in words.withIndex()) {
                val next = words.getOrNull(i + 1) ?: break
                val long = next.range.startMs - sceneStart >= AUTO_SCENE_MS
                if ((w.endsSentence && next.range.startMs - sceneStart >= AUTO_SCENE_MIN_MS) || long) {
                    scenes += SceneScore(from = i + 1, transition = if (scenes.size % 2 == 1) "whip" else "flash")
                    sceneStart = next.range.startMs
                }
            }
            return scenes
        }

        private const val AUTO_SCENE_MS = 6000L
        private const val AUTO_SCENE_MIN_MS = 2600L
    }
}
