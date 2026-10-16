package io.trimio.engine.motion.score

import io.trimio.engine.motion.art.styled
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Camera
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Filter
import io.trimio.engine.motion.Fit
import io.trimio.engine.motion.Grade
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Mask
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill
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
import io.trimio.engine.motion.recipe.Stages
import io.trimio.engine.motion.recipe.TextRecipes
import io.trimio.engine.motion.recipe.Variety
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a [Score] into a finished [Composition]. The model says *what*; the compiler decides
 * *when* (word timing, two-frame lead, beat snapping, readable durations), *where* (safe zones,
 * collision-free placement, text fitted to its slot), and adds the polish a senior editor would:
 * captions that step aside for headlines, one highlight per line, scrims under text on footage,
 * camera life, transitions with sound, grain and vignette from the look.
 */
class Compiler(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary = BrandLibrary.Empty,
    private val visuals: io.trimio.engine.motion.visual.VisualLibrary = io.trimio.engine.motion.visual.VisualLibrary.Empty,
    /** Keep every landed graphic inside the frame's safe area, measured by drawing it ([Geometry]); off only for raw layout tests. */
    private val inside: Boolean = true,
) {
    private val geometry by lazy { Geometry(text.measurer, text.fonts) }

    /** What a beat is, for [restMemo]: its recipe, its cue (where, when, what) and the look. */
    private data class Rest(val recipe: String, val cue: Cue, val look: String, val w: Int, val h: Int)

    /**
     * What the camera does to a resting graphic: its closest zoom (about the frame's centre) and
     * how far it drifts off. A bound safe on screen is then a bound pulled that much toward the
     * centre (and in by the drift) in the graphic's own coordinates.
     */
    private class Lens(val zoom: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {
        /** [bound] (a screen x on the [near] side of the centre or the far one) as an x before the camera. */
        fun x(bound: Float, size: Float, near: Boolean) = inward(bound, size, dx, near)
        fun y(bound: Float, size: Float, near: Boolean) = inward(bound, size, dy, near)

        private fun inward(bound: Float, size: Float, off: Float, near: Boolean): Float {
            val c = size / 2f
            val z = maxOf(1f, zoom)
            return c + (bound - c) / z + if (near) off else -off
        }

        companion object {
            private const val LOOKS = 9

            fun over(camera: Camera, from: Float, to: Float): Lens {
                val ts = (0 until LOOKS).map { from + (to - from).coerceAtLeast(0f) * it / (LOOKS - 1) }
                return Lens(ts.maxOf { camera.zoom.at(it) }, ts.maxOf { abs(camera.x.at(it)) }, ts.maxOf { abs(camera.y.at(it)) })
            }
        }
    }

    /** Where beats rest, drawn once per beat as long as it does not change (oldest first out). */
    private val restMemo = LinkedHashMap<Rest, Pair<Geometry.Ink?, Geometry.Ink?>>()

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
        /** Who speaks when, on the source clock, when two voices or more were heard: each voice's captions get its own colour. */
        val voices: List<Voice> = emptyList(),
        /**
         * The speaker can be cut out of the footage ([io.trimio.engine.motion.MediaSource.matte]): a short title is then set
         * behind their head, the signature of a premium edit; without it nothing is ever placed behind them.
         */
        val matte: Boolean = false,
        /** The top of the speaker's head, as a share of the frame's height (found through the matte), when known. */
        val crown: Float? = null,
    )

    /** A beat as placed: its slot in frame pixels ([left], [top], [right], [bottom]) and on-screen time. */
    data class Placed(
        val recipe: String, val zone: String, val at: Float, val out: Float, val text: String,
        val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f,
        /** Drawn over the speaker's footage (not on a full-frame stage). */
        val overFootage: Boolean = false,
        /** Which treatment of the recipe was built ([Recipe.variants]): layout, choreography and type. */
        val variant: Int = 0,
        /** Set behind the speaker: they stand in front of it, so it covers no part of them. */
        val behind: Boolean = false,
    )

    data class Output(
        val composition: Composition, val sfx: List<Sfx>, val beats: List<Placed>, val notes: List<String>,
        /** Each placed beat's own layers in the composition (same order as [beats]): what [Geometry] draws alone. */
        val layers: List<List<Node>> = emptyList(),
    )

    private class Beat(
        val id: Int,
        val recipe: Recipe,
        val cue: Cue,
        val zone: Layout.Zone,
        val scene: Int,
        val minOut: Float,
        /** Set behind the speaker's head ([Input.matte]). */
        val behind: Boolean = false,
        /** A designer's accent placed from the beat's box ([BeatScore.accent]). */
        val accent: io.trimio.engine.motion.visual.VectorIcon? = null,
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
        val look = Look.named(score.look).withAccent(score.accent).let { l ->
            l.copy(backdrop = score.background ?: l.backdrop, lens = score.lens ?: l.lens)
        }
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
            val nominal = scenes.mapIndexed { k, sc ->
                if (k == 0) 0f else (sc.time ?: sc.from?.let { start(it) - Craft.SCENE_LEAD } ?: 0f).coerceIn(0f, duration)
            }
            sceneStarts = nominal.mapIndexed { k, s -> if (k == 0) s else stageStart(scenes[k], s, nominal.getOrElse(k + 1) { duration }, nominal[k - 1]) }
            sceneEnds = sceneStarts.drop(1) + duration
        }

        /**
         * A takeover cuts in with its graphic: an empty stage before the graphic lands reads as a
         * mistake, so the speaker stays on screen until just before it.
         */
        private fun stageStart(scene: SceneScore, nominal: Float, next: Float, previous: Float): Float {
            if (input.footage == null || sceneBg(scene, input) == "media") return nominal
            val from = scene.from ?: 0
            val lands = scene.beats.mapNotNull { b ->
                b.time ?: b.at?.let { start(it) } ?: b.text?.let { t -> locate(t, norm, from..words.lastIndex.coerceAtLeast(from))?.let { start(it.first) } }
            }.minOrNull() ?: return nominal
            // A graphic brought in early (to be read in full) brings its stage with it: never a card over the speaker
            // for a moment before the frame turns into its stage.
            val earliest = minOf(nominal, maxOf(previous + MIN_SCENE, lands - Craft.SCENE_LEAD))
            return (lands - Craft.SCENE_LEAD).coerceIn(earliest, maxOf(earliest, next - MIN_SCENE))
        }

        fun start(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.startMs?.div(1000f) ?: 0f
        fun end(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.endMs?.div(1000f) ?: 0f

        /** Footage is on screen behind this scene (not covered by a full-frame takeover). */
        fun overFootage(scene: SceneScore) = input.footage != null && sceneBg(scene, input) == "media"

        /** Output time the piece restarts after a cold open (null without one). */
        val restart: Float? = input.edit?.takeIf { it.opening.isNotEmpty() }?.preludeLength

        /** A scene's entering transition, unless the cold open's restart already is one. */
        fun entering(k: Int): String? = scenes.getOrNull(k)?.transition?.lowercase()
            ?.takeIf { k > 0 && restart?.let { r -> abs(sceneStarts[k] - r) < RESTART_WINDOW } != true }

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
            // A card pushed out before it could be read is never on screen at all: a flash is no graphic.
            beats.removeAll { it.out - it.cue.at < MIN_SHOWN }
            holdStages(beats)
            // Graphics first, kept inside the frame: the captions then yield to where each one really rests.
            val graphics = keepInside(beats.associateWith { b -> b.recipe.build(b.cue.copy(out = b.out), look, fitter) }).mapValues { (b, built) ->
                // The designer's accent goes where the beat really landed.
                val accent = b.accent ?: return@mapValues built
                val box = built.box ?: io.trimio.engine.motion.recipe.Box.around(b.cue.x, b.cue.y, b.cue.width, b.cue.height)
                built.copy(nodes = built.nodes + io.trimio.engine.motion.art.TemplateRecipes.accent(accent, box, b.cue.at, b.out, b.cue.rtl))
            }
            val captionBeats = if (score.captions.show && words.isNotEmpty()) captions(this, beats) else emptyList()
            val built = graphics + captionBeats.associateWith { b -> b.recipe.build(b.cue.copy(out = b.out), look, fitter) }
            // Text over footage keeps the look's contrast floor whatever the picture does behind it.
            for ((b, out) in built) if (b.cue.overMedia) out.nodes.forEach { guard(it, look.guard) }

            val transitions = mutableListOf<Node>()
            val transitionSfx = mutableListOf<Sfx>()
            // Out of a cold open the piece starts over: a rewind into a white dip, "here's how".
            restart?.let { r -> rewind(r, transitions, transitionSfx) }
            val sceneGroups = scenes.indices.map { k -> sceneGroup(k, built, transitions, transitionSfx) }
            // Footage already has its own texture: lighter grain and vignette over it.
            val texture = if (input.footage != null) FOOTAGE_TEXTURE else 1f
            val overlays = built.values.flatMap { it.overlays } + transitions + listOfNotNull(
                EffectNode(Effect.Vignette((look.vignette * texture).anim)),
                EffectNode(Effect.Grain(look.grain * texture)).takeIf { look.grain > 0f },
            )
            // The speaker stands in front of a title set behind them: their cut-out over it, under the captions.
            val people = beats.filter { it.behind }.flatMap { b -> person(b.cue.at - PERSON_LEAD, b.out) }
            val root = Group(picture(base(), "footage") + sceneGroups + people + captionBeats.flatMap { built.getValue(it).nodes } + overlays, name = "root")
            val camera = camera(scenes, sceneStarts, sceneEnds, built.values.flatMap { it.camera }, footage = input.footage != null)
            val sfx = mixSfx(built.values.flatMap { it.sfx } + transitionSfx, duration)
            val order = (beats + captionBeats).sortedBy { it.cue.at }
            val placed = order.map {
                val c = it.cue
                // A recipe that knows where it landed (tighter than its slot) reports that box.
                val box = built.getValue(it).box
                Placed(
                    it.recipe.name, it.zone.name.lowercase(), c.at, it.out, c.text,
                    box?.left ?: (c.x - c.width / 2f), box?.top ?: (c.y - c.height / 2f), box?.right ?: (c.x + c.width / 2f), box?.bottom ?: (c.y + c.height / 2f),
                    c.overMedia, c.variant, it.behind,
                )
            }
            return Output(Composition(w, h, input.fps, duration, look.canvas, root, camera), sfx, placed, notes, order.map { built.getValue(it).nodes })
        }

        /**
         * Every graphic, once it has landed, inside the frame's safe area — the platform's bars and
         * buttons kept clear: drawn alone where it rests ([Geometry.rest]) and, when its ink (or, for
         * a band across the frame, its words) reaches past the safe area, moved in, and shrunk when
         * it is larger than the area. Its entrance and exit move with it. Full-frame stages and
         * captions (placed in their own band) are left as they are.
         */
        /** Where each graphic rests once kept inside (frame px), by beat id: the captions keep off it. */
        val rests = mutableMapOf<Int, Geometry.Ink>()

        private fun keepInside(built: Map<Beat, Built>): Map<Beat, Built> {
            if (!inside) return built
            // The camera moves the graphics with the picture (a push-in, a slam's kick, the drift):
            // the safe area is what stays safe at the camera's closest and furthest-off frame.
            val camera = camera(scenes, sceneStarts, sceneEnds, built.values.flatMap { it.camera }, footage = input.footage != null)
            return built.mapValues { (b, out) ->
                if (b.id >= CAPTION_ID_BASE || out.nodes.isEmpty()) return@mapValues out
                val end = b.out - REST_EXIT
                val from = minOf(b.cue.at + REST_ENTRANCE, end)
                val times = listOf(from, (from + end) / 2f, end).distinct()
                // The same beat drawn the same way rests in the same place: a jury round recompiles mostly unchanged beats.
                val key = Rest(b.recipe.name, b.cue.copy(out = b.out), look.name, w, h)
                val (ink, type) = restMemo.getOrPut(key) { geometry.rest(out.nodes, w, h, times) }
                if (restMemo.size > REST_MEMO) restMemo.remove(restMemo.keys.first())
                ink?.let { rests[b.id] = it }
                val lens = Lens.over(camera, from, end)
                // A callout points at the speaker or rings their head: the face is its subject, not a place to keep off.
                val move = ink?.let { filled(b, it) ?: safely(it, type, b.cue.overMedia, lens, avoidFace = b.recipe.name != "callout" && !b.behind) } ?: return@mapValues out
                rests[b.id] = move.third
                notes += "kept ${b.recipe.name} inside the frame"
                out.copy(nodes = listOf(Group(out.nodes, transform = move.first, name = "keep-inside")), box = out.box?.let(move.second))
            }
        }

        /**
         * A graphic on a stage of its own (the footage covered) is the scene: a compact card drawn for a band over the
         * speaker would sit small in an empty frame, so it grows (to [STAGE_GROW] at most) until it fills its slot, as an
         * editor scales a card up when it takes the whole frame. Null when it already fills it, or over the footage.
         */
        private fun filled(b: Beat, ink: Geometry.Ink): Triple<Transform, (io.trimio.engine.motion.recipe.Box) -> io.trimio.engine.motion.recipe.Box, Geometry.Ink>? {
            if (b.cue.overMedia || input.footage == null || b.zone != Layout.Zone.Center) return null
            if (ink.width < 1f || ink.height < 1f) return null
            val fw = w.toFloat()
            val fh = h.toFloat()
            val room = minOf(b.cue.width, fw * (1f - 2f * SAFE_SIDE)) * STAGE_FILL
            val k = minOf(room / ink.width, b.cue.height / ink.height, STAGE_GROW)
            if (k < STAGE_MIN_GROW) return null
            val cx = (ink.left + ink.right) / 2f
            val cy = (ink.top + ink.bottom) / 2f
            val nx = fw / 2f
            val ny = b.cue.y
            val t = Transform(x = nx.anim, y = ny.anim, anchorX = cx / fw, anchorY = cy / fh, scale = k.anim)
            val map = { r: io.trimio.engine.motion.recipe.Box ->
                io.trimio.engine.motion.recipe.Box(nx + (r.left - cx) * k, ny + (r.top - cy) * k, nx + (r.right - cx) * k, ny + (r.bottom - cy) * k)
            }
            return Triple(t, map, Geometry.Ink(nx + (ink.left - cx) * k, ny + (ink.top - cy) * k, nx + (ink.right - cx) * k, ny + (ink.bottom - cy) * k))
        }

        /** Whether [box] covers more than [FACE_SHARE] of the speaker's face. */
        private fun covers(box: Geometry.Ink, face: Subject): Boolean {
            val f = Geometry.Ink(face.left * w, face.top * h, face.right * w, face.bottom * h)
            return f.area > 0f && box.shared(f) / f.area > FACE_SHARE
        }

        /** The move that brings [ink] (its letters [type]) inside the safe area, and what it does to a box; null when it is inside. */
        private fun safely(
            ink: Geometry.Ink,
            type: Geometry.Ink?,
            overMedia: Boolean,
            lens: Lens = Lens(),
            avoidFace: Boolean = true,
        ): Triple<Transform, (io.trimio.engine.motion.recipe.Box) -> io.trimio.engine.motion.recipe.Box, Geometry.Ink>? {
            val fw = w.toFloat()
            val fh = h.toFloat()
            // A full-frame stage fills the frame on purpose; a band across it may run off the sides, its words may not.
            if (ink.width >= fw * FULL && ink.height >= fh * FULL) return null
            val band = ink.width >= fw * FULL
            val box = if (band) Geometry.Ink((type ?: ink).left, ink.top, (type ?: ink).right, ink.bottom) else ink
            val tall = h > w
            var top = lens.y(fh * SAFE_TOP, fh, near = true)
            var bottom = lens.y(fh * if (tall) SAFE_BOTTOM_TALL else SAFE_BOTTOM, fh, near = false)
            // Over the speaker the face is off limits too: a graphic above it stays above the brow, one below it under the chin.
            val face = input.subject?.face?.takeIf { overMedia && avoidFace }
            if (face != null && covers(box, face)) {
                if ((box.top + box.bottom) / 2f < face.centerY * fh) bottom = minOf(bottom, face.top * fh - FACE_GAP) else top = maxOf(top, face.bottom * fh + FACE_GAP)
            }
            if (bottom - top < box.height * MIN_FIT) return null
            val left = lens.x(fw * SAFE_SIDE, fw, near = true)
            // The platform's buttons run down the right of a vertical video, from about the middle.
            val right = lens.x(fw * if (tall && box.bottom > fh * BUTTONS_FROM) SAFE_BUTTONS else 1f - SAFE_SIDE, fw, near = false)
            val k = minOf(1f, (right - left) / box.width.coerceAtLeast(1f), (bottom - top) / box.height.coerceAtLeast(1f))
            val bw = box.width * k
            val bh = box.height * k
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            val nx = cx.coerceIn(left + bw / 2f, maxOf(left + bw / 2f, right - bw / 2f))
            val ny = cy.coerceIn(top + bh / 2f, maxOf(top + bh / 2f, bottom - bh / 2f))
            if (k > 1f - SHRINK_TOL && abs(nx - cx) < NUDGE_TOL && abs(ny - cy) < NUDGE_TOL) return null
            val t = Transform(x = nx.anim, y = ny.anim, anchorX = cx / fw, anchorY = cy / fh, scale = k.anim)
            val map = { r: io.trimio.engine.motion.recipe.Box ->
                io.trimio.engine.motion.recipe.Box(nx + (r.left - cx) * k, ny + (r.top - cy) * k, nx + (r.right - cx) * k, ny + (r.bottom - cy) * k)
            }
            val moved = Geometry.Ink(nx + (ink.left - cx) * k, ny + (ink.top - cy) * k, nx + (ink.right - cx) * k, ny + (ink.bottom - cy) * k)
            return Triple(t, map, moved)
        }

        /**
         * A takeover is its graphic: an empty stage after the graphic leaves reads as dead air, so
         * the last graphic on a stage stays until the cut back (when that is only moments away).
         */
        private fun holdStages(beats: List<Beat>) {
            if (input.footage == null) return
            for (k in scenes.indices.filterNot { i -> overFootage(scenes[i]) }) {
                val last = beats.filter { it.scene == k }.maxByOrNull { it.out } ?: continue
                // A stage that outlives its last card would stand empty: the card stays up to the cut back to the speaker.
                if (sceneEnds[k] - last.out >= 0f) last.out = sceneEnds[k] + 0.05f
            }
        }

        /** What, when and where for one requested beat. */
        // What, when and where for a beat: every option a score may give is one branch.
        @Suppress("CyclomaticComplexMethod")
        private fun resolve(b: BeatScore, k: Int, scene: SceneScore, range: IntRange, id: Int): Beat {
            val recipe = Recipes.named(b.recipe) ?: guess(b).also { notes += "recipe '${b.recipe}' → ${it.name}" }
            val quoted = b.text?.let { locate(it, norm, range) }
            val atIndex = b.at ?: quoted?.first
            val at = snap(b.time ?: atIndex?.let { start(it) } ?: (sceneStarts[k] + 0.2f), recipe, score, notes)
            val lastIndex = b.until ?: quoted?.last ?: atIndex
            val spokenText = if (recipe.kind == Recipe.Kind.Text && atIndex != null) (atIndex..(lastIndex ?: atIndex)).joinToString(" ") { words[it].text } else ""
            val beatText = clean(b.text ?: spokenText)
            val readable = maxOf(MIN_READ, 0.35f + 0.055f * beatText.length, recipe.minHold)
            val out = minOf(maxOf(naturalEnd(b, recipe, at, lastIndex, sceneEnds[k], readable), at + readable), sceneEnds[k] + 0.05f, at + if (recipe.name in LONG_FORM) MAX_THREAD_HOLD else MAX_HOLD)
            val words = beatText.split(' ').count { it.isNotEmpty() }
            val minOut = minOf(at + readable, out)
            val over = overFootage(scene)
            val wanted = layout.zoneOf(b.place, defaultZone(recipe)).let { if (over) layout.zoneOverFootage(it) else it }
            val stage = !over && input.footage != null
            val zone = layout.place(
                wanted, at, out, minOut, id, locked = recipe.name == "lower-third", allowed = if (over) layout.footageZones else Layout.Zone.entries.toSet(),
                // The storyboard's cards follow one another in the band over the head: the next takes the last one's place.
                replace = stage, evict = recipe.name.startsWith("board-"),
            )
            val behind = behind(recipe, over, zone, words, at)
            val slot = behind ?: layout.slot(
                zone, recipe.preferredHeight, aroundSubject = over, stage = stage, inset = stage && scene.inset?.lowercase() in INSETS,
                floor = cutoutFloor?.takeIf { stage && cutout(scene) },
            )
            val cue = Cue(
                // A style that sets Latin headlines in capitals does so here; captions stay as said.
                text = if (recipe.kind == Recipe.Kind.Text) look.cased(beatText) else beatText, at = at, out = out, x = slot.x, y = slot.y, width = slot.w, height = slot.h,
                energy = (b.energy ?: defaultEnergy(recipe)).coerceIn(0f, 1f), emphasis = emphasisOf(beatText, b.emphasis, quoted, this.words),
                wordTimes = spokenTimes(b.text, quoted, words, at),
                value = b.value, from = b.from, prefix = b.prefix ?: "", suffix = b.suffix ?: "", decimals = b.decimals ?: 0,
                points = b.points, icon = b.icon, label = b.label, mark = b.mark, overMedia = over,
                items = b.items,
                itemTimes = b.items.mapIndexed { n, item -> b.itemAt.getOrNull(n)?.takeIf { it >= 0 }?.let(::start) ?: locate(item.removePrefix(">"), norm, range)?.let { start(it.first) } },
                rtl = rtlOf(beatText, b.label), seed = id * 7 + 3,
                media = b.media, aspect = b.aspect ?: 1f, frameWidth = w.toFloat(), frameHeight = h.toFloat(), variety = score.variety,
                head = input.subject?.takeIf { over }?.let { io.trimio.engine.motion.recipe.Box(it.left * w, it.top * h, it.right * w, it.bottom * h) },
            )
            return Beat(id, recipe, treated(recipe, pictured(cue, b), b, id), zone, k, minOut, behind = behind != null, accent = b.accent?.let(::template))
        }

        /**
         * Where a short title goes when it is set behind the speaker's head, the cover-line look: across the frame, its lower
         * half hidden behind the top of the head (the speaker cut out over it, [person]). Only a big title of a few words,
         * over the footage, in the upper band, when the speaker can be cut out; at most one in [BEHIND_EVERY] seconds, so it
         * stays a signature rather than a habit. Null for every other beat.
         */
        private fun behind(recipe: Recipe, over: Boolean, zone: Layout.Zone, words: Int, at: Float): Layout.Slot? {
            val sub = input.subject ?: return null
            val crown = input.crown ?: return null
            val fits = recipe.name in BEHIND_TITLES && words in 1..BEHIND_WORDS && at - lastBehind >= BEHIND_EVERY
            val room = input.matte && over && zone != Layout.Zone.Lower
            if (!fits || !room) return null
            lastBehind = at
            // Where the top of the head is on screen, the footage's zoom about the subject included.
            val zoom = footageZoom().at(at)
            val cy = sub.centerY * h
            val top = cy + (crown * h - cy) * zoom
            val height = h * BEHIND_HEIGHT
            // The line's middle sits a little below the crown, so its foot is behind the head wherever it moves in the shot.
            return Layout.Slot(zone, w / 2f, top + h * BEHIND_SINK, w * BEHIND_WIDTH, height)
        }

        private var lastBehind = -BEHIND_EVERY

        /** The last treatment each recipe got: the same device twice in a row takes another form. */
        private val lastVariant = mutableMapOf<String, Int>()

        /** The beat's treatment ([Variety]): the score's wish, else the edit's variety over what it says. */
        private fun treated(recipe: Recipe, cue: Cue, b: BeatScore, id: Int): Cue {
            if (recipe.variants <= 1) return cue
            val salt = Variety.salt(cue.text, b.label, b.items) * 31 + id
            var v = Variety.pick(recipe, look, cue, score.variety, b.variant, salt)
            if (b.variant == null && score.variety != 0 && lastVariant[recipe.name] == v) v = Variety.next(recipe, look, cue, v)
            lastVariant[recipe.name] = v
            return cue.copy(variant = v)
        }

        /**
         * Words land as spoken only when they are spoken during this beat (a hook that quotes a
         * line said later keeps its own timing).
         */
        private fun spokenTimes(text: String?, quoted: IntRange?, count: Int, at: Float): List<Float>? =
            quoted?.takeIf { q -> q.count() == count && exact(text, q) && start(q.first) in (at - 0.6f)..(at + 1.2f) }?.map { start(it) }

        private fun rtlOf(text: String, label: String?) = isRtl(text.ifBlank { label ?: "" }) || (text.isBlank() && words.any { isRtl(it.text) })

        /** Every word of [text] is said, in order, at [range] (a loose match only places the beat). */
        private fun exact(text: String?, range: IntRange): Boolean {
            val q = text.orEmpty().split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }
            return q.size == range.count() && q.indices.all { j -> norm[range.first + j].trimEnd('.', '،', ',') == q[j].trimEnd('.', '،', ',') }
        }

        /** A designer's template (`template:<id>`) as it was designed: its own colours, or the look's ink when it is a single-colour drawing. */
        private fun template(visual: String): io.trimio.engine.motion.visual.VectorIcon? {
            if (!visual.startsWith(TEMPLATE)) return null
            val icon = visuals.named("template", visual.removePrefix(TEMPLATE)) ?: return null
            val art = icon.motion ?: return null
            return icon.copy(motion = art.styled(if (art.colourful) io.trimio.engine.motion.art.Colouring.Own else io.trimio.engine.motion.art.Colouring.Tint))
        }

        /** Brand marks and pictures from the visual vocabulary for what the beat names. */
        private fun pictured(cue: Cue, b: BeatScore) = cue.copy(
            marks = b.items.map { brands.mark(it) }.ifEmpty { listOfNotNull(b.brand?.let { brands.find(it) }) },
            // Animated pictures take the look's colours and face the reading direction; stills are unchanged.
            visual = b.visual?.let { v -> template(v) ?: visuals.find(v)?.let { io.trimio.engine.motion.art.AnimatedLayers.styled(it, look, cue.rtl, cue.overMedia) } },
            itemVisuals = b.items.indices.map { i -> visuals.find(b.visuals.getOrNull(i) ?: b.items[i])?.let { io.trimio.engine.motion.art.AnimatedLayers.styled(it, look, cue.rtl, cue.overMedia) } },
        )

        /** Elements not tied to words stay for the scene; spoken text stays while it is said. */
        private fun naturalEnd(b: BeatScore, recipe: Recipe, at: Float, lastIndex: Int?, sceneEnd: Float, readable: Float): Float {
            val spokenEnd = lastIndex?.let { end(it) + LINGER }
            // A thread's hold is its least: its messages land as they are said, so it stays until the last one is.
            b.hold?.let { return if (recipe.name in LONG_FORM && spokenEnd != null) maxOf(at + it, spokenEnd) else at + it }
            val tied = recipe.kind == Recipe.Kind.Text || b.text != null || b.until != null
            return when {
                spokenEnd != null && tied -> spokenEnd
                recipe.kind == Recipe.Kind.Element -> sceneEnd
                else -> at + readable
            }
        }

        /**
         * The look's lens and the distortion of "glitch" and "ripple" cuts. They bend the picture
         * only (footage, stages): text is drawn over it sharp, since in Persian a softened dot is
         * another letter.
         */
        private val pictureFilters: List<Filter> by lazy {
            listOfNotNull(Stages.lens(look)) + Stages.cuts(scenes.indices.mapNotNull { k -> entering(k)?.let { it to sceneStarts[k] } })
        }

        /** Picture layers [nodes] inside the picture filters (nested, one off-screen each). */
        private fun picture(nodes: List<Node>, name: String): List<Node> = if (nodes.isEmpty() || pictureFilters.isEmpty()) nodes else listOf(
            pictureFilters.fold(Group(nodes, name = name)) { inner, f -> Group(listOf(inner), name = "$name-${f.family.id}", filter = f) },
        )

        /** A scene: its background from the cut, its beats, and its transition moves. */
        private fun sceneGroup(k: Int, built: Map<Beat, Built>, transitions: MutableList<Node>, sfx: MutableList<Sfx>): Group {
            val scene = scenes[k]
            val s = sceneStarts[k]
            val e = sceneEnds[k]
            val bg = mutableListOf<Node>()
            // Backgrounds exist only from the cut: before it, the previous scene is still on screen.
            when (sceneBg(scene, input)) {
                // Over footage a graphic scene needs its own stage; without footage the base already is one.
                "aurora" -> if (input.footage != null) bg += EffectNode(Stages.background(look, stage(look), k + 1), start = s)
                "grid" -> {
                    bg += EffectNode(Stages.background(look, stage(look), k + 1), start = s)
                    bg += EffectNode(Effect.Grid(w / 9f, look.ink, 0.05f.anim), start = s)
                }
                "plain" -> bg += EffectNode(Effect.Flash(look.canvas, Anim.One), start = s)
            }
            val nodes = picture(bg, "stage-$k").toMutableList()
            nodes += if (cutout(scene)) cutout(k, s, e) else inset(k, s, e)
            built.filterKeys { it.scene == k }.values.forEach { nodes += it.nodes }
            val enter = entering(k)
            val (tx, sc, op) = sceneMotion(enter, entering(k + 1), s, e, w.toFloat(), look.settle)
            transitionOverlay(enter, s, look, transitions, sfx, w.toFloat(), h.toFloat())
            val t = Transform(anchorX = 0.5f, anchorY = 0.5f, x = (w / 2f).anim + tx, y = (h / 2f).anim, scale = sc, opacity = op)
            // A scene stays a moment past its cut for the next one to come in over it; a stage cut back to the speaker
            // ends on the cut, or it stands empty over the footage once its cards and card of the speaker have gone.
            val back = input.footage != null && !overFootage(scene) && scenes.getOrNull(k + 1)?.let(::overFootage) == true
            return Group(nodes, transform = t, start = s - 0.6f, end = if (back) e else e + 0.3f, name = "scene-$k")
        }

        /**
         * Whether [scene] keeps the speaker on its stage cut out of the picture rather than in a card: a stage that would hold
         * their card, when the renderer can cut them out and where their head is known ([Input.crown]).
         */
        private fun cutout(scene: SceneScore) =
            cutoutFloor != null && scene.inset?.lowercase() in INSETS && sceneBg(scene, input) != "media"

        /** How far down the frame a cut-out speaker's head starts (a share of its height), less a margin; null without a matte. */
        private val cutoutFloor: Float? = input.crown?.takeIf { input.matte && input.footage != null && h > w }
            ?.let { c -> 1f - (1f - c) * CUTOUT_SCALE - CUTOUT_MARGIN }

        /**
         * The speaker on a stage of their own ([cutout]): cut out of the footage, the room gone, they settle back to the foot of
         * the frame as the stage opens (from the whole picture to [CUTOUT_SCALE] of it, about the bottom edge), the graphic
         * taking the room above their head; they grow back as it ends, unless the next scene keeps them too.
         */
        private fun cutout(k: Int, s: Float, e: Float): List<Node> {
            val footage = input.footage ?: return emptyList()
            fun kept(j: Int) = scenes.getOrNull(j)?.let { cutout(it) } == true
            val opens = !kept(k - 1)
            val closes = !kept(k + 1)
            val scale = anim(if (opens) 1f else CUTOUT_SCALE, s) {
                to(CUTOUT_SCALE, s + if (opens) INSET_IN else 0f, Easing.ExpoOut)
                if (closes) {
                    to(CUTOUT_SCALE, e - INSET_OUT, Easing.Linear)
                    to(1f, e, Easing.ExpoIn)
                }
            }
            val segments = input.edit?.played ?: listOf(EditPlan.Segment(0f, duration, 0f))
            val opening = input.edit?.opening?.size ?: 0
            return segments.withIndex().filter { (i, seg) -> i >= opening && seg.outEnd > s - SCENE_PAD && seg.outStart < e + SCENE_PAD }.map { (i, seg) ->
                MediaNode(
                    footage, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = seg.sourceStart, grade = gradeOf(look),
                    transform = Transform(x = (w / 2f).anim, y = h.toFloat().anim, anchorX = 0.5f, anchorY = 1f, scale = scale),
                    start = seg.outStart, end = seg.outEnd, name = "cutout-$i", matte = true,
                )
            }.let { clips -> listOf(Group(clips, start = s - SCENE_PAD, end = e, name = "cutout")) }
        }

        /**
         * The speaker kept on a stage of its own ([SceneScore.inset]): the footage, cut as everywhere else, shrinks
         * from the whole frame into a rounded card around the head and shoulders as the stage opens, and grows back as
         * it ends, unless the scene before or after keeps the card too (then it simply stays).
         */
        private fun inset(k: Int, s: Float, e: Float): List<Node> {
            val scene = scenes[k]
            val place = scene.inset?.lowercase()?.takeIf { it in INSETS } ?: return emptyList()
            val footage = input.footage ?: return emptyList()
            if (sceneBg(scene, input) == "media") return emptyList()
            fun kept(j: Int) = scenes.getOrNull(j)?.let { it.inset?.lowercase() == place && sceneBg(it, input) != "media" } == true
            val fw = w.toFloat()
            val fh = h.toFloat()
            val sub = input.subject ?: Subject(0.3f, 0.3f, 0.7f, 0.6f)
            // Head and shoulders: the subject's width with room either side, a card a little taller than wide.
            val rw = ((sub.right - sub.left) * fw * INSET_ROOM).coerceIn(fw * INSET_MIN, fw)
            val rh = (rw * INSET_ASPECT).coerceAtMost(fh)
            val rx = (sub.centerX * fw - rw / 2f).coerceIn(0f, fw - rw)
            val ry = (sub.top * fh - rh * INSET_HEADROOM).coerceIn(0f, fh - rh)
            val cw = fw * if (place == "top") INSET_TOP else INSET_CORNER
            val sc = cw / rw
            val cx = if (place == "top") (fw - cw) / 2f else fw * INSET_MARGIN
            val cy = fh * INSET_MARGIN
            val opens = !kept(k - 1)
            val closes = !kept(k + 1)
            // Every property eases between the whole frame and the card on the same curve, so the card's edge, its rim and
            // the picture inside it move as one.
            fun move(whole: Float, card: Float): Anim = anim(if (opens) whole else card, s) {
                to(card, s + if (opens) INSET_IN else 0f, Easing.ExpoOut)
                if (closes) {
                    to(card, e - INSET_OUT, Easing.Linear)
                    to(whole, e, Easing.ExpoIn)
                }
            }
            val transform = Transform(anchorX = 0f, anchorY = 0f, x = move(0f, cx - sc * rx), y = move(0f, cy - sc * ry), scale = move(1f, sc))
            val radius = rw * INSET_RADIUS
            val rim = rw * INSET_RIM
            fun card(grow: Float) = Transform(x = move(fw / 2f, rx + rw / 2f), y = move(fh / 2f, ry + rh / 2f)) to
                ShapeSpec.Rect(move(fw + grow, rw + grow), move(fh + grow, rh + grow), move(0f, radius + grow / 2f))
            val (maskAt, maskShape) = card(0f)
            val (rimAt, rimShape) = card(rim * 2f)
            val segments = input.edit?.played ?: listOf(EditPlan.Segment(0f, duration, 0f))
            val clips = segments.filter { it.outEnd > s - SCENE_PAD && it.outStart < e + SCENE_PAD }.map { seg ->
                MediaNode(
                    footage, fw, fh, Fit.Cover, sourceOffset = seg.sourceStart, grade = gradeOf(look),
                    transform = Transform(x = (fw / 2f).anim, y = (fh / 2f).anim), start = seg.outStart, end = seg.outEnd, name = "inset-clip",
                )
            }
            val shadow = Shadow(Color.Black.copy(alpha = 0.5f), 0f, rh * 0.03f, rh * 0.1f)
            return listOf(
                Group(
                    listOf(ShapeNode(rimShape, fill = look.ink.copy(alpha = 0.9f).fill(), shadow = shadow, transform = rimAt)),
                    transform = transform, start = s - SCENE_PAD, end = e, name = "inset-rim",
                ),
                Group(clips, transform = transform, start = s - SCENE_PAD, end = e, mask = Mask(ShapeNode(maskShape, fill = Color.White.fill(), transform = maskAt)), name = "inset"),
            )
        }

        /**
         * Footage, cut to the edit, graded, with scrims so text never depends on the picture, and
         * punched in on the speaker scene by scene (jump-cut rhythm); or a living gradient.
         */
        /**
         * The speaker alone from [from] to [to]: the footage through its person matte, laid exactly over the footage below
         * (the same cut, the same zoom), so whatever was drawn between the two stands behind them.
         */
        private fun person(from: Float, to: Float): List<Node> {
            val footage = input.footage ?: return emptyList()
            val zoom = footageZoom()
            val sub = input.subject ?: return emptyList()
            val segments = input.edit?.played ?: listOf(EditPlan.Segment(0f, duration, 0f))
            val opening = input.edit?.opening?.size ?: 0
            return segments.withIndex().filter { (i, seg) -> i >= opening && seg.outEnd > from && seg.outStart < to }.map { (i, seg) ->
                val start = maxOf(seg.outStart, from)
                MediaNode(
                    footage, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = seg.sourceStart + (start - seg.outStart), grade = gradeOf(look),
                    transform = Transform(x = (w * sub.centerX).anim, y = (h * sub.centerY).anim, anchorX = sub.centerX, anchorY = sub.centerY, scale = zoom),
                    start = start, end = minOf(seg.outEnd, to), name = "person-$i", matte = true,
                )
            }
        }

        private fun base(): List<Node> = if (input.footage != null) {
            val zoom = footageZoom()
            val sub = input.subject ?: Subject(0.3f, 0.3f, 0.7f, 0.6f)
            val segments = input.edit?.played ?: listOf(EditPlan.Segment(0f, duration, 0f))
            val opening = input.edit?.opening?.size ?: 0
            fun place(scale: Anim, blur: Anim = Anim.Zero) =
                Transform(x = (w * sub.centerX).anim, y = (h * sub.centerY).anim, anchorX = sub.centerX, anchorY = sub.centerY, scale = scale, blur = blur)
            // The cold open's last frames are pulled into the restart (a push and a smear into the white).
            val pull = restart?.let { r -> Anim.tween(0f, REWIND_PUSH, r - REWIND_IN, r, Easing.ExpoIn) } ?: Anim.Zero
            val smear = restart?.let { r -> Anim.tween(0f, REWIND_BLUR, r - REWIND_IN * 0.7f, r, Easing.ExpoIn) } ?: Anim.Zero
            segments.mapIndexed { i, seg ->
                val cold = i < opening
                MediaNode(
                    input.footage, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = seg.sourceStart, grade = gradeOf(look),
                    transform = if (cold) place(zoom + pull, smear) else place(zoom),
                    start = seg.outStart, end = if (i == segments.lastIndex) heldFrom ?: Float.POSITIVE_INFINITY else seg.outEnd, name = "footage-$i",
                )
            } + listOfNotNull(restart?.let { r -> settle(r, segments.getOrNull(opening), ::place, zoom) }, held(place(zoom))) + listOf(
                EffectNode(Effect.Scrim(fromBottom = true, coverage = 0.4f, opacity = 0.5f.anim)),
                EffectNode(Effect.Scrim(fromBottom = false, coverage = 0.36f, opacity = 0.42f.anim)),
            )
        } else {
            listOf(EffectNode(Stages.background(look, look.aurora, 1)))
        }

        /** Output time an end hold freezes the picture (the source has run out), or null. */
        private val heldFrom: Float? = input.edit?.let { e -> e.outro?.takeIf { it.running < it.length }?.let { e.bodyEnd + it.running } }

        /** An end hold's frozen last frame of the source, to the end. */
        private fun held(place: Transform): Node? {
            val from = heldFrom ?: return null
            val frame = (input.edit?.outro?.heldFrame ?: return null) - 1f / input.fps
            return MediaNode(
                input.footage ?: return null, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = frame.coerceAtLeast(0f), speed = 0f,
                grade = gradeOf(look), transform = place, start = from, name = "footage-hold",
            )
        }

        /**
         * The rewind's landing: the body's first frame, held through the restart's transition, zooms
         * back from a step too close (motion-blurred: the group moves) as the white clears, so the
         * first word starts on a settled frame. Nothing to hold when the body follows at once.
         */
        private fun settle(r: Float, body: EditPlan.Segment?, place: (Anim, Anim) -> Transform, zoom: Anim): Node? {
            val first = body?.takeIf { it.outStart - r > 0.5f / input.fps } ?: return null
            val hold = MediaNode(
                input.footage ?: return null, w.toFloat(), h.toFloat(), Fit.Cover, sourceOffset = first.sourceStart, speed = 0f, grade = gradeOf(look),
                transform = place(zoom, Anim.Zero), start = r, end = first.outStart, name = "footage-restart",
            )
            val scale = Anim.tween(REWIND_BACK, 1f, r, first.outStart, Easing.ExpoOut)
            val t = Transform(anchorX = 0.5f, anchorY = 0.5f, x = (w / 2f).anim, y = (h / 2f).anim, scale = scale)
            return Group(listOf(hold), transform = t, start = r, end = first.outStart, name = "rewind")
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
            val base = if (keys.isEmpty()) Anim.One else Anim.keys(keys)
            return pulses().fold(base) { acc, p -> acc + p }
        }

        /**
         * Punches on the words the speaker leans on: a quick 3.5% push that settles to 1% — the
         * editor's way of underlining speech. At most one every 2 s, only over footage.
         */
        private fun pulses(): List<Anim> {
            val out = mutableListOf<Anim>()
            var last = -10f
            for (w in words) {
                val t = w.range.startMs / 1000f
                val k = sceneStarts.indexOfLast { it <= t }.coerceAtLeast(0)
                val ok = w.emphasis >= PULSE_STRESS && t - last >= PULSE_GAP && overFootage(scenes[k]) && t - sceneStarts[k] > 0.3f
                if (!ok) continue
                out += io.trimio.engine.motion.anim(0f, t - 0.04f) { by(0.035f, 0.12f, Easing.ExpoOut); by(0.01f, 0.7f, Easing.SineInOut); hold(sceneEnds[k] - 0.02f); by(0f, 0.01f) }
                last = t
            }
            return out
        }
    }

    // ------------------------------------------------------------------ captions

    private fun captions(session: Session, beats: List<Beat>): List<Beat> {
        val score = session.score
        val layout = session.layout
        val input = session.input
        val recipe = Recipes.named(score.captions.recipe) ?: TextRecipes.PopCaptions
        val zone = layout.zoneOf(score.captions.place, Layout.Zone.Lower)
        // The cold open speaks too: its words get captions on the output clock before the body's.
        val prelude = input.transcript?.let { t -> input.edit?.preludeWords(t) }.orEmpty()
        // Hesitations («اِم», "um") are never shown; the words around them keep their own times.
        // A blank word was cut or merged by the creator (the editor): it has nothing to show either.
        fun said(words: List<Word>) = words.filterNot { it.text.isBlank() || Words.isFiller(it.text) }
        // The storyboard's captions are phrases: a group never runs across a comma into the next thought.
        val phrases = score.captions.mark in PHRASE_MARKS
        val most = score.captions.maxWords.coerceIn(1, MAX_CAPTION_WORDS)
        val lines = (if (prelude.isEmpty()) emptyList() else Words.captionLines(said(prelude), most, commas = phrases)) +
            Words.captionLines(said(session.transcript!!.words), most, commas = phrases)
        val emphasisWords = beats.flatMap { b -> b.cue.emphasis.mapNotNull { b.cue.words.getOrNull(it) } }.map(NumberWords::normalize).toSet()
        val out = mutableListOf<Beat>()
        var skipped = 0
        for ((i, line) in lines.withIndex()) {
            val at = line.first().range.startMs / 1000f
            val next = lines.getOrNull(i + 1)?.first()?.range?.startMs?.div(1000f) ?: Float.MAX_VALUE
            val natural = minOf(line.last().range.endMs / 1000f + 0.35f, next - 0.03f)
            // A caption ends at the cut to a full-frame stage: it never runs on under the stage's graphic.
            val stage = session.sceneStarts.indices.firstOrNull { k -> session.sceneStarts[k] in (at + MIN_CAPTION)..natural && !session.overFootage(session.scenes[k]) }
            val end = stage?.let { session.sceneStarts[it] - 0.02f } ?: natural
            // A title up says the words itself; in the storyboard a title over the head leaves the captions their band.
            val headline = beats.any { b -> b.recipe.kind == Recipe.Kind.Text && (!phrases || b.zone == zone) && overlap(b.cue.at, b.out, at, end) > (end - at) * 0.35f }
            // A graphic that really rests over the caption's band (whatever band it was given) is up: the caption yields.
            val band = layout.slot(zone, recipe.preferredHeight)
            val covered = beats.any { b ->
                val r = session.rests[b.id]
                overlap(b.cue.at, b.out, at, end) > MIN_CAPTION && r != null && r.width < w(session) * FULL_WIDTH && r.bottom > band.y - band.h / 2f && r.top < band.y + band.h / 2f
            }
            // A graphic leaving the zone is still on screen through its exit: give it that time.
            if (headline || covered || layout.busy(zone, at - EXIT_TAIL, end)) { skipped++; continue }
            val lineWords = line.map { it.text }
            // One highlight per line: the strongest word (prosody, a number, or the director's pick).
            // A verb is never the line's word (what is said is in its nouns, numbers, gains and losses).
            val strongest = line.indices.filter { Words.isContent(lineWords[it]) && !verbal(lineWords[it]) }.maxByOrNull { j ->
                val n = NumberWords.normalize(lineWords[j])
                line[j].emphasis + (if (n in emphasisWords) 1f else 0f) + (if (NumberWords.at(lineWords, j) != null) 0.5f else 0f) +
                    (if (io.trimio.engine.motion.recipe.Meaning.tone(lineWords[j]).let { it == io.trimio.engine.motion.recipe.Meaning.Tone.Gain || it == io.trimio.engine.motion.recipe.Meaning.Tone.Loss }) TONE_WORD else 0f)
            }
            val strong = strongest?.takeIf { j ->
                stands(line[j].emphasis, NumberWords.normalize(lineWords[j]) in emphasisWords, NumberWords.at(lineWords, j)) ||
                    io.trimio.engine.motion.recipe.Meaning.tone(lineWords[j]).let { it == io.trimio.engine.motion.recipe.Meaning.Tone.Gain || it == io.trimio.engine.motion.recipe.Meaning.Tone.Loss }
            }
            val k = session.sceneStarts.indexOfLast { it <= at }.coerceAtLeast(0)
            val over = session.overFootage(session.scenes[k])
            val slot = layout.slot(zone, recipe.preferredHeight, aroundSubject = over)
            val cue = Cue(
                text = clean(lineWords.joinToString(" ")), at = at, out = end, x = slot.x, y = slot.y, width = slot.w, height = slot.h,
                energy = 0.5f, emphasis = setOfNotNull(strong), wordTimes = line.map { it.range.startMs / 1000f },
                overMedia = over, rtl = lineWords.any(::isRtl), seed = i, variety = score.variety, mark = score.captions.mark,
                speaker = speakerOf(input.voices, line),
            )
            out += Beat(CAPTION_ID_BASE + i, recipe, cue, zone, -1, end)
        }
        if (skipped > 0) session.notes += "captions: $skipped lines yield to headlines or busy zones"
        return out
    }

    // ------------------------------------------------------------------ helpers

    /** The voice saying most of [line] (its middle on the source clock); 0 with one voice. */
    private fun speakerOf(voices: List<Voice>, line: List<Word>): Int {
        if (voices.isEmpty()) return 0
        val mid = (line.first().range.startMs + line.last().range.endMs) / 2000f
        return voices.firstOrNull { mid >= it.from && mid < it.to }?.speaker
            ?: voices.minByOrNull { minOf(kotlin.math.abs(it.from - mid), kotlin.math.abs(it.to - mid)) }?.speaker ?: 0
    }

    private fun w(session: Session) = session.layout.width.toFloat()

    /** Gives every piece of text in [node] that is not on a body of its own the look's [Guard]. */
    private fun guard(node: Node, g: io.trimio.engine.motion.Guard) {
        when (node) {
            is Group -> node.children.forEach { guard(it, g) }
            is io.trimio.engine.motion.TextNode -> if (!node.onBody) node.guard = g
            is io.trimio.engine.motion.CounterNode -> if (!node.onBody) node.guard = g
            else -> Unit
        }
    }

    /** Whether a caption word earns the line's one highlight. */
    /** A Persian verb as said: the continuous «می‌…», its negative «نمی‌…», or the copula's short forms. */
    private fun verbal(word: String): Boolean {
        val w = word.trimEnd('،', ',', '.', '!', '?', '؟').replace("\u200C", "")
        return w.startsWith("می") && w.length > 3 && w !in NOT_VERBS || w.startsWith("نمی") || w in COPULAS
    }

    private val NOT_VERBS = setOf("میلیون", "میلیارد", "میز", "میوه", "میانگین", "میزان", "میدان", "میدونی")
    private val COPULAS = setOf("است", "هست", "بود", "شد", "هستن", "بودن", "شده", "کرد", "کردم", "کردن")

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

    /**
     * A full-frame stage for graphic scenes: the look's canvas lit by soft pools of its accent and
     * heat colours, so the graphic sits in light instead of on a dark smudge.
     */
    private fun stage(look: Look): List<Color> {
        fun mix(c: Color, t: Float) = Color(
            look.canvas.red + (c.red - look.canvas.red) * t, look.canvas.green + (c.green - look.canvas.green) * t,
            look.canvas.blue + (c.blue - look.canvas.blue) * t, 1f,
        )
        val dark = look.canvas.luminance() < 0.5f
        return listOf(look.canvas, mix(look.accent, if (dark) 0.2f else 0.35f), mix(look.hot, if (dark) 0.16f else 0.22f), mix(look.positive, if (dark) 0.1f else 0.15f))
    }

    private fun gradeOf(look: Look) = look.grade ?: when (look.name) {
        "paper" -> Grade(contrast = 1.04f.anim, saturation = 0.85f.anim)
        "lumen" -> Grade(contrast = 1.06f.anim, saturation = 0.95f.anim, temperature = (-0.4f).anim)
        else -> Grade(exposure = 0.04f.anim, contrast = 1.06f.anim, saturation = 1.0f.anim, temperature = 0.15f.anim)
    }

    /** Slide/scale/opacity of a scene group for its incoming and outgoing transitions. */
    private fun sceneMotion(enter: String?, exit: String?, s: Float, e: Float, w: Float, settle: Easing): Triple<Anim, Anim, Anim> {
        val xKeys = mutableListOf(Anim.Key(s - 1f, 0f, Easing.Hold))
        val scaleKeys = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        val opKeys = mutableListOf(Anim.Key(s - 1f, 1f, Easing.Hold))
        when (enter) {
            "whip" -> { xKeys += Anim.Key(s, w * 1.1f, Easing.Hold); xKeys += Anim.Key(s + 0.24f, 0f, settle) }
            "zoom" -> {
                scaleKeys += Anim.Key(s, 0.82f, Easing.Hold); scaleKeys += Anim.Key(s + 0.4f, 1f, settle)
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

    /**
     * The cold open's restart, an editor's rewind rather than a cut: the last frames of the
     * flash-forward push in and smear (see base) into a quick white dip that peaks on the cut and
     * clears as the body's first frame settles; a reverse swell rises into the cut and lands with
     * a soft hit. The flash-forward itself sits under a deeper vignette that releases here, so the
     * opening reads as "later", the body as "now".
     */
    private fun rewind(r: Float, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        val dip = anim(0f, r - REWIND_DIP) { by(REWIND_WHITE, REWIND_DIP, Easing.ExpoIn); by(0f, REWIND_CLEAR, Easing.ExpoOut) }
        overlays += EffectNode(Effect.Flash(Color.White, dip), start = r - REWIND_DIP, end = r + REWIND_CLEAR, name = "rewind-dip")
        val frame = anim(FLASH_FORWARD_VIGNETTE, 0f) { hold(r - REWIND_IN); by(FLASH_FORWARD_VIGNETTE + 0.12f, REWIND_IN, Easing.ExpoIn); by(0f, VIGNETTE_RELEASE, Easing.SineInOut) }
        overlays += EffectNode(Effect.Vignette(frame), start = 0f, end = r + VIGNETTE_RELEASE, name = "flash-forward")
        sfx += Sfx(r - REWIND_SWELL, SfxKind.Rewind, 1f)
        sfx += Sfx(r, SfxKind.Hit, 0.5f)
    }

    private fun transitionOverlay(kind: String?, s: Float, look: Look, overlays: MutableList<Node>, sfx: MutableList<Sfx>, width: Float, height: Float) {
        when (kind) {
            "whip" -> sfx += Sfx(s - 0.12f, SfxKind.Whoosh, 0.9f)
            "zoom" -> sfx += Sfx(s - 0.15f, SfxKind.Whoosh, 0.8f)
            "flash" -> {
                val flash = io.trimio.engine.motion.anim(0f, s - 0.001f) { by(0.85f, 0.001f, Easing.Hold); by(0f, 0.22f, Easing.ExpoOut) }
                overlays += EffectNode(Effect.Flash(Color.White, flash), start = s - 0.01f, end = s + 0.25f)
                sfx += Sfx(s, SfxKind.Hit, 0.9f)
            }
            "glitch" -> sfx += Sfx(s - 0.05f, SfxKind.Swish, 0.8f)
            "ripple" -> sfx += Sfx(s - 0.2f, SfxKind.Shimmer, 0.7f)
            "leak" -> {
                val strength = io.trimio.engine.motion.anim(0f, s - 0.35f) { by(0.85f, 0.35f, Easing.SineInOut); by(0f, 0.8f, Easing.SineInOut) }
                overlays += EffectNode(Effect.LightLeak(look.hot, Anim.tween(0f, 1f, s - 0.35f, s + 0.8f, Easing.SineInOut), strength), start = s - 0.4f, end = s + 0.9f)
                sfx += Sfx(s - 0.3f, SfxKind.Shimmer, 0.7f)
            }
            // Film burn, glitch slices, a dip, a cool leak: drawn by the recipes' own cuts.
            else -> io.trimio.engine.motion.recipe.Transitions.overlay(kind, s, look, width, height, overlays, sfx)
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

    private fun mixSfx(all: List<Sfx>, duration: Float): List<Sfx> {
        val out = mutableListOf<Sfx>()
        for (s in all.sortedWith(compareBy({ it.at }, { -it.gain }))) {
            val prev = out.lastOrNull()
            when {
                prev == null || s.at - prev.at >= MIN_SFX_GAP -> out += s
                s.gain > prev.gain -> out[out.size - 1] = s
            }
        }
        return budget(thin(out), duration)
    }

    /**
     * At most [MAX_SFX_PER_MINUTE] cues a minute over the piece (a short piece counts as a quarter
     * of a minute): past that, the quietest go first. Sound stays an accent.
     */
    private fun budget(cues: List<Sfx>, duration: Float): List<Sfx> {
        val allowed = (MAX_SFX_PER_MINUTE * maxOf(duration / 60f, MIN_SFX_MINUTES)).toInt()
        if (cues.size <= allowed) return cues
        val kept = cues.sortedByDescending { it.gain }.take(allowed).toSet()
        return cues.filter { it in kept }
    }

    /**
     * At most [MAX_SFX_BURST] cues in any two seconds: a dense passage keeps its strongest hits
     * (a mix where every beat makes a sound stops meaning anything).
     */
    private fun thin(cues: List<Sfx>): List<Sfx> {
        val kept = cues.toMutableList()
        var i = 0
        while (i < kept.size) {
            val window = kept.filter { it.at >= kept[i].at && it.at < kept[i].at + SFX_WINDOW }
            if (window.size > MAX_SFX_BURST) kept.remove(window.minBy { it.gain }) else i++
        }
        return kept
    }

    private object Craft {
        const val SCENE_LEAD = 0.12f
    }

    companion object {
        /** A beat's visual naming a designer's template rather than a picture. */
        const val TEMPLATE = "template:"

        /** Seconds a graphic must be on screen to be shown at all. */
        private const val MIN_SHOWN = 0.4f

        /** The storyboard's caption styles: each runs in phrases that never cross a comma. */
        private val PHRASE_MARKS = setOf("meaning", "pop", "highlight")

        /** What a word of gain or loss adds to its claim on a caption's highlight. */
        private const val TONE_WORD = 0.7f
        private const val FIT_STEPS = 28

        // The speaker's card on a stage of its own (SceneScore.inset).
        private val INSETS = setOf("corner", "top")

        /** A cut-out speaker on a stage: their size there (about the bottom edge), and the room kept above their head. */
        private const val CUTOUT_SCALE = 0.8f
        private const val CUTOUT_MARGIN = 0.03f

        /** Titles that may stand behind the speaker, the longest they may be, and how often. */
        private val BEHIND_TITLES = setOf("slam", "stack", "spread", "mask-rise", "blur-in", "flip")
        private const val BEHIND_WORDS = 3
        private const val BEHIND_EVERY = 8f

        /** A title behind the head: its band's height and width in the frame, and how far below the crown its middle sits. */
        private const val BEHIND_HEIGHT = 0.16f
        private const val BEHIND_WIDTH = 0.94f
        private const val BEHIND_SINK = 0.045f

        /** The speaker stands in front from a moment before the title behind them starts its entrance. */
        private const val PERSON_LEAD = 0.15f
        private const val INSET_ROOM = 1.5f
        private const val INSET_MIN = 0.32f
        private const val INSET_ASPECT = 1.15f
        private const val INSET_HEADROOM = 0.18f
        private const val INSET_CORNER = 0.25f
        private const val INSET_TOP = 0.3f
        private const val INSET_MARGIN = 0.05f
        private const val INSET_IN = 0.5f
        private const val INSET_OUT = 0.4f
        private const val INSET_RADIUS = 0.1f
        private const val INSET_RIM = 0.012f
        private const val SCENE_PAD = 0.6f

        /** Words per caption line at most: a reel shows a few, long-form subtitles a sentence. */
        private const val MAX_CAPTION_WORDS = 12

        /** Where a graphic rests: from this long after it lands to this long before it leaves. */
        private const val REST_ENTRANCE = 0.8f
        private const val REST_EXIT = 0.35f
        /** The safe area, as shares of the frame: the status bar, the caption and handle, the side margins, the buttons. */
        private const val SAFE_TOP = 0.05f
        private const val SAFE_BOTTOM_TALL = 0.88f
        private const val SAFE_BOTTOM = 0.95f
        private const val SAFE_SIDE = 0.04f
        private const val SAFE_BUTTONS = 0.9f
        private const val BUTTONS_FROM = 0.55f
        private const val FULL = 0.97f
        private const val STAGE_FILL = 0.92f
        private const val STAGE_GROW = 2.2f
        private const val STAGE_MIN_GROW = 1.12f
        /** A graphic as wide as the frame is a stage, not something over the caption band. */
        private const val FULL_WIDTH = 0.97f
        private const val NUDGE_TOL = 3f
        /** Kept off the face: more than this share of it covered is a graphic over the face; the gap kept from it, px. */
        private const val FACE_SHARE = 0.06f
        private const val FACE_GAP = 12f
        /** A graphic is shrunk to fit at most this much; smaller would be unreadable (the jury moves it instead). */
        private const val MIN_FIT = 0.5f
        private const val REST_MEMO = 512
        private const val SHRINK_TOL = 0.01f
        private const val FOOTAGE_TEXTURE = 0.35f
        private const val MIN_READ = 1.0f
        private const val MAX_HOLD = 7f
        private const val MAX_SFX_BURST = 4
        /** The mix's accent budget (the sound judge's): cues a minute, a short piece counted as this much of a minute. */
        private const val MAX_SFX_PER_MINUTE = 45
        private const val MIN_SFX_MINUTES = 0.25f
        private const val SFX_WINDOW = 2f
        /** A chat thread plays as long as the story it tells; the call to action holds to the end. */
        private const val MAX_THREAD_HOLD = 24f
        /** Devices that hold across lines: a thread, the ask, and the storyboard's cards that change state as the talk goes on. */
        private val LONG_FORM = setOf("message", "comment", "endcard", "board-tiles", "board-versus", "board-chat", "board-steps", "board-chips")
        private const val LINGER = 0.55f
        private const val EXIT_TAIL = 0.4f
        private const val STAGE_HOLD = 3.5f
        private const val MIN_SCENE = 1.2f
        private const val TAIL = 0.8f
        private const val SNAP_WINDOW = 0.1f
        private const val MIN_SFX_GAP = 0.12f
        private const val CAPTION_ID_BASE = 100_000
        /** A caption cut short by a stage still shows this long (a shorter one is not clamped). */
        private const val MIN_CAPTION = 0.3f
        private const val PULSE_STRESS = 0.72f
        private const val PULSE_GAP = 2f
        /** A scene starting this close to a cold open's restart takes the restart as its transition. */
        private const val RESTART_WINDOW = 0.6f
        /** Rewind: the cold open's push into the cut (scale added, over [REWIND_IN] s) and its smear (px). */
        private const val REWIND_IN = 0.12f
        private const val REWIND_PUSH = 0.06f
        private const val REWIND_BLUR = 9f
        /** Rewind: the body's held first frame starts this much too close and settles to 1. */
        private const val REWIND_BACK = 1.09f
        /** Rewind: the white dip rises over [REWIND_DIP] s to [REWIND_WHITE] and clears over [REWIND_CLEAR] s. */
        private const val REWIND_DIP = 0.07f
        private const val REWIND_WHITE = 0.8f
        private const val REWIND_CLEAR = 0.24f
        /** Rewind: the reverse swell's rise before its peak on the cut (ProceduralSfx "rewind"). */
        private const val REWIND_SWELL = 0.36f
        /** The flash-forward's extra vignette, released over [VIGNETTE_RELEASE] s after the restart. */
        private const val FLASH_FORWARD_VIGNETTE = 0.22f
        private const val VIGNETTE_RELEASE = 0.45f

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
