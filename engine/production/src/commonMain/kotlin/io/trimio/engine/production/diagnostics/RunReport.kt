package io.trimio.engine.production.diagnostics

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.Instant

/** One named fact ("device" → "samsung SM-S948B"). */
@Serializable
data class Fact(val name: String, val value: String)

@Serializable
enum class RunOutcome { Running, Done, Failed, Cancelled }

/** One stage or sub-step of an attempt, wall clock (epoch ms). [detail] is its latest sub-progress ("48/48"). */
@Serializable
data class StepTime(
    val attempt: Int,
    val name: String,
    val startMs: Long,
    val endMs: Long? = null,
    val detail: String? = null,
    /** Resident memory when the step started, where the platform can read it. */
    val rssMb: Int? = null,
) {
    val seconds: Float? get() = endMs?.let { (it - startMs) / MS }
}

/** One request to a language model: what went in and out, and how fast. */
@Serializable
data class ModelCall(
    val attempt: Int,
    val model: String,
    /** The run's step when it was asked ("understand 12/48", "look", "pictures"). */
    val step: String,
    val startMs: Long,
    val seconds: Float,
    val promptChars: Int,
    /** Prompt tokens when the engine reports them; null otherwise. */
    val promptTokens: Int? = null,
    /** Generated tokens (llama.cpp streams one piece per token; a split character counts once). */
    val generatedTokens: Int,
    val images: Int = 0,
    /** "ok", "cancelled" or the failure's kind. */
    val outcome: String = "ok",
    /** Seconds until the first generated piece (prompt processing), when any came. */
    val firstTokenSeconds: Float? = null,
    /** Prompt tokens kept from the previous call (not read again), when the engine reports them. */
    val reusedTokens: Int? = null,
    /** The engine's own times for reading the prompt and generating, when it reports them. */
    val promptMs: Long? = null,
    val generateMs: Long? = null,
) {
    /** Generation speed: the engine's own generate time where measured, else after the first streamed piece. */
    val tokensPerSecond: Float
        get() {
            generateMs?.takeIf { it > 0 }?.let { return generatedTokens * MS / it }
            val decode = seconds - (firstTokenSeconds ?: 0f)
            return if (decode > 0f && generatedTokens > 1) (generatedTokens - 1) / decode else 0f
        }

    /** Prompt tokens read per second (reused ones cost nothing), where the engine measured it. */
    val promptTokensPerSecond: Float?
        get() = promptMs?.takeIf { it > 0 }?.let { ms -> promptTokens?.let { (it - (reusedTokens ?: 0)) * MS / ms } }
}

/** Whisper's pass: how much sound, how long it took ([realTimeFactor] above 1 is slower than real time). */
@Serializable
data class SpeechTime(val model: String?, val audioSeconds: Float, val seconds: Float, val threads: Int, val words: Int) {
    val realTimeFactor: Float get() = if (audioSeconds > 0f) seconds / audioSeconds else 0f
}

@Serializable
data class RenderTime(val frames: Int, val seconds: Float, val width: Int, val height: Int) {
    val fps: Float get() = if (seconds > 0f) frames / seconds else 0f
}

@Serializable
data class JudgeMark(val judge: String, val score: Float)

/** One sitting of the panel (a revision round, a variation, or the hearing of the mix), in the order they sat. */
@Serializable
data class JuryRound(val attempt: Int, val index: Int, val marks: List<JudgeMark>, val findings: List<String>) {
    val min: Float get() = marks.minOfOrNull { it.score } ?: 0f
    val mean: Float get() = if (marks.isEmpty()) 0f else marks.sumOf { it.score.toDouble() }.toFloat() / marks.size
}

/** Process memory: [peakRssMb] is the high-water mark the OS kept (VmHWM), [heapPeakMb] the JVM heap's where RSS is unreadable. */
@Serializable
data class MemoryUse(val rssMb: Int? = null, val peakRssMb: Int? = null, val heapPeakMb: Int? = null)

@Serializable
data class RunError(val attempt: Int, val atMs: Long, val step: String, val message: String, val stack: String)

/** The process ended (killed, crashed, closed) while the attempt was in [step]; found when the run started again. */
@Serializable
data class Death(
    val attempt: Int,
    val step: String,
    val detectedAtMs: Long,
    /** Counted towards giving up; false once a run already gave up on it (a retry starts with fresh chances). */
    val counted: Boolean = true,
)

