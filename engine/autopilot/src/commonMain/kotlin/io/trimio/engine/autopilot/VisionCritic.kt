package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Renders frames of a compiled edit for the eyes to look at (small RGB is plenty). */
fun interface FrameGrabber {
    suspend fun frames(output: Compiler.Output, times: List<Float>): List<RgbImage>
}

/**
 * The supervising editor's eyes. Measured on labelled frames of a real edit (VisionBenchTest),
 * small vision models are poor at yes/no judgements ("does the graphic cover the face?" — the
 * 4B director answered yes for 6 of 12 frames wrongly) but good at grounding and reading: asked
 * for the face's box, Qwen3.5-4B found it in 11 of 12 frames. So the eyes do what they are good
 * at, and the geometry is ours:
 *
 * - the model draws the face box; overlap is computed against the graphic's exact slot, which
 *   the compiler knows;
 * - the model reads the largest text; when it cannot read the graphic's own words, they are
 *   too small or too busy for a phone, and the graphic gets a full-frame stage.
 */
class VisionCritic(private val model: LanguageModel, private val maxFrames: Int = 6) {

    @Serializable
    data class Seen(
        val person: String = "no",
        /** Face box, 0–1000 of the frame: x1, y1, x2, y2. */
        val face: List<Int> = emptyList(),
        /** The largest text on screen, as read. */
        val text: String = "",
    )

    data class Look(val beat: Compiler.Placed, val seen: Seen, val coversFace: Boolean, val readable: Boolean)

    suspend fun review(score: Score, out: Compiler.Output, words: List<Word>, grab: FrameGrabber, log: (String) -> Unit = {}): Pair<List<Look>, Score?> {
        if (!model.canSee) return emptyList<Look>() to null
        val w = out.composition.width.toFloat()
        val h = out.composition.height.toFloat()
        // The moment each graphic has landed: 0.7 s in, or its middle when shorter.
        val graphics = out.beats.filter { it.recipe != CAPTIONS }.sortedByDescending { it.out - it.at }.take(maxFrames).sortedBy { it.at }
        if (graphics.isEmpty()) return emptyList<Look>() to null
        val times = graphics.map { minOf(it.at + 0.7f, (it.at + it.out) / 2f) }
        val frames = grab.frames(out, times)
        val looks = graphics.indices.mapNotNull { i ->
            val g = graphics[i]
            val seen = runCatching { ask(frames[i]) }.getOrNull() ?: return@mapNotNull null
            val face = seen.face.takeIf { seen.person == "yes" && it.size == 4 && it[2] > it[0] && it[3] > it[1] }
                ?.let { listOf(it[0] / 1000f * w, it[1] / 1000f * h, it[2] / 1000f * w, it[3] / 1000f * h) }
            val covers = g.overFootage && face != null && cover(face, listOf(g.left, g.top, g.right, g.bottom)) > COVER
            val readable = g.text.isBlank() || g.recipe !in TEXT || legible(g.text, seen.text)
            log("eyes ${round1(times[i])}s ${g.recipe}: face=${seen.face} covers=$covers read=\"${seen.text}\" readable=$readable")
            Look(g, seen, covers, readable)
        }
        return looks to fix(score, looks, words)
    }

    private suspend fun ask(frame: RgbImage): Seen = model.generateStructured(
        GenerationRequest(system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, QUESTION, listOf(frame))), schema = schema, maxTokens = 90, temperature = 0f),
        Seen.serializer(),
    )

    /** Score edits for what the eyes found; null when nothing needs to change. */
    private fun fix(score: Score, looks: List<Look>, words: List<Word>): Score? {
        val bad = looks.filter { it.coversFace || !it.readable }
        if (bad.isEmpty()) return null
        fun sceneAt(t: Float) = score.scenes.indexOfLast { s -> (s.time ?: s.from?.let { words.getOrNull(it)?.range?.startMs?.div(1000f) } ?: 0f) <= t + 0.05f }.coerceAtLeast(0)
        val scenes = score.scenes.toMutableList()
        for (l in bad) {
            val k = sceneAt(l.beat.at)
            val scene = scenes[k]
            scenes[k] = when {
                // Over the face: pictures go, other graphics move to the other free band.
                l.coversFace && l.beat.recipe in PICTURES -> scene.copy(beats = scene.beats.filterNot { it.recipe == l.beat.recipe })
                l.coversFace -> scene.copy(beats = scene.beats.map { b -> if (b.recipe == l.beat.recipe) b.copy(place = if (l.beat.zone == "lower") "top" else "lower") else b })
                // Words the eyes cannot read get the whole frame.
                scene.bg == null && k > 0 -> scene.copy(bg = "grid", transition = scene.transition ?: "zoom")
                else -> scene
            }
        }
        return if (scenes == score.scenes) null else score.copy(scenes = scenes)
    }

    /** Share of the face box covered by [g]. */
    private fun cover(face: List<Float>, g: List<Float>): Float {
        val w = (minOf(face[2], g[2]) - maxOf(face[0], g[0])).coerceAtLeast(0f)
        val h = (minOf(face[3], g[3]) - maxOf(face[1], g[1])).coerceAtLeast(0f)
        val area = (face[2] - face[0]) * (face[3] - face[1])
        return if (area <= 0f) 0f else w * h / area
    }

    /** The eyes read at least half of the graphic's letters (sound-alike letters count as read). */
    private fun legible(intended: String, read: String): Boolean {
        val want = Proofreader.key(intended)
        val got = Proofreader.key(read)
        if (want.isEmpty()) return true
        val common = want.toSet().count { c -> c in got }
        return common * 2 >= want.toSet().size
    }

    private companion object {
        const val CAPTIONS = "pop-captions"
        const val COVER = 0.15f
        val PICTURES = setOf("object", "objects", "icon")
        val TEXT = setOf("slam", "stack", "mask-rise", "flip", "spread", "type-on", "blur-in", "glitch", "stamp", "comment")
        const val SYSTEM = "You check frames of short vertical videos for an editor. Look carefully and answer in JSON."
        const val QUESTION = "Is there a person in this frame? If so, give the bounding box of the person's face (from the top of the head to the chin) " +
            "as [x1, y1, x2, y2] in coordinates from 0 to 1000 relative to the image width and height. text: the largest text on screen."
        val schema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
              "person":{"type":"string","enum":["yes","no"]},
              "face":{"type":"array","items":{"type":"integer"},"minItems":4,"maxItems":4},
              "text":{"type":"string","maxLength":40}}}""",
        ).jsonObject

        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
    }
}
