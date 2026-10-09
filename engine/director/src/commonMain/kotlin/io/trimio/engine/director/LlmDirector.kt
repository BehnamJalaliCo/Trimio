package io.trimio.engine.director

import io.trimio.core.model.asset.IconCatalog
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.styles.StylePack
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lets a language model refine the rules engine's draft. The model sees the user's brief, the
 * indexed transcript (with prosodic stress marks) and the draft, and returns a full [EditPlan].
 * It decides *what* to do; timing, layout and rendering stay deterministic.
 */
class LlmDirector(private val model: LanguageModel) {

    data class Result(val plan: EditPlan, val servedBy: String)

    suspend fun refine(
        prompt: String,
        transcript: Transcript,
        input: InputSource,
        packs: List<StylePack>,
        draft: EditPlan,
        onText: (String) -> Unit = {},
    ): Result {
        val maxWords = if (model.isLocal) 1_400 else 6_000
        val request = GenerationRequest(
            system = SYSTEM,
            messages = listOf(ChatMessage(ChatRole.User, userMessage(prompt, transcript, input, packs, draft, maxWords))),
            schema = EditPlanSchema.schema(packs.map { it.id }),
            maxTokens = if (model.isLocal) 1_800 else 16_000,
            temperature = 0.2f,
        )
        var servedBy = model.id
        val plan = model.generateStructured(request, EditPlan.serializer(), onServed = { servedBy = it }, onText = onText)
        return Result(plan, servedBy)
    }

    private fun userMessage(prompt: String, transcript: Transcript, input: InputSource, packs: List<StylePack>, draft: EditPlan, maxWords: Int): String = buildString {
        appendLine("<brief>")
        appendLine(prompt.ifBlank { "(no instructions: make it engaging and professional)" })
        appendLine("</brief>")
        appendLine()
        appendLine("Input: ${if (input is InputSource.AudioOnly) "audio only (the style paints the whole picture)" else "video"}, ${input.durationMs / 1000.0}s, spoken language: ${transcript.language.code}")
        appendLine()
        appendLine("<styles>")
        packs.forEach { appendLine("${it.id}: ${it.nameEn} — ${it.tags.joinToString(", ")}") }
        appendLine("</styles>")
        appendLine()
        appendLine("<transcript>")
        appendLine("Format: index@seconds word, * marks vocal stress (louder/higher pitch).")
        val words = transcript.words
        val shown = if (words.size <= maxWords) words.indices.toList() else (0 until maxWords * 2 / 3) + (words.size - maxWords / 3 until words.size)
        var previous = -1
        for (i in shown) {
            if (i != previous + 1) appendLine("… (${i - previous - 1} words omitted) …")
            val w = words[i]
            append(i).append('@').append(((w.range.startMs / 100.0).roundToInt() / 10.0)).append(' ').append(w.text)
            if (w.emphasis >= 0.6f) append('*')
            append('\n')
            previous = i
        }
        appendLine("</transcript>")
        appendLine()
        appendLine("<draft>")
        appendLine(json.encodeToString(EditPlan.serializer(), draft))
        appendLine("</draft>")
        appendLine()
        append("Return the improved plan.")
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        val SYSTEM = """
            You are the director of a professional short-form video editor for Persian and English creators.
            You decide the edit; a deterministic engine renders it frame-perfectly. Return one JSON object only.

            Decide:
            - styleId: the style that best fits the brief and the content. If the brief names a style, use it.
            - energy: 0 calm … 1 hyper. Follow the brief's tone; ads and hype content are 0.8+, luxury/education 0.3–0.55.
            - captionMode: "auto" keeps the style's own caption behaviour; change it only if the brief asks.
            - cutSilences / cutFillers: true unless the brief asks to keep pauses or fillers.
            - music: a mood matching the content, or "none" if the brief says no music.
            - hookEnd: last word index of the opening hook (usually the first sentence, under 4 s).
            - ctaStart: first word index of the call to action (follow, subscribe, link, comment…), or -1.
            - emphasis: word indices that carry the message — key nouns, numbers, money, results, contrast words,
              words with vocal stress (*). Not filler or function words. Roughly one every 1–2 seconds; more for high energy.
            - elements: motion graphics on the word they illustrate, at most one every ~2 seconds:
              counter (value is the spoken number, optionally with %, $ prefix, or " تومان"), ticker (value: BTC, ETH,
              GOLD…; label: signed percent move like +5.2) when a market and its move are said together, chart-up /
              chart-down for market moves, arrow-up / arrow-down for change, icon (value: one of ${IconCatalog.ids.joinToString(", ")}),
              badge (value: short text), progress (value 0–100). label is an optional 1–3 word caption under the
              element, in the spoken language.
            - headline: a short punchy title for the hook in the spoken language, or "".
            - summaryFa / summaryEn: one sentence each describing the edit to the user.

            The draft was made by heuristics; keep what is good, fix what is not. Word indices must exist in the transcript.
            Persian text must be natural Persian, not transliteration.
        """.trimIndent()
    }
}

