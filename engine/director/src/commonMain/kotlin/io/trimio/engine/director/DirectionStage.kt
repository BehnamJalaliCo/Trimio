package io.trimio.engine.director

import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.CancellationException

/** Chooses the model that directs a job, or none (rules only). */
fun interface DirectorModels {
    suspend fun select(job: JobSpec): LanguageModel?
}

/**
 * Cloud when the user picked it and connected a key, the on-device model otherwise; with neither,
 * the rules engine directs alone. Offline cloud failures fall back at generation time.
 */
class DefaultDirectorModels(
    private val cloud: CloudModels?,
    private val local: suspend () -> LanguageModel?,
) : DirectorModels {
    override suspend fun select(job: JobSpec): LanguageModel? = when (job.director) {
        DirectorBackend.Cloud -> cloud?.firstAvailable() ?: local()
        DirectorBackend.OnDevice -> local()
    }
}

/**
 * Pipeline stage that turns transcript + analysis + prompt into the edit:
 *  1. read the brief and pick the style;
 *  2. draft a plan with the rules engine (always available, instant);
 *  3. let the selected LLM refine it (local or cloud), sanitised; any failure keeps the draft;
 *  4. compose the timeline, tune the style and run quality control;
 *  5. publish [StandardArtifacts.Timeline] and [StandardArtifacts.Style], narrating each decision live.
 */
