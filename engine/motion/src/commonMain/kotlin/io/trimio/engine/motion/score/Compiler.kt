package io.trimio.engine.motion.score

import io.trimio.engine.motion.art.styled
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Camera
import io.trimio.engine.motion.CaptionMetrics
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.CraftGate
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Filter
import io.trimio.engine.motion.FinishingPass
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
import io.trimio.engine.motion.TextNormalize
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.TransitionShaders
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

class Compiler(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary = BrandLibrary.Empty,
    private val visuals: io.trimio.engine.motion.visual.VisualLibrary = io.trimio.engine.motion.visual.VisualLibrary.Empty,
    private val inside: Boolean = true,
) {
    private val geometry by lazy { Geometry(text.measurer, text.fonts) }

    private data class Rest(val recipe: String, val cue: Cue, val look: String, val w: Int, val h: Int)

    private class Lens(val zoom: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {
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

    private val restMemo = LinkedHashMap<Rest, Pair<Geometry.Ink?, Geometry.Ink?>>()

    data class Input(
        val score: Score,
        val transcript: Transcript?,
        val duration: Float? = null,
        val footage: String? = null,
        val fps: Int = 30,
        val edit: EditPlan? = null,
        val subject: Subject? = null,
        val voices: List<Voice> = emptyList(),
        val matte: Boolean = false,
        val crown: Float? = null,
    )

    data class Placed(
        val recipe: String, val zone: String, val at: Float, val out: Float, val text: String,
        val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f,
        val overFootage: Boolean = false,
        val variant: Int = 0,
        val behind: Boolean = false,
    )

    data class Output(
        val composition: Composition, val sfx: List<Sfx>, val beats: List<Placed>, val notes: List<String>,
        val layers: List<List<Node>> = emptyList(),
    )

    private class Beat(
        val id: Int,
        val recipe: Recipe,
        val cue: Cue,
        val zone: Layout.Zone,
        val scene: Int,
        val minOut: Float,
        val behind: Boolean = false,
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

        private fun stageStart(scene: SceneScore, nominal: Float, next: Float, previous: Float): Float {
            if (input.footage == null || sceneBg(scene, input) == "media") return nominal
            val from = scene.from ?: 0
            val lands = scene.beats.mapNotNull { b ->
                b.time ?: b.at?.let { start(it) } ?: b.text?.let { t -> locate(t, norm, from..words.lastIndex.coerceAtLeast(from))?.let { start(it.first) } }
            }.minOrNull() ?: return nominal
            val earliest = minOf(nominal, maxOf(previous + MIN_SCENE, lands - Craft.SCENE_LEAD))
            return (lands - Craft.SCENE_LEAD).coerceIn(earliest, maxOf(earliest, next - MIN_SCENE))
        }

        fun start(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.startMs?.div(1000f) ?: 0f
        fun end(i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.endMs?.div(1000f) ?: 0f

        fun overFootage(scene: SceneScore) = input.footage != null && sceneBg(scene, input) == "media"

        val restart: Float? = input.edit?.takeIf { it.opening.isNotEmpty() }?.preludeLength

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
            val pushed = beats.filter { layout.outOf(it.id) != null }.map { it.id }.toSet()
            for (b in beats) b.out = layout.outOf(b.id) ?: b.out
            beats.removeAll { it.id in pushed && it.out - it.cue.at < MIN_SHOWN }
            holdStages(beats)
            val graphics = keepInside(beats.associateWith { b -> b.recipe.build(b.cue.copy(out = b.out), look, fitter) }).mapValues { (b, built) ->
                val accent = b.accent ?: return@mapValues built
                val box = built.box ?: io.trimio.engine.motion.recipe.Box.around(b.cue.x, b.cue.y, b.cue.width, b.cue.height)
                built.copy(nodes = built.nodes + io.trimio.engine.motion.art.TemplateRecipes.accent(accent, box, b.cue.at, b.out, b.cue.rtl))
            }
            val captionBeats = if (score.captions.show && words.isNotEmpty()) captions(this, beats) else emptyList()
            val built = graphics + captionBeats.associateWith { b -> b.recipe.build(b.cue.copy(out = b.out), look, fitter) }
            for ((b, out) in built) if (b.cue.overMedia) out.nodes.forEach { guard(it, look.guard) }

            val transitions = mutableListOf<Node>()
            val transitionSfx = mutableListOf<Sfx>()
            restart?.let { r -> rewind(r, transitions, transitionSfx) }
            val sceneGroups = scenes.indices.map { k -> sceneGroup(k, built, transitions, transitionSfx) }
            val texture = if (input.footage != null) FOOTAGE_TEXTURE else 1f
            val underText = listOfNotNull(
                EffectNode(Effect.Grain(look.grain * texture)).takeIf { look.grain > 0f },
            )
            val overlays = built.values.flatMap { it.overlays } + transitions + listOfNotNull(
                EffectNode(Effect.Vignette((look.vignette * texture).anim)),
            )
            val people = beats.filter { it.behind }.flatMap { b -> person(b.cue.at - PERSON_LEAD, b.out) }
            val pictureNodes = FinishingPass.wrap(
                picture(base(), "footage") + sceneGroups + people,
                film = look.film,
                grade = look.finish,
            )
            val root = Group(pictureNodes + underText + captionBeats.flatMap { built.getValue(it).nodes } + overlays, name = "root")
            val camera = camera(scenes, sceneStarts, sceneEnds, built.values.flatMap { it.camera }, footage = input.footage != null)
            val sfx = mixSfx(built.values.flatMap { it.sfx } + transitionSfx, duration)
            val order = (beats + captionBeats).sortedBy { it.cue.at }
            val placed = order.map {
                val c = it.cue
                val box = built.getValue(it).box
                Placed(
                    it.recipe.name, it.zone.name.lowercase(), c.at, it.out, c.text,
                    box?.left ?: (c.x - c.width / 2f), box?.top ?: (c.y - c.height / 2f), box?.right ?: (c.x + c.width / 2f), box?.bottom ?: (c.y + c.height / 2f),
                    c.overMedia, c.variant, it.behind,
                )
            }
            val composition = Composition(w, h, input.fps, duration, look.canvas, root, camera)
            // Craft rules run on the real scene graph and report; they do not block the compile
            // (recipes that predate the rules would otherwise fail every render). Strict consumers
            // still gate through CraftGate.enforce / Policy.Reject.
            val findings = CraftGate.audit(
                composition,
                hasAudio = sfx.isNotEmpty() || input.footage != null,
            ).findings
            findings.forEach { notes += "craft ${it.rule}: ${it.message}" }
            return Output(composition, sfx, placed, notes, order.map { built.getValue(it).nodes })
        }
