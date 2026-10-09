package io.trimio.core.pipeline

/**
 * The fixed sequence of work for one edit job. [weight] is the share of the 0-100% bar,
 * calibrated to typical wall-clock time on a flagship phone (transcription and rendering dominate).
 */
enum class StageId(val weight: Int, val titleFa: String, val titleEn: String) {
    Ingest(2, "خواندن فایل", "Reading media"),
    AudioCleanup(5, "پاک‌سازی صدا", "Cleaning audio"),
    Transcription(25, "تشخیص کلمات", "Recognising speech"),
    Alignment(8, "زمان‌بندی دقیق کلمات", "Aligning words"),
    Analysis(5, "تحلیل تأکید و ریتم", "Analysing emphasis"),
    Direction(10, "طراحی نقشهٔ ادیت", "Directing the edit"),
    AssetMatching(5, "انتخاب المان و صدا", "Picking elements"),
    Render(35, "رندر موشن‌گرافی", "Rendering motion"),
    Export(5, "ساخت خروجی نهایی", "Exporting");

    companion object {
        val TOTAL_WEIGHT: Int = entries.sumOf { it.weight }
    }
}

/** One unit of pipeline work. Implementations live in engine modules; the demo ones in [io.trimio.core.pipeline.demo]. */
interface PipelineStage {
    val id: StageId

    /**
     * Do the work, reporting progress through [context]. Must be cancellable (check
     * `isActive` / call suspending functions) and must not keep heavy models loaded after returning.
     */
    suspend fun run(context: StageContext)
}

interface StageContext {
    val job: JobSpec
    val artifacts: Artifacts

    /** Progress inside this stage, 0..1. Values are clamped and never move backwards. */
    fun progress(fraction: Float)

    /** Push live content to the UI (recognised words, plan steps, rendered frames...). */
    suspend fun emit(signal: LiveSignal)
}
