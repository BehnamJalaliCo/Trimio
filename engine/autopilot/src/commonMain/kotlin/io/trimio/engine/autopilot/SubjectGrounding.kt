package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.score.Subject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The director's eyes check where the head is before the edit is planned. Small vision models are
 * good at grounding (Qwen3.5-4B drew the face box right on 11 of 12 benchmark frames, see
 * [VisionCritic]) and the pixel [SubjectFinder] is good at exact edges, so each checks the other:
 *
 * - the eyes draw the head box (0–1000, x1 y1 x2 y2) on two or three source frames; their median
 *   is what they saw;
 * - when the eyes and the pixels agree (IoU ≥ 0.5) the box is trusted, the average of both;
 * - when they disagree the union is taken if it is still a plausible head (graphics stay off both),
 *   else the surer side;
 * - when the pixels only had the framing prior, the eyes' box wins; when the eyes saw nothing
 *   usable, the pixels' box stands.
 */
class SubjectGrounding(private val eyes: LanguageModel, private val maxFrames: Int = 3) {

    @Serializable
    data class Seen(
        val person: String = "no",
        /** Head box, 0–1000 of the frame: x1, y1, x2, y2. */
        val head: List<Int> = emptyList(),
    )

    /** [frames]: source frames (about 288×512); [found]: the pixel finder's answer on the same clip. */
    suspend fun ground(frames: List<RgbImage>, found: SubjectFinder.Found?, log: (String) -> Unit = {}): Subject? {
        if (!eyes.canSee || frames.isEmpty()) return found?.subject
        val picked = if (frames.size <= maxFrames) frames else List(maxFrames) { frames[(it * 2 + 1) * frames.size / (maxFrames * 2)] }
        val boxes = picked.mapNotNull { frame ->
            runCatching { ask(frame) }.onFailure { log("subject eyes: ${it.message}") }.getOrNull()?.let(::box)
        }
        val seen = if (boxes.isEmpty()) null else Subject(median(boxes) { it.left }, median(boxes) { it.top }, median(boxes) { it.right }, median(boxes) { it.bottom })
        val fused = fuse(found, seen)
        log("subject: pixels ${found?.subject} (conf ${found?.confidence}${if (found?.prior == true) ", prior" else ""}) eyes $seen on ${boxes.size}/${picked.size} → $fused")
        return fused
    }

    private suspend fun ask(frame: RgbImage): Seen = eyes.generateStructured(
        GenerationRequest(system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, QUESTION, listOf(frame))), schema = schema, maxTokens = 60, temperature = 0f),
        Seen.serializer(),
    )

    companion object {
        /** IoU at which the eyes and the pixels agree. */
        const val AGREE = 0.5f
        private const val MAX_AREA = 0.3f
        private const val SURE = 0.6f

        /** Combines the pixel finder's [found] with what the eyes [seen]; see the class notes. */
        fun fuse(found: SubjectFinder.Found?, seen: Subject?): Subject? {
            if (seen == null || !plausible(seen)) return found?.subject
            if (found == null || found.prior) return seen
            val p = found.subject
            if (iou(p, seen) >= AGREE) return Subject((p.left + seen.left) / 2f, (p.top + seen.top) / 2f, (p.right + seen.right) / 2f, (p.bottom + seen.bottom) / 2f)
            val union = Subject(minOf(p.left, seen.left), minOf(p.top, seen.top), maxOf(p.right, seen.right), maxOf(p.bottom, seen.bottom))
            return when {
                plausible(union) -> union
                found.confidence >= SURE -> p
                else -> seen
            }
        }

        /** A model's 0–1000 box as fractions, when it is a box at all. */
        internal fun box(seen: Seen): Subject? {
            val b = seen.head
            if (seen.person != "yes" || b.size != 4 || b.any { it !in 0..1000 } || b[2] <= b[0] || b[3] <= b[1]) return null
            return Subject(b[0] / 1000f, b[1] / 1000f, b[2] / 1000f, b[3] / 1000f).takeIf(::plausible)
        }

        private fun plausible(s: Subject): Boolean {
            val w = s.right - s.left
            val h = s.bottom - s.top
            return w in 0.04f..0.8f && h in 0.03f..0.65f && w * h <= MAX_AREA
        }

        private fun iou(a: Subject, b: Subject) = SubjectFinder.iou(floatArrayOf(a.left, a.top, a.right, a.bottom), floatArrayOf(b.left, b.top, b.right, b.bottom))

        private fun median(boxes: List<Subject>, of: (Subject) -> Float): Float {
            val v = boxes.map(of).sorted()
            return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2f
        }

        private const val SYSTEM = "You locate people in frames of short vertical videos for an editor. Look carefully and answer in JSON."
        private const val QUESTION = "Is there a person in this frame? If so, give the bounding box of the person's head, from the top of the hair to the chin " +
            "(include the beard), as [x1, y1, x2, y2] in coordinates from 0 to 1000 relative to the image width and height."
        private val schema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
              "person":{"type":"string","enum":["yes","no"]},
              "head":{"type":"array","items":{"type":"integer"},"minItems":4,"maxItems":4}}}""",
        ).jsonObject
    }
}
