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
 * The supervising editor's eyes: the director model (or the light looker) looks at the rendered
 * frame where each graphic is fully on screen and says whether it reads, whether it covers the
 * face, whether the screen is crowded, and whether it pictures what is being said. Problems turn
 * into score edits: a crowded or face-covering graphic moves or goes, an unreadable one gets a
 * full-frame stage, a wrong picture is dropped.
 */
class VisionCritic(private val model: LanguageModel, private val maxFrames: Int = 6) {

    /**
     * What the eyes saw. Small models are unreliable at yes/no judgements ("does it cover the
     * face?" is answered yes for everything), but good at saying where things are; overlap is
     * worked out from the places.
     */
    @Serializable
    data class Verdict(
        /** top, middle, bottom or none (no person in the frame). */
        val face: String = "none",
        /** top, middle, bottom or full. */
        val graphic: String = "top",
        val readable: Boolean = true,
        val crowded: Boolean = false,
        /** good, weak or wrong: does the graphic picture what is said? */
        val fits: String = "good",
        val note: String = "",
    ) {
        val coversFace: Boolean get() = face != "none" && (graphic == face || graphic == "full")
    }

    data class Look(val at: Float, val recipe: String, val verdict: Verdict)

    suspend fun review(score: Score, out: Compiler.Output, words: List<Word>, grab: FrameGrabber, log: (String) -> Unit = {}): Pair<List<Look>, Score?> {
        if (!model.canSee) return emptyList<Look>() to null
        // The moment each graphic has landed: 0.7 s in, or its middle when shorter.
        val graphics = out.beats.filter { it.recipe != "pop-captions" }.sortedByDescending { it.out - it.at }.take(maxFrames).sortedBy { it.at }
        if (graphics.isEmpty()) return emptyList<Look>() to null
        val times = graphics.map { minOf(it.at + 0.7f, (it.at + it.out) / 2f) }
        val frames = grab.frames(out, times)
        val looks = graphics.indices.mapNotNull { i ->
            val g = graphics[i]
            val said = words.filter { w -> w.range.startMs / 1000f in (g.at - 1f)..(g.out) }.joinToString(" ") { it.text }
            val verdict = runCatching { ask(frames[i], g.recipe, g.text, said) }.getOrNull() ?: return@mapNotNull null
            log("eyes ${round1(times[i])}s ${g.recipe}: face=${verdict.face} graphic=${verdict.graphic} readable=${verdict.readable} crowded=${verdict.crowded} fits=${verdict.fits} — ${verdict.note}")
            Look(g.at, g.recipe, verdict)
        }
        return looks to fix(score, looks, words)
    }

    private suspend fun ask(frame: RgbImage, recipe: String, text: String, said: String): Verdict {
        val message = "The speaker is saying: \"${said.take(200)}\".\nThe graphic on screen is a ${WHAT[recipe] ?: recipe}${if (text.isNotBlank()) " showing \"$text\"" else ""}.\n" +
            "Judge this frame of the edit."
        return model.generateStructured(
            GenerationRequest(
                system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, message, listOf(frame))),
                schema = schema, maxTokens = 120, temperature = 0f,
            ),
            Verdict.serializer(),
        )
    }

    /** Score edits for what the eyes found; null when nothing needs to change. */
    private fun fix(score: Score, looks: List<Look>, words: List<Word>): Score? {
        val bad = looks.filter { !it.verdict.readable || it.verdict.coversFace || it.verdict.crowded || it.verdict.fits == "wrong" }
        if (bad.isEmpty()) return null
        fun sceneAt(t: Float) = score.scenes.indexOfLast { s -> (s.time ?: s.from?.let { words.getOrNull(it)?.range?.startMs?.div(1000f) } ?: 0f) <= t + 0.05f }.coerceAtLeast(0)
        val scenes = score.scenes.toMutableList()
        for (l in bad) {
            val k = sceneAt(l.at)
            val scene = scenes[k]
            val v = l.verdict
            scenes[k] = when {
                // A wrong picture is worse than none.
                v.fits == "wrong" && l.recipe in PICTURES -> scene.copy(beats = scene.beats.filterNot { it.recipe == l.recipe })
                // Over the face or crowded: the helper pictures go first, then the graphic moves off the face.
                v.crowded -> scene.copy(beats = scene.beats.filterNot { it.recipe in PICTURES && scene.beats.size > 1 })
                v.coversFace && scene.bg == null -> scene.copy(beats = scene.beats.map { b -> if (b.recipe == l.recipe) b.copy(place = if (b.place == "lower") "top" else "lower") else b })
                // Unreadable over footage: give the graphic its own stage.
                !v.readable && scene.bg == null && k > 0 -> scene.copy(bg = "grid", transition = scene.transition ?: "zoom")
                else -> scene
            }
        }
        return if (scenes == score.scenes) null else score.copy(scenes = scenes)
    }

    private companion object {
        val PICTURES = setOf("object", "objects", "icon")
        val WHAT = mapOf(
            "counter" to "big animated number", "logos" to "row of app logos", "terminal" to "terminal window", "network" to "node map",
            "meter" to "before/after bar meter", "chart" to "line chart", "object" to "picture sticker", "objects" to "row of pictures",
            "list" to "checklist", "comment" to "comment call-to-action bubble", "lower-third" to "name caption", "stamp" to "stamp",
            "slam" to "big headline", "stack" to "big headline", "mask-rise" to "headline", "flip" to "headline", "spread" to "headline",
            "type-on" to "typed headline", "blur-in" to "headline", "glitch" to "headline",
        )
        const val SYSTEM = "You are the supervising editor of short vertical videos. Look at one frame. face: which third of the frame the person's face is in (none if no person). " +
            "graphic: which third the motion graphic occupies (full if it fills the frame). readable: can the graphic's text be read at phone size. " +
            "crowded: more than two things compete for attention. fits: does the graphic picture what is being said. Answer in JSON."
        val schema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
              "face":{"type":"string","enum":["top","middle","bottom","none"]},
              "graphic":{"type":"string","enum":["top","middle","bottom","full"]},
              "readable":{"type":"boolean"},"crowded":{"type":"boolean"},
              "fits":{"type":"string","enum":["good","weak","wrong"]},"note":{"type":"string","maxLength":60}}}""",
        ).jsonObject

        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
    }
}
