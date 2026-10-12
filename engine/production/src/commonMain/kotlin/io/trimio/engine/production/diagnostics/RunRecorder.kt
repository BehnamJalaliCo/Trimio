package io.trimio.engine.production.diagnostics

import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.ModelRuntime
import io.trimio.engine.llm.StopReason
import io.trimio.engine.llm.Usage
import io.trimio.engine.asr.SpeechRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The native engines as they loaded for a run: the build, the GPU and its share of the model, the threads. */
data class Runtime(
    val facts: List<Fact> = emptyList(),
    val backend: String? = null,
    val gpu: String? = null,
    val gpuLayers: Int? = null,
    val totalLayers: Int? = null,
    val threads: Int? = null,
) {
    companion object {
        /** What the language engine ([model], once loaded) and whisper ([speech], when it listened) reported about themselves. */
        fun of(model: ModelRuntime?, speech: SpeechRuntime?): Runtime {
            val facts = buildList {
                model?.let { m ->
                    val placed = m.gpu?.let { "$it · ${m.gpuLayers}/${m.totalLayers} layers on the GPU" } ?: "CPU only (${m.gpuLayers}/${m.totalLayers} layers on a GPU)"
                    add(Fact("llm", "${m.backend} · $placed · ${m.threads} threads · KV ${m.kvCache}"))
                    m.cpuReason?.let { add(Fact("llm on CPU because", it)) }
                    if (m.usage.calls > 0) add(Fact("llm calls", m.usage.summary()))
                    m.facts.filter { (k, _) -> k in RAW }.forEach { (k, v) -> add(Fact("llm.$k", v)) }
                }
                speech?.let { s ->
                    add(Fact("speech", s.summary()))
                    s.facts.filter { (k, _) -> k in RAW }.forEach { (k, v) -> add(Fact("speech.$k", v)) }
                }
            }
            return Runtime(
                facts, model?.backend ?: speech?.backend, model?.gpu ?: speech?.gpu, model?.gpuLayers, model?.totalLayers, model?.threads ?: speech?.threads,
            )
        }

        /** The engines' raw lines worth keeping: devices, placement, buffers. */
        private val RAW = setOf("device", "gpu", "model", "layers", "gpu_layers", "requested_gpu_layers", "gpu_coverage", "ctx", "batch", "flash", "log", "backend")
    }
}

/**
 * Records one run's [RunReport] as it happens and keeps it on disk ([Reports]) at every step, so
 * what a run did survives the run (and the process). Steps are recorded at their engine names
 * ("transcribe", "understand 12/48" as the step "understand" with the detail "12/48"); before
 * native-heavy work [native] writes the death marker, so a run the system killed in whisper,
 * llama.cpp or the encoder is found where it died when it starts again ([begin]).
 *
 * [session] identifies this process; [clock] is wall time in ms (tests pass a fake one).
 * [onChange] gets every new report (the run's state carries it to the screens); [onBeat] a
 * counter that moves while a model streams (at most every [BEAT_MS]), for the watchdog.
 */