/**
 * Makes any model output safe to compose: unknown styles, out-of-range indices, invalid element
 * kinds or non-numeric counters fall back to the draft or are dropped; density limits are enforced.
 */
object PlanSanitizer {

    fun sanitize(plan: EditPlan, draft: EditPlan, transcript: Transcript, styleIds: Set<String>): EditPlan {
        val words = transcript.words
        val n = words.size
        fun valid(i: Int) = i in 0 until n
        val energy = plan.energy.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: draft.energy

        val emphasisGap = (900 - 500 * energy).toLong()
        val emphasis = mutableListOf<Int>()
        for (i in plan.emphasis.filter(::valid).distinct().sorted()) {
            val t = words[i].range.startMs
            if (emphasis.none { abs(words[it].range.startMs - t) < emphasisGap }) emphasis += i
        }

        val texts = words.map { it.text }
        val elements = mutableListOf<ElementCue>()
        for (cue in plan.elements.filter { valid(it.word) && it.kind in ElementCue.KINDS }.sortedBy { it.word }) {
            val fixed = when (cue.kind) {
                "counter" -> if (Regex("^\\$?\\s*-?[0-9]+(\\.[0-9]+)?\\s*(%| تومان)?$").matches(cue.value.trim())) cue
                else NumberWords.at(texts, cue.word)?.let { cue.copy(value = it.value.toLong().toString()) }
                "icon" -> if (cue.value in ElementCue.ICONS) cue else cue.copy(value = IconMatcher.match(cue.value + " " + cue.label + " " + words[cue.word].text) ?: "star")
                "ticker" -> if (cue.value.isBlank() || cue.label.trim().trimStart('+', '-').toDoubleOrNull() == null) null else cue
                "badge" -> cue.copy(value = cue.value.ifBlank { words[cue.word].text }.take(24))
                else -> cue
            } ?: continue
            val t = words[cue.word].range.startMs
            if (elements.none { abs(words[it.word].range.startMs - t) < 1_200 }) elements += fixed.copy(label = fixed.label.take(32))
        }

        val hook = plan.hookEnd.takeIf { it == -1 || valid(it) } ?: draft.hookEnd
        return EditPlan(
            styleId = plan.styleId.takeIf { it in styleIds } ?: draft.styleId,
            energy = energy,
            captionMode = plan.captionMode.takeIf { it in EditPlanSchema.CAPTION_MODES } ?: "auto",
            cutSilences = plan.cutSilences,
            cutFillers = plan.cutFillers,
            music = plan.music.takeIf { it in EditPlanSchema.MUSIC_MOODS } ?: draft.music,
            hookEnd = hook,
            ctaStart = plan.ctaStart.takeIf { (it == -1 || valid(it)) && (it == -1 || it > hook) } ?: -1,
            // Small models sometimes return an empty list; an edit with no emphasis is never what was meant.
            emphasis = emphasis.ifEmpty { draft.emphasis },
            elements = elements.take(16),
            headline = plan.headline.trim().take(48),
            summaryFa = plan.summaryFa.trim().take(160).ifBlank { draft.summaryFa },
            summaryEn = plan.summaryEn.trim().take(160).ifBlank { draft.summaryEn },
        )
    }
}