/** The watchdog saw no progress for [minutes] in [step]; [choice] is what the creator did about it. */
@Serializable
data class StallNote(val attempt: Int, val step: String, val atMs: Long, val minutes: Int, val choice: String? = null)

/**
 * Everything one run (one request key) measured, across its attempts: the device, the models and
 * the native build that ran them, every stage and sub-step, every model call, whisper's real-time
 * factor, the render's frame rate, each sitting of the jury, memory, errors with their stacks,
 * process deaths and stalls. Persisted beside the run's checkpoints ([Reports]); [text] is the
 * file the creator shares.
 */
@Serializable
data class RunReport(
    val key: String,
    val format: Int = FORMAT,
    val app: String = "",
    val startedAtMs: Long = 0,
    val updatedAtMs: Long = 0,
    val outcome: RunOutcome = RunOutcome.Running,
    val attempt: Int = 1,
    val device: List<Fact> = emptyList(),
    val models: List<Fact> = emptyList(),
    val runtime: List<Fact> = emptyList(),
    val steps: List<StepTime> = emptyList(),
    val calls: List<ModelCall> = emptyList(),
    val speech: SpeechTime? = null,
    val render: RenderTime? = null,
    val jury: List<JuryRound> = emptyList(),
    val memory: MemoryUse? = null,
    val errors: List<RunError> = emptyList(),
    val deaths: List<Death> = emptyList(),
    val stalls: List<StallNote> = emptyList(),
) {
    /** The director model's id, when one directed. */
    val director: String? get() = models.firstOrNull { it.name == DIRECTOR }?.value?.substringBefore(' ')

    /** Generated tokens per second over every successful call of [model] (or all), generation time only. */
    fun tokensPerSecond(model: String? = null): Float? {
        val ok = calls.filter { it.outcome == "ok" && (model == null || it.model == model) && it.generatedTokens > 1 && it.tokensPerSecond > 0f }
        val tokens = ok.sumOf { it.generatedTokens }
        val seconds = ok.sumOf { (it.generatedTokens / it.tokensPerSecond).toDouble() }.toFloat()
        return if (tokens > 0 && seconds > 0f) tokens / seconds else null
    }

    /** The shareable text: plain, aligned, one section per kind of measurement. */
    fun text(): String = ReportText.of(this)

    companion object {
        const val FORMAT = 1
        const val DIRECTOR = "director"
        const val EYES = "eyes"
        const val SPEECH = "speech"
    }
}

private const val MS = 1000f

/** The report as a text file a person (or the developer they send it to) reads top to bottom. */
internal object ReportText {
    fun of(r: RunReport): String = buildString {
        appendLine("Trimio technical report")
        appendLine("run ${r.key} · attempt ${r.attempt} · ${r.outcome.name.lowercase()} · started ${stamp(r.startedAtMs)} · updated ${stamp(r.updatedAtMs)}")
        if (r.app.isNotBlank()) appendLine("app ${r.app}")
        facts("DEVICE", r.device)
        facts("MODELS", r.models)
        facts("ENGINE", r.runtime)
        steps(r)
        calls(r)
        r.speech?.let { s ->
            section("SPEECH")
            appendLine("  ${s.model ?: "whisper"}: ${f1(s.audioSeconds)} s of sound in ${f1(s.seconds)} s → real-time factor ${f2(s.realTimeFactor)} · ${s.threads} threads · ${s.words} words")
        }
        r.render?.let { v ->
            section("RENDER")
            appendLine("  ${v.frames} frames ${v.width}×${v.height} in ${f1(v.seconds)} s → ${f1(v.fps)} fps")
        }
        jury(r)
        r.memory?.let { m ->
            section("MEMORY")
            val parts = listOfNotNull(m.peakRssMb?.let { "peak RSS $it MB" }, m.rssMb?.let { "RSS now $it MB" }, m.heapPeakMb?.let { "JVM heap peak $it MB" })
            appendLine("  " + parts.joinToString(" · ").ifEmpty { "not readable here" })
        }
        if (r.deaths.isNotEmpty()) {
            section("PROCESS DEATHS")
            r.deaths.forEach { appendLine("  attempt ${it.attempt}: the app ended during «${it.step}» (found ${stamp(it.detectedAtMs)})" + if (it.counted) "" else " · already handled") }
        }
        if (r.stalls.isNotEmpty()) {
            section("STALLS")
            r.stalls.forEach { appendLine("  attempt ${it.attempt}: no progress for ${it.minutes} min in «${it.step}»" + (it.choice?.let { c -> " → $c" } ?: "")) }
        }
        if (r.errors.isNotEmpty()) {
            section("ERRORS")
            r.errors.forEach { e ->
                appendLine("  [attempt ${e.attempt} · ${e.step} · ${stamp(e.atMs)}] ${e.message}")
                e.stack.lineSequence().take(STACK_LINES).forEach { appendLine("    $it") }
            }
        }
    }

