package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import io.trimio.core.data.EngineSummary
import io.trimio.core.data.Sharer
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.diagnostics.EngineRecord
import io.trimio.engine.production.diagnostics.Fact
import io.trimio.engine.production.diagnostics.RunOutcome
import io.trimio.engine.production.diagnostics.RunReport
import io.trimio.feature.stream.TechFact
import io.trimio.feature.stream.TechSitting
import io.trimio.feature.stream.TechStage
import io.trimio.feature.stream.TechnicalDetails
import io.trimio.feature.stream.TechnicalDetailsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Run [key]'s technical report: [live] while its run reports one, else the one kept with the
 * project (read once from beside its checkpoints); null when none was kept.
 */
@Composable
internal fun rememberReport(key: String?, live: RunReport?): RunReport? {
    val checkpoints = koinInject<Checkpoints>()
    val stored by produceState<RunReport?>(null, key, live == null) {
        value = if (key == null || live != null) null else withContext(Dispatchers.Default) { checkpoints.reports.load(key) }
    }
    return live ?: stored
}

/** «جزئیات فنی» over the production screens: the report, copied or shared as a text file. */
@Composable
internal fun TechnicalOverlay(report: RunReport, persian: Boolean, onClose: () -> Unit) {
    val koin = getKoin()
    val sharer = remember { koin.getOrNull<Sharer>() }
    val details = TechnicalUi.of(report, persian)
    TechnicalDetailsScreen(details, onShare = { sharer?.shareFiles(mapOf(details.fileName to details.text), "Trimio technical report") }, onClose = onClose)
}

/** A run's [RunReport] as «جزئیات فنی» shows it, and the engine record as the models screen's hardware line reads it. */
object TechnicalUi {
    fun of(report: RunReport, persian: Boolean): TechnicalDetails {
        fun t(fa: String, en: String) = if (persian) fa else en
        val attempt = report.attempt
        return TechnicalDetails(
            runId = report.key,
            status = listOf(outcome(report.outcome, persian), t("تلاش ${digits(attempt, persian)}", "attempt $attempt")).joinToString(" · "),
            running = report.outcome == RunOutcome.Running,
            summary = summary(report, persian),
            stages = report.steps.filter { it.attempt == attempt }.map { TechStage(stepName(it.name, persian), it.seconds, it.detail) },
            metrics = metrics(report, persian),
            jury = report.jury.filter { it.attempt == attempt }.map { TechSitting(it.index, it.min, it.mean, it.findings) },
            problems = problems(report, persian),
            text = report.text(),
            fileName = "trimio-report-${report.key}.txt",
        )
    }

    private fun summary(r: RunReport, persian: Boolean): List<TechFact> {
        fun t(fa: String, en: String) = if (persian) fa else en
        fun fact(list: List<Fact>, name: String) = list.firstOrNull { it.name == name }?.value
        return listOfNotNull(
            fact(r.device, "device")?.let { TechFact(t("دستگاه", "Device"), listOfNotNull(it, fact(r.device, "chip")).joinToString(" · ")) },
            fact(r.device, "os")?.let { TechFact(t("سیستم", "System"), listOfNotNull(it, fact(r.device, "memory")).joinToString(" · ")) },
            r.app.takeIf { it.isNotBlank() }?.let { TechFact(t("نسخه", "App"), it) },
            fact(r.models, RunReport.DIRECTOR)?.let { TechFact(t("کارگردان", "Director"), it) },
            fact(r.models, RunReport.EYES)?.let { TechFact(t("چشم", "Eyes"), it) },
            fact(r.models, RunReport.SPEECH)?.let { TechFact(t("گفتار", "Speech"), it) },
            fact(r.runtime, "llm")?.let { TechFact(t("موتور مدل", "Model engine"), it) },
            fact(r.runtime, "llm on CPU because")?.let { TechFact(t("چرا CPU", "Why CPU"), it) },
            fact(r.runtime, "speech")?.let { TechFact(t("موتور گفتار", "Speech engine"), it) },
        )
    }

