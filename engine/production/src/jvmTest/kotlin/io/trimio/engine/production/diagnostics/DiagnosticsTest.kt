package io.trimio.engine.production.diagnostics

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.StopReason
import io.trimio.engine.llm.Usage
import io.trimio.engine.production.Checkpoints
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The technical report: kept on disk beside the run's checkpoints and found again; the death
 * marker read across processes (a fake session per "process"); steps, model calls and the jury's
 * sittings timed on a fake clock.
 */
class DiagnosticsTest {
    private val dir = Files.createTempDirectory("diagnostics-test").toFile()
    private val reports = Reports(Path(dir.path))
    private var now = 1_000_000L
    private val clock = { now }

    private fun recorder(session: String, key: String = "p1-7") = RunRecorder(key, reports, session, clock)

    @Test
    fun reportsPersistAndGoWithTheirProject() {
        val r = recorder("A")
        r.begin("Trimio 1.0.14", listOf(Fact("device", "test phone")))
        r.step("transcribe")
        now += 4_000
        r.speech(SpeechTime("whisper-large-v3", audioSeconds = 120f, seconds = 60f, threads = 6, words = 300))
        r.finish(RunOutcome.Done)

        // A new process reads what the run left: the report is on disk, the marker is gone (a clean end).
        val again = Reports(Path(dir.path))
        val loaded = assertNotNull(again.load("p1-7"))
        assertEquals(RunOutcome.Done, loaded.outcome)
        assertEquals("Trimio 1.0.14", loaded.app)
        assertEquals(4f, loaded.steps.single().seconds)
        assertEquals(0.5f, loaded.speech?.realTimeFactor)
        assertNull(again.marker("p1-7"))

        reports.saveEngine(EngineRecord(atMs = 1, backend = "vulkan", gpu = "RTX 3050 Ti", gpuLayers = 20, totalLayers = 33, threads = 6, speeds = mapOf("qwen" to 9.5f)))
        assertEquals(20, Reports(Path(dir.path)).engine()?.gpuLayers)

        // Deleting the project's checkpoints takes the report and the marker with them.
        reports.mark("p1-7", "in plan@A")
        Checkpoints(Path(dir.path)).delete("p1-7")
        assertNull(reports.load("p1-7"))
        assertNull(reports.marker("p1-7"))
    }

    @Test
    fun markersReadAcrossProcesses() {
        assertNull(Deaths.died(null, "A"))
        assertNull(Deaths.died("entering transcribe@A#x1", "A"), "this process's own marker is no death")
        assertEquals("transcribe", Deaths.died("entering transcribe@A#x1", "B"))
        assertEquals("after llm understand 3/48", Deaths.died("left llm understand 3/48@A#x1", "B"))
        assertEquals("plan", Deaths.died("in plan@A#x1", "B"))
        assertEquals("llm understand", Deaths.place("llm understand 12/48"))
        assertEquals(Deaths.place("llm understand 3/48"), Deaths.place("llm understand 45/48"))

        val once = listOf(Death(1, "llm understand 3/48", 0))
        assertNull(Deaths.giveUp(once))
        assertNull(Deaths.giveUp(once + Death(2, "render", 0)), "deaths in different steps do not add up")
        assertEquals("llm understand", Deaths.giveUp(once + Death(2, "llm understand 6/48", 0)))
        assertNull(Deaths.giveUp(once.map { it.copy(counted = false) } + Death(2, "llm understand 6/48", 0)), "handled deaths do not count")
    }

    @Test
    fun aRunThatDiesTwiceInOneStepGivesUpOnce() = runBlocking {
        // Process A dies inside the model's call.
        val a = recorder("A")
        assertNull(a.begin("app", emptyList()))
        a.step("understand 3/48")
        assertFailsWith<IllegalStateException> {
            a.native("llm understand 3/48") {
                assertTrue(reports.marker("p1-7")!!.startsWith("entering llm understand 3/48@A"), "the marker is written before native work")
                error("killed")
            }
        }
        // The kill came inside the call: put the marker back as a kill would have left it.
        reports.mark("p1-7", "entering llm understand 3/48@A#k")

        // Process B restarts the run: one death, carry on.
        val b = recorder("B")
        assertNull(b.begin("app", emptyList()))
        assertEquals(listOf("llm understand 3/48"), b.report.value.deaths.map { it.step })
        assertEquals(2, b.report.value.attempt)
        b.step("understand 9/48")
        b.native("llm understand 9/48") { reports.mark("p1-7", "entering llm understand 9/48@B#k") }
        reports.mark("p1-7", "entering llm understand 9/48@B#k")

        // Process C: the second death in the same place: give up, and mark those deaths handled.
        val c = recorder("C")
        assertEquals("llm understand", c.begin("app", emptyList()))
        assertTrue(c.report.value.deaths.none { it.counted })
        c.finish(RunOutcome.Failed)

        // The creator retries (process D): fresh chances.
        val d = recorder("D")
        assertNull(d.begin("app", emptyList()))
        assertEquals(4, d.report.value.attempt)
        assertTrue(d.report.value.text().contains("PROCESS DEATHS"))
    }

    @Test
    fun aReplacedRunNeverRemovesTheNewRunsMarker() {
        val old = recorder("A")
        old.begin("app", emptyList())
        val new = recorder("A")
        new.begin("app", emptyList())
        new.step("transcribe")
        old.finish(RunOutcome.Cancelled)
        assertNotNull(reports.marker("p1-7"), "the new run's marker stays")
        new.finish(RunOutcome.Done)
        assertNull(reports.marker("p1-7"))
    }