class RunRecorder(
    val key: String,
    private val reports: Reports,
    private val session: String,
    private val clock: () -> Long = ::wallClock,
    private val memory: () -> MemoryUse? = { null },
    private val onChange: (RunReport) -> Unit = {},
    private val onBeat: (Long) -> Unit = {},
) {
    private val state = MutableStateFlow(RunReport(key))
    val report: StateFlow<RunReport> = state.asStateFlow()
    /** This recorder within the process: a run replaced by another of the same key never removes the new one's marker. */
    private val owner = session + "#" + kotlin.random.Random.nextInt(0, Int.MAX_VALUE).toString(36)
    private var marked: String? = null
    private var lastDetail = 0L
    private var beats = 0L
    private var lastBeat = 0L

    /** The step running now, with its detail ("understand 12/48"); "start" before the first. */
    val current: String
        get() = state.value.steps.lastOrNull { it.endMs == null && it.attempt == state.value.attempt }?.let { s -> listOfNotNull(s.name, s.detail).joinToString(" ") } ?: "start"

    /**
     * Starts an attempt on what earlier attempts of this run left: a marker from another process
     * is a death in its step. Returns the place to give up on when the run has died there
     * [Deaths.LIMIT] times (those deaths are then handled: a later retry gets fresh chances).
     */
    fun begin(app: String, device: List<Fact>): String? {
        val now = clock()
        val previous = reports.load(key)
        val died = Deaths.died(reports.marker(key), session)
        var deaths = previous?.deaths.orEmpty() + listOfNotNull(died?.let { Death(previous?.attempt ?: 0, it, now) })
        val giveUp = Deaths.giveUp(deaths)
        if (giveUp != null) deaths = deaths.map { if (Deaths.place(it.step) == giveUp) it.copy(counted = false) else it }
        val base = previous ?: RunReport(key, startedAtMs = now, attempt = 0)
        set(base.copy(app = app, device = device, attempt = base.attempt + 1, outcome = RunOutcome.Running, updatedAtMs = now, deaths = deaths, memory = memory()))
        mark(Deaths.inside("start", owner))
        return giveUp
    }

    /** The run moved to [name] ("understand 12/48"): a new step when its first word changes, else the open step's detail. */
    fun step(name: String) {
        val base = name.substringBefore(' ').trim()
        if (base.isEmpty()) return
        val detail = name.substringAfter(' ', "").trim().ifEmpty { null }
        val now = clock()
        val open = open()
        if (open != null && open.name == base) {
            if (detail == null || detail == open.detail || now - lastDetail < DETAIL_MS) return
            lastDetail = now
            set(state.value.let { r -> r.copy(steps = r.steps.dropLast(1) + open.copy(detail = detail)) }, persist = false)
            return
        }
        lastDetail = now
        set(state.value.let { r -> r.copy(steps = closed(r.steps, now) + StepTime(r.attempt, base, now, detail = detail, rssMb = memory()?.rssMb)) })
        mark(Deaths.inside(base, owner))
    }

    /** [block] is native-heavy (whisper, llama.cpp, the codecs): the marker says so while it runs. */
    suspend fun <T> native(step: String, block: suspend () -> T): T {
        mark(Deaths.entering(step, owner))
        try {
            return block()
        } finally {
            mark(Deaths.left(step, owner))
        }
    }

    fun models(facts: List<Fact>) = set(state.value.copy(models = facts))

    fun runtime(runtime: Runtime) = set(state.value.copy(runtime = runtime.facts))

    fun call(call: ModelCall) = set(state.value.let { it.copy(calls = it.calls + call) })

    fun speech(time: SpeechTime) = set(state.value.copy(speech = time))

    fun render(time: RenderTime) = set(state.value.copy(render = time))

    fun sitting(marks: List<JudgeMark>, findings: List<String>) =
        set(state.value.let { r -> r.copy(jury = r.jury + JuryRound(r.attempt, r.jury.count { it.attempt == r.attempt } + 1, marks, findings)) })

    fun error(e: Throwable, step: String = current) =
        set(state.value.let { r -> r.copy(errors = r.errors + RunError(r.attempt, clock(), step, e.message ?: e::class.simpleName.orEmpty(), e.stackTraceToString())) })

    fun stall(step: String, minutes: Int, choice: String? = null) =
        set(state.value.let { r -> r.copy(stalls = r.stalls + StallNote(r.attempt, step, clock(), minutes, choice)) })

    /** The creator answered the latest stall. */
    fun stallChoice(choice: String) = state.value.stalls.lastOrNull()?.let { last ->
        set(state.value.let { r -> r.copy(stalls = r.stalls.dropLast(1) + last.copy(choice = choice)) })
    }

    /** A model is streaming: the watchdog's heartbeat (throttled). */
    fun beat() {
        val now = clock()
        if (now - lastBeat < BEAT_MS) return
        lastBeat = now
        onBeat(++beats)
    }

    /** The attempt ended: open steps closed, memory read, the marker removed (no death to find). */
    fun finish(outcome: RunOutcome) {
        val now = clock()
        set(state.value.let { r -> r.copy(outcome = outcome, steps = closed(r.steps, now), memory = memory() ?: r.memory) })
        // Only this run's own marker goes (a newer run of the same key may have written its own since).
        if (reports.marker(key) == marked) reports.mark(key, null)
    }

    private fun mark(line: String) {
        marked = line
        reports.mark(key, line)
    }

    /** What the device's engines measured in this run, merged into the earlier record (speeds by model). */
    fun engine(runtime: Runtime, earlier: EngineRecord?): EngineRecord {
        val r = state.value
        val speeds = (earlier?.speeds.orEmpty() + r.calls.map { it.model }.distinct().mapNotNull { m -> r.tokensPerSecond(m)?.let { m to it } })
        return EngineRecord(
            atMs = clock(), backend = runtime.backend ?: earlier?.backend, gpu = runtime.gpu ?: earlier?.gpu,
            gpuLayers = runtime.gpuLayers ?: earlier?.gpuLayers, totalLayers = runtime.totalLayers ?: earlier?.totalLayers,
            threads = runtime.threads ?: earlier?.threads, model = r.director ?: earlier?.model, speeds = speeds,
            speechRealTime = r.speech?.realTimeFactor ?: earlier?.speechRealTime, renderFps = r.render?.fps ?: earlier?.renderFps,
        )
    }

    /** [model] measured into this report: every call timed, its pieces counted, the marker set while it runs. */
    fun instrument(model: LanguageModel): LanguageModel = model as? RecordingModel ?: RecordingModel(model, this)

    /** [jury] whose every sitting is recorded (scores and findings per judge). */
    fun instrument(jury: Jury): Jury = Jury(jury.judges.mapIndexed { i, j -> RecordingJudge(j, this, i, jury.judges.size) }, jury.budget)

    internal fun now(): Long = clock()

    internal val sittings = Sittings()

    private fun open(): StepTime? = state.value.let { r -> r.steps.lastOrNull()?.takeIf { it.attempt == r.attempt && it.endMs == null } }

    /** This attempt's open steps closed at [now]; an earlier attempt's open step stays open (it died there). */
    private fun closed(steps: List<StepTime>, now: Long) = steps.map { if (it.endMs == null && it.attempt == state.value.attempt) it.copy(endMs = now) else it }

    private fun set(report: RunReport, persist: Boolean = true) {
        val next = report.copy(updatedAtMs = clock())
        state.value = next
        if (persist) reports.save(next)
        onChange(next)
    }

    /** One panel's verdicts as the judges give them, sent on when the last judge has spoken. */
    internal inner class Sittings {
        private val marks = mutableListOf<JudgeMark>()
        private val findings = mutableListOf<String>()

        fun verdict(index: Int, size: Int, judge: String, verdict: Verdict?) {
            if (index == 0) {
                marks.clear()
                findings.clear()
            }
            marks += JudgeMark(judge, verdict?.score ?: -1f)
            verdict?.findings?.forEach { f -> findings += "$judge ${f.severity.name.lowercase()} at ${Jury.fmt(f.at)}s: ${f.what} → ${f.fix}" }
            if (index == size - 1) sitting(marks.toList(), findings.toList())
        }
    }

    companion object {
        const val BEAT_MS = 5_000L
        private const val DETAIL_MS = 2_000L
    }
}