class DirectionStage(
    private val styles: StylePackRepository,
    private val models: DirectorModels,
    private val qc: QualityControl = QualityControl(),
) : PipelineStage {
    override val id = StageId.Direction

    override suspend fun run(context: StageContext) {
        val job = context.job
        val transcript = context.artifacts[StandardArtifacts.Transcript] ?: Transcript(job.language ?: Language.Persian, emptyList())
        val packs = styles.all()
        val brief = BriefParser.parse(job.prompt)

        val draftPack = StyleMatcher.choose(packs, brief, job.styleId)
        val draft = RulesDirector.plan(transcript, brief, draftPack, job.input.durationMs)
        context.emit(LiveSignal.PlanStep("سبک «${draftPack.nameFa}» انتخاب شد", "Picked the ${draftPack.nameEn} style"))
        context.progress(0.1f)

        var plan = draft
        val model = runCatching { models.select(job) }.getOrNull()
        if (model != null && transcript.words.isNotEmpty()) {
            context.emit(LiveSignal.Note(if (model.isLocal) "مدل روی گوشی در حال طراحی ادیت است" else "کارگردان ابری در حال طراحی ادیت است", "The ${if (model.isLocal) "on-device" else "cloud"} director is planning the edit"))
            var chars = 0
            try {
                val refined = LlmDirector(model).refine(job.prompt, transcript, job.input, packs, draft) { text ->
                    chars += text.length
                    // Streams are a few KB; map them onto the stage bar so it never sits still.
                    context.progress(0.1f + 0.6f * (chars / 2_500f).coerceAtMost(1f))
                }
                plan = enforceBrief(PlanSanitizer.sanitize(refined.plan, draft, transcript, packs.map { it.id }.toSet()), brief, job, draft, transcript)
                if (!refined.servedBy.startsWith(model.id)) {
                    // A server-side refusal fallback answered instead of the requested model: say so.
                    context.emit(LiveSignal.Note("پاسخ توسط مدل جایگزین (${refined.servedBy}) داده شد", "Answered by the fallback model (${refined.servedBy})"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LanguageModelException) {
                context.emit(fallbackNote(e))
            } catch (e: Exception) {
                context.emit(LiveSignal.Note("خطای مدل؛ ادیت با موتور قوانین ادامه می‌یابد", "Model error; continuing with the rules engine"))
            } finally {
                model.release()
            }
        }
        context.progress(0.75f)

        val pack = packs.firstOrNull { it.id == plan.styleId } ?: draftPack
        val (style, notes) = qc.tuneStyle(pack.spec, plan, job.input)
        val timeline = qc.finalize(TimelineComposer.compose(plan, transcript, style, job.input, job.seed), job.input)
            ?: qc.finalize(TimelineComposer.compose(draft, transcript, draftPack.spec, job.input, job.seed), job.input)
            ?: error("Director could not produce a renderable timeline")

        narrate(context, plan, transcript, pack.nameFa, pack.nameEn)
        notes.forEach { context.emit(LiveSignal.Note(it.textFa, it.textEn)) }

        context.artifacts[StandardArtifacts.Timeline] = timeline
        context.artifacts[StandardArtifacts.Style] = if (timeline.styleId == pack.id) style else draftPack.spec
        context.progress(1f)
    }

    /** What the user said explicitly always beats the model's taste. */
    private fun enforceBrief(plan: EditPlan, brief: Brief, job: JobSpec, draft: EditPlan, transcript: Transcript): EditPlan = plan.copy(
        styleId = if (job.styleId != null || brief.styleId != null) draft.styleId else plan.styleId,
        cutSilences = plan.cutSilences && brief.cutSilences,
        cutFillers = plan.cutFillers && brief.cutFillers,
        music = if (!brief.music) "none" else plan.music,
        captionMode = brief.captionMode?.name ?: plan.captionMode,
        emphasis = (plan.emphasis + transcript.words.indices.filter { Lexicon.norm(transcript.words[it].text) in brief.emphasize }).distinct().sorted(),
        headline = brief.headline ?: plan.headline,
    )

    private fun fallbackNote(e: LanguageModelException): LiveSignal.Note = when (e.kind) {
        LanguageModelException.Kind.Refused -> LiveSignal.Note(
            "مدل این درخواست را رد کرد؛ ادیت با موتور قوانین ساخته شد",
            "The model declined this request; the rules engine made the edit",
        )
        LanguageModelException.Kind.Auth -> LiveSignal.Note("کلید API نامعتبر است؛ از موتور قوانین استفاده شد", "Invalid API key; used the rules engine")
        LanguageModelException.Kind.Network, LanguageModelException.Kind.RateLimited ->
            LiveSignal.Note("اتصال به کارگردان ابری برقرار نشد؛ ادیت آفلاین ساخته شد", "Couldn't reach the cloud director; made the edit offline")
        else -> LiveSignal.Note("پاسخ مدل قابل استفاده نبود؛ از موتور قوانین استفاده شد", "The model's answer was unusable; used the rules engine")
    }

    private suspend fun narrate(context: StageContext, plan: EditPlan, transcript: Transcript, nameFa: String, nameEn: String) {
        val words = transcript.words
        if (plan.summaryFa.isNotBlank()) context.emit(LiveSignal.PlanStep(plan.summaryFa, plan.summaryEn))
        if (plan.hookEnd >= 0) {
            val hook = words.take(plan.hookEnd + 1).joinToString(" ") { it.text }
            context.emit(LiveSignal.PlanStep("هوک: «$hook»", "Hook: “$hook”"))
        }
        plan.emphasis.take(6).forEach { i ->
            words.getOrNull(i)?.let { w -> context.emit(LiveSignal.PlanStep("تأکید روی «${w.text}»", "Emphasis on “${w.text}”")) }
        }
        plan.elements.forEach { cue ->
            val word = words.getOrNull(cue.word)?.text.orEmpty()
            context.emit(LiveSignal.AssetChosen("${cue.kind}:${cue.value}", "${elementFa(cue.kind)} روی «$word»", "${cue.kind} on “$word”"))
        }
        if (plan.ctaStart >= 0) context.emit(LiveSignal.PlanStep("فراخوان پایانی برجسته شد", "Call to action highlighted"))
        if (plan.music != "none") context.emit(LiveSignal.PlanStep("موسیقی: ${plan.music}", "Music: ${plan.music}"))
        context.emit(LiveSignal.PlanStep("نقشهٔ ادیت با سبک «$nameFa» آماده شد", "Edit plan ready in $nameEn"))
    }

    private fun elementFa(kind: String) = when (kind) {
        "counter" -> "شمارنده"
        "chart-up", "chart-down" -> "نمودار"
        "arrow-up", "arrow-down" -> "فلش"
        "badge" -> "برچسب"
        "progress" -> "نوار پیشرفت"
        else -> "آیکون"
    }
}
