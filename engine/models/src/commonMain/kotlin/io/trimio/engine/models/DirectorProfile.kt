package io.trimio.engine.models

import kotlinx.serialization.Serializable

/** Order of the four numbers a model writes for a box. */
@Serializable
enum class BoxOrder {
    /** x1, y1, x2, y2 (Qwen2.5-VL onwards, Qwen3.5/3.6). */
    Xyxy,

    /** y1, x1, y2, x2 (Gemini and Gemma 4's `box_2d`). */
    Yxyx,
}

/**
 * How a model family writes bounding boxes. Asking a model for the other family's order costs it
 * most of its grounding: the vision benchmark's Gemma 4 run (2/12 face boxes) asked for x1,y1,x2,y2
 * while Gemma 4 is trained on `box_2d: [y1, x1, y2, x2]`.
 */
@Serializable
data class Grounding(val order: BoxOrder, val scale: Int = 1000) {
    /** The words that ask for a box in this model's own convention. */
    val ask: String
        get() = when (order) {
            BoxOrder.Xyxy -> "as [x1, y1, x2, y2] in coordinates from 0 to $scale relative to the image width and height"
            BoxOrder.Yxyx -> "as box_2d [y1, x1, y2, x2] in coordinates from 0 to $scale relative to the image height and width"
        }

    /** A box as the model wrote it → x1, y1, x2, y2 in 0..1, or null when it is not four numbers. */
    fun toUnitXyxy(box: List<Float>): List<Float>? {
        if (box.size != 4) return null
        val s = scale.toFloat()
        val v = box.map { (it / s).coerceIn(0f, 1f) }
        // Horizontal pair first: indices 0 and 2 in x-first order, 1 and 3 in y-first order.
        val (x, y) = if (order == BoxOrder.Xyxy) 0 to 1 else 1 to 0
        return listOf(minOf(v[x], v[x + 2]), minOf(v[y], v[y + 2]), maxOf(v[x], v[x + 2]), maxOf(v[y], v[y + 2]))
    }
}

/**
 * What the pipeline needs to know to get the same quality from every director family. The
 * deterministic planner and critic set the floor; the profile makes sure a model is asked the way
 * it was trained (prompt layout, reasoning switch, box convention, image budget) so that floor is
 * reached by every model and stronger models can only add to it.
 *
 * Temperatures are starting points, calibrated per family with `ModelEvalTest`.
 */
@Serializable
data class DirectorProfile(
    val family: String,
    /** Sampling temperature for the understanding step: a reading, not a creative act. */
    val understandingTemperature: Float,
    /** Image tokens per frame (Qwen: dynamic resolution, 32×32 px each; Gemma 4: 70/140/280/560/1120). */
    val maxImageTokens: Int,
    /**
     * Written after the assistant header to start the answer past the reasoning block. Qwen3.5/3.6
     * templates open `<think>` by default; llama.cpp's built-in ChatML does not, so without this a
     * free-text request may spend its tokens thinking, and a grammar-constrained one answers from
     * inside a thought it was never allowed to finish. Empty when the model does not think unasked.
     */
    val assistantPrefix: String = "",
    /** How it writes boxes; null for text-only models. */
    val grounding: Grounding? = null,
    /** Context window to allocate: the understanding prompt of a 90 s video plus answer fits 12k. */
    val contextSize: Int = DEFAULT_CONTEXT,
) {
    val suppressesThinking: Boolean get() = assistantPrefix.isNotEmpty()

    companion object {
        const val DEFAULT_CONTEXT = 12_288

        /** Qwen3.5/3.6 chat templates with enable_thinking=false. */
        const val QWEN_NO_THINK = "<think>\n\n</think>\n\n"

        val Qwen35 = DirectorProfile("qwen3.5", 0.3f, 256, QWEN_NO_THINK, Grounding(BoxOrder.Xyxy))
        val Qwen36 = DirectorProfile("qwen3.6", 0.3f, 256, QWEN_NO_THINK, Grounding(BoxOrder.Xyxy))

        /** Thinking is opt-in (`<|think|>` in the system turn) and E2B/E4B emit no empty thought when off. */
        val Gemma4 = DirectorProfile("gemma-4", 0.4f, 280, grounding = Grounding(BoxOrder.Yxyx))

        /** Liquid recommends low temperatures for LFM2.x; text only (sees through the light looker). */
        val Lfm25 = DirectorProfile("lfm2.5", 0.3f, 0)

        /** The light looker: a small context is plenty for one frame and a short answer. */
        val QwenLooker = Qwen35.copy(family = "qwen3.5-looker", contextSize = 4096)

        /** By catalogue id prefix; a server-added model of an unknown family gets its chat format's default. */
        fun of(spec: ModelSpec): DirectorProfile = when {
            spec.id.startsWith("qwen3.5-0.8b") -> QwenLooker
            spec.id.startsWith("qwen3.5") -> Qwen35
            spec.id.startsWith("qwen3.6") -> Qwen36
            spec.id.startsWith("gemma-4") -> Gemma4
            spec.id.startsWith("lfm2") -> Lfm25
            spec.chatFormat == ChatFormat.Gemma4 -> Gemma4
            spec.chatFormat == ChatFormat.Lfm -> Lfm25
            else -> DirectorProfile("generic", 0.3f, 256, grounding = Grounding(BoxOrder.Xyxy))
        }

        /** For a running model known only by id (cloud models and unknown ids get null). */
        fun forModelId(id: String): DirectorProfile? = ModelCatalog.byId(id)?.let(::of)
    }
}