    private fun metrics(r: RunReport, persian: Boolean): List<TechFact> {
        fun t(fa: String, en: String) = if (persian) fa else en
        val ok = r.calls.filter { it.attempt == r.attempt }
        return listOfNotNull(
            ok.takeIf { it.isNotEmpty() }?.let { calls ->
                val speed = r.tokensPerSecond()?.let { "${one(it)} tok/s" } ?: "-"
                val read = calls.mapNotNull { it.promptTokensPerSecond }.takeIf { it.isNotEmpty() }?.let { " · read ${one(it.average().toFloat())} tok/s" }.orEmpty()
                val seconds = one(calls.sumOf { it.seconds.toDouble() }.toFloat())
                TechFact(t("مدل زبانی", "Language model"), "$speed$read · ${calls.size} calls · ${calls.sumOf { it.generatedTokens }} tokens out · $seconds s")
            },
            r.speech?.let { TechFact(t("شنیدن", "Listening"), "RTF ${two(it.realTimeFactor)} · ${one(it.audioSeconds)} s sound in ${one(it.seconds)} s · ${it.threads} threads") },
            r.render?.let { TechFact(t("رندر", "Render"), "${one(it.fps)} fps · ${it.frames} frames ${it.width}×${it.height} in ${one(it.seconds)} s") },
            r.memory?.let { m ->
                val text = listOfNotNull(m.peakRssMb?.let { "peak $it MB" }, m.rssMb?.let { "now $it MB" }, m.heapPeakMb?.let { "JVM heap peak $it MB" }).joinToString(" · ")
                text.takeIf { it.isNotEmpty() }?.let { TechFact(t("حافظه", "Memory"), it) }
            },
        )
    }

    private fun problems(r: RunReport, persian: Boolean): List<String> {
        fun t(fa: String, en: String) = if (persian) fa else en
        return r.deaths.map { t("برنامه در «${it.step}» بسته شد (تلاش ${digits(it.attempt, persian)})", "The app ended during «${it.step}» (attempt ${it.attempt})") } +
            r.stalls.map { s ->
                t("${digits(s.minutes, persian)} دقیقه بدون پیشرفت در «${s.step}»", "${s.minutes} min without progress in «${s.step}»") +
                    (s.choice?.let { " → " + if (persian) CHOICES[it] ?: it else it } ?: "")
            } +
            r.errors.map { "${it.step}: ${it.message.lineSequence().first().take(ERROR)}" }
    }

    /** The models screen's line: the engine as the latest run measured it. */
    fun engine(record: EngineRecord): EngineSummary = EngineSummary(
        backend = record.backend, gpu = record.gpu, gpuLayers = record.gpuLayers, totalLayers = record.totalLayers,
        threads = record.threads, model = record.model, speeds = record.speeds, speechRealTime = record.speechRealTime,
        renderFps = record.renderFps, measuredAtMs = record.atMs,
    )

    private fun outcome(o: RunOutcome, persian: Boolean) = when (o) {
        RunOutcome.Running -> if (persian) "در حال ساخت" else "running"
        RunOutcome.Done -> if (persian) "ساخته شد" else "done"
        RunOutcome.Failed -> if (persian) "ناموفق" else "failed"
        RunOutcome.Cancelled -> if (persian) "متوقف شد" else "cancelled"
    }

    /** The engine's step names as the creator reads them (unknown ones as they are). */
    private fun stepName(step: String, persian: Boolean): String {
        if (!persian) return step
        return STEPS[step] ?: step
    }

    private val STEPS = mapOf(
        "start" to "شروع", "prepare" to "آماده‌سازی", "attachments" to "پیوست‌ها", "transcribe" to "شنیدن (whisper)",
        "direct" to "بارگذاری کارگردان", "listen" to "تحلیل گفتار", "understand" to "فهم", "research" to "جست‌وجو",
        "plan" to "برنامهٔ ادیت", "pictures" to "تصویرها", "compile" to "ساخت صحنه‌ها", "look" to "نگاه مدل",
        "jury" to "داوری", "sound" to "صدا و موسیقی", "render" to "رندر",
        // The explainer film's stages (audio-only input).
        "write" to "فیلم‌نامه و داوری فیلم", "draw" to "صحنه‌ها و تصویرها",
    )

    /** The creator's answers to the watchdog, as the runner notes them. */
    private val CHOICES = mapOf("kept waiting" to "صبر کرد", "lighter director" to "مدل کوچک‌تر", "cancelled" to "لغو")

    private fun digits(n: Int, persian: Boolean): String =
        if (persian) n.toString().map { c -> if (c.isDigit()) '۰' + (c - '0') else c }.joinToString("") else n.toString()

    private fun one(v: Float): String {
        val r = (v * 10).roundToInt()
        return "${r / 10}.${r % 10}"
    }

    private fun two(v: Float): String {
        val r = (v * 100).roundToInt()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }

    private const val ERROR = 160
}