/** Wall clock, epoch milliseconds. */
fun wallClock(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** A model whose calls are timed and counted into [recorder]; everything else is the model's own. */
internal class RecordingModel(private val inner: LanguageModel, private val recorder: RunRecorder) : LanguageModel by inner {
    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
        val step = recorder.current
        val start = recorder.now()
        var pieces = 0
        var first: Long? = null
        val prompt = request.system.length + request.messages.sumOf { it.text.length }
        val images = request.messages.sumOf { it.images.size }
        fun record(outcome: String, usage: Usage? = null) = recorder.call(
            ModelCall(
                recorder.report.value.attempt, inner.id, step, start, (recorder.now() - start) / MS, prompt,
                promptTokens = usage?.promptTokens, generatedTokens = usage?.generatedTokens ?: pieces, images = images, outcome = outcome,
                firstTokenSeconds = usage?.let { it.promptMs / MS } ?: first?.let { (it - start) / MS },
                reusedTokens = usage?.reusedTokens, promptMs = usage?.promptMs, generateMs = usage?.generateMs,
            ),
        )
        return try {
            recorder.native("llm $step") {
                inner.generate(request) { text ->
                    if (first == null) first = recorder.now()
                    pieces++
                    recorder.beat()
                    onText(text)
                }
            }.also { record(if (it.stopReason == StopReason.Cancelled) "cancelled" else "ok", it.usage) }
        } catch (e: CancellationException) {
            record("cancelled")
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            record((e as? LanguageModelException)?.kind?.name?.lowercase() ?: "error")
            recorder.error(e, "llm $step")
            throw e
        }
    }

    private companion object {
        const val MS = 1000f
    }
}

/** A judge whose verdicts are recorded into [recorder]'s sittings; [index] is its seat on the panel of [size]. */
internal class RecordingJudge(private val inner: Judge, private val recorder: RunRecorder, private val index: Int, private val size: Int) : Judge by inner {
    override fun score(c: Jury.Context): Verdict {
        val verdict = runCatching { inner.score(c) }
        recorder.sittings.verdict(index, size, inner.name, verdict.getOrNull())
        return verdict.getOrThrow()
    }
}