    @Test
    fun stepsAreTimedWithTheirDetail() {
        val r = recorder("A")
        r.begin("app", emptyList())
        r.step("listen")
        now += 1_500
        r.step("understand 3/48")
        now += 3_000
        r.step("understand 48/48")
        now += 500
        r.step("plan")
        now += 2_000
        r.finish(RunOutcome.Done)
        val steps = r.report.value.steps
        assertEquals(listOf("listen", "understand", "plan"), steps.map { it.name })
        assertEquals(listOf(1.5f, 3.5f, 2f), steps.map { it.seconds })
        assertEquals("48/48", steps[1].detail)
    }

    @Test
    fun modelCallsAreTimedAndCounted() = runBlocking {
        val r = recorder("A")
        r.begin("app", emptyList())
        r.step("understand 3/48")
        var beats = 0
        val streaming = object : LanguageModel {
            override val id = "qwen-test"
            override val isLocal = true
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
                repeat(11) {
                    now += 100
                    onText("x")
                }
                return Generation("x".repeat(11), StopReason.EndTurn, id)
            }
        }
        val model = RunRecorder("p1-7", reports, "A", clock, onBeat = { beats++ }).also { it.begin("app", emptyList()) }.instrument(streaming)
        model.generate(GenerationRequest("system", emptyList()))
        val call = assertNotNull(reports.load("p1-7")?.calls?.single())
        assertEquals(11, call.generatedTokens)
        assertEquals(1.1f, call.seconds, 1e-4f)
        assertEquals(10f, call.tokensPerSecond, 1e-3f, "ten tokens after the first in one second")
        assertTrue(beats >= 1, "a streaming model is a heartbeat")

        // The engine's own counts win where it reports them.
        val measured = object : LanguageModel {
            override val id = "qwen-test"
            override val isLocal = true
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit) =
                Generation("{}", StopReason.EndTurn, id, Usage(promptTokens = 2_000, reusedTokens = 1_500, generatedTokens = 120, promptMs = 5_000, generateMs = 12_000))
        }
        r.instrument(measured).generate(GenerationRequest("system", emptyList()))
        val counted = r.report.value.calls.last()
        assertEquals(2_000, counted.promptTokens)
        assertEquals(10f, counted.tokensPerSecond, 1e-3f)
        assertEquals(100f, counted.promptTokensPerSecond!!, 1e-3f, "500 tokens read in 5 s (1500 reused)")

        // A failing call is recorded with its error and stack, and still fails.
        val failing = object : LanguageModel {
            override val id = "broken"
            override val isLocal = true
            override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation =
                throw LanguageModelException(LanguageModelException.Kind.Unavailable, "could not load")
        }
        assertFailsWith<LanguageModelException> { r.instrument(failing).generate(GenerationRequest("s", emptyList())) }
        assertEquals("unavailable", r.report.value.calls.last().outcome)
        assertTrue(r.report.value.errors.single().stack.contains("LanguageModelException"))
    }

    @Test
    fun everySittingOfTheJuryIsRecorded() {
        val r = recorder("A")
        r.begin("app", emptyList())
        class Fixed(override val name: String, private val findings: List<Finding>) : Judge {
            override val dimension = name
            override fun score(c: Jury.Context): Verdict = Verdict(name, name, 10f - findings.sumOf { it.severity.cost.toDouble() }.toFloat(), findings)
        }
        val jury = r.instrument(Jury(listOf(Fixed("hook", emptyList()), Fixed("spelling", listOf(Finding(3.2f, Severity.Major, "«بیتکوین»", "«بیت‌کوین»")))), Jury.Budget(0, 0)))
        // Two sittings of the panel, as Jury.deliberate calls its judges in order.
        val context = dummy()
        repeat(2) { jury.judges.forEach { j -> j.score(context) } }
        val sittings = r.report.value.jury
        assertEquals(2, sittings.size)
        assertEquals(listOf(10f, 9f), sittings.first().marks.map { it.score })
        assertTrue(sittings.first().findings.single().startsWith("spelling major at 3.2s"))
    }

    /** A context nobody reads (these judges are fixed): allocated without its many fields. */
    @Suppress("UNCHECKED_CAST")
    private fun dummy(): Jury.Context = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let { f ->
        f.isAccessible = true
        (f.get(null) as sun.misc.Unsafe).allocateInstance(Jury.Context::class.java) as Jury.Context
    }

    @Test
    fun theTextNamesEverySection() {
        val r = recorder("A")
        r.begin("Trimio 1.0.14", listOf(Fact("device", "samsung SM-S948B")))
        r.models(listOf(Fact(RunReport.DIRECTOR, "qwen3.5-9b (on device)")))
        r.runtime(Runtime(listOf(Fact("llm", "opencl · Adreno 830 · 33/33 layers on the GPU · 6 threads"))))
        r.step("render 10/300")
        r.render(RenderTime(300, 60f, 1080, 1920))
        r.sitting(listOf(JudgeMark("hook", 10f)), emptyList())
        r.error(IllegalStateException("boom"))
        r.stall("understand 3/48", 6)
        r.stallChoice("kept waiting")
        val text = r.report.value.text()
        listOf("DEVICE", "MODELS", "ENGINE", "STAGES", "RENDER", "5.0 fps", "JURY", "ERRORS", "boom", "STALLS", "kept waiting").forEach {
            assertTrue(it in text, "missing $it in:\n$text")
        }
    }
}