    private fun StringBuilder.section(name: String) {
        appendLine()
        appendLine(name)
    }

    private fun StringBuilder.facts(name: String, facts: List<Fact>) {
        if (facts.isEmpty()) return
        section(name)
        val width = facts.maxOf { it.name.length }
        facts.forEach { appendLine("  ${it.name.padEnd(width)}  ${it.value}") }
    }

    private fun StringBuilder.steps(r: RunReport) {
        if (r.steps.isEmpty()) return
        section("STAGES")
        r.steps.groupBy { it.attempt }.forEach { (attempt, steps) ->
            val origin = steps.first().startMs
            appendLine("  attempt $attempt")
            steps.forEach { s ->
                val took = s.seconds?.let { "${f1(it)} s" } ?: "unfinished"
                val extra = listOfNotNull(s.detail, s.rssMb?.let { "RSS $it MB" }).joinToString(" · ")
                appendLine("    +${f1((s.startMs - origin) / MS).padStart(STAGE_PAD)} s  ${s.name.padEnd(NAME_PAD)} ${took.padStart(STAGE_PAD + 2)}  $extra".trimEnd())
            }
        }
    }

    private fun StringBuilder.calls(r: RunReport) {
        if (r.calls.isEmpty()) return
        val out = r.calls.sumOf { it.generatedTokens }
        section("MODEL CALLS (${r.calls.size} calls, $out tokens out, ${r.tokensPerSecond()?.let { f1(it) } ?: "-"} tokens/s)")
        r.calls.forEach { c ->
            val read = c.promptTokensPerSecond?.let { " read at ${f1(it)} tok/s" }.orEmpty()
            val prompt = c.promptTokens?.let { "$it tok" + (c.reusedTokens?.takeIf { n -> n > 0 }?.let { n -> " ($n reused)" }.orEmpty()) + read } ?: "${c.promptChars} chars"
            val first = c.firstTokenSeconds?.let { " · first token ${f1(it)} s" }.orEmpty()
            val images = if (c.images > 0) " · ${c.images} images" else ""
            appendLine(
                "  #${c.attempt} ${c.step.padEnd(NAME_PAD)} ${c.model} · prompt $prompt$images · out ${c.generatedTokens} tok · " +
                    "${f1(c.seconds)} s$first · ${f1(c.tokensPerSecond)} tok/s · ${c.outcome}",
            )
        }
    }

    private fun StringBuilder.jury(r: RunReport) {
        if (r.jury.isEmpty()) return
        section("JURY (${r.jury.size} sittings)")
        r.jury.forEach { j ->
            appendLine("  #${j.attempt} sitting ${j.index}: min ${f1(j.min)} mean ${f1(j.mean)} — " + j.marks.joinToString(" ") { "${it.judge} ${f1(it.score)}" })
            j.findings.forEach { appendLine("      $it") }
        }
    }

    fun stamp(ms: Long): String = if (ms <= 0) "-" else Instant.fromEpochMilliseconds(ms).toString().substringBefore('.').replace('T', ' ') + " UTC"

    fun f1(v: Float): String {
        val r = (v * 10f).roundToInt()
        return "${r / 10}.${kotlin.math.abs(r % 10)}".let { if (r < 0 && r > -10) "-$it" else it }
    }

    private fun f2(v: Float): String {
        val r = (v * 100f).roundToInt()
        return "${r / 100}.${kotlin.math.abs(r % 100).toString().padStart(2, '0')}"
    }

    private const val STACK_LINES = 40
    private const val STAGE_PAD = 6
    private const val NAME_PAD = 16
}
