package io.trimio.engine.production.diagnostics

import io.trimio.engine.models.DeviceTier
import io.trimio.engine.production.AppProductionRunner
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.DirectorChoice
import io.trimio.engine.production.Edit
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionState
import io.trimio.engine.production.Stage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The watchdog's timing on virtual time: silent past the device's limit → a stall; progress or "keep waiting" clear it. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogTest {
    private val minute = 60_000L

    @Test
    fun limitsFollowTheDevice() {
        assertEquals(4 * minute, Watchdog.limitFor(null))
        assertEquals(8 * minute, Watchdog.limitFor(DeviceTier.Standard))
        assertEquals(6 * minute, Watchdog.limitFor(DeviceTier.High))
        assertTrue(Watchdog.limitFor(DeviceTier.Ultra) < Watchdog.limitFor(DeviceTier.High))
    }

    @Test
    fun aPureWatchdogOnAFakeClock() {
        var now = 0L
        val dog = Watchdog(5 * minute) { now }
        dog.saw("understand 1/48")
        now += 4 * minute
        assertNull(dog.stalled())
        now += minute
        assertEquals(5 * minute, dog.stalled())
        // Progress clears it.
        dog.saw("understand 4/48")
        assertNull(dog.stalled())
        // The same report again is no progress.
        now += 5 * minute
        assertTrue(dog.saw("understand 4/48").not())
        assertNotNull(dog.stalled())
        // Keep waiting: twice as long before it speaks again.
        dog.keepWaiting()
        now += 9 * minute
        assertNull(dog.stalled())
        now += minute
        assertNotNull(dog.stalled())
    }

    /** An editor whose run reports [states] and then goes silent (a model stuck in prompt processing). */
    private class Silent(private val limit: Long, private val states: MutableStateFlow<ProductionState?>) : AutoEditor {
        val asked = mutableListOf<ProductionRequest>()
        val notes = mutableListOf<String>()

        override fun produce(request: ProductionRequest): Flow<ProductionState> = flow {
            asked += request
            states.takeWhile { true }.collect { s -> if (s != null) emit(s.copy(key = request.key)) }
            awaitCancellation()
        }

        override fun vary(edit: Edit, seed: Long): Flow<ProductionState> = error("unused")

        override fun revise(edit: Edit, feedback: String): Flow<ProductionState> = error("unused")

        override fun stallLimitMs(): Long = limit

        override suspend fun lighter(current: String?): DirectorChoice = DirectorChoice.Local("qwen3.5-4b-q4km")

        override fun stalled(key: String, step: String, minutes: Int, choice: String?) {
            notes += listOfNotNull(step, minutes.toString(), choice).joinToString(" ")
        }
    }

    private fun TestScope.runner(editor: AutoEditor) =
        AppProductionRunner(editor, backgroundScope, now = { testScheduler.currentTime }, tickMs = 15_000L)

    @Test
    fun aSilentRunStallsAndTheCreatorChooses() = runTest {
        val states = MutableStateFlow<ProductionState?>(ProductionState("k", stage = Stage.Direct, step = "understand 3/48", progress = 0.4f))
        val editor = Silent(5 * minute, states)
        val runner = runner(editor)
        val request = ProductionRequest("clip.mp4", prompt = "brief", id = "k")
        runner.start(request)
        runCurrent()
        advanceTimeBy(4 * minute)
        assertNull(runner.active.value?.stall, "within the limit")
        advanceTimeBy(minute + 15_000L)
        val stall = assertNotNull(runner.active.value?.stall, "silent past the limit")
        assertEquals("understand 3/48", stall.step)
        assertEquals(5, stall.minutes)
        assertEquals("understand 3/48 5", editor.notes.single())

        // Keep waiting: the stall clears, and comes back only after twice the limit.
        runner.keepWaiting()
        assertNull(runner.active.value?.stall)
        advanceTimeBy(9 * minute)
        assertNull(runner.active.value?.stall)
        advanceTimeBy(minute + 15_000L)
        assertNotNull(runner.active.value?.stall)

        // Progress (a model streaming text) clears it at once.
        states.value = states.value!!.copy(heartbeat = 1)
        runCurrent()
        assertNull(runner.active.value?.stall)

        // A smaller model: the same order again with the editor's lighter director.
        states.value = states.value!!.copy(heartbeat = 2)
        advanceTimeBy(6 * minute)
        assertNotNull(runner.active.value?.stall)
        runner.lighter()
        runCurrent()
        assertEquals(DirectorChoice.Local("qwen3.5-4b-q4km"), editor.asked.last().director)
        assertEquals(request.key, editor.asked.last().key, "the same run key: its words and reading carry over")
        assertTrue(editor.notes.any { it.endsWith("lighter director") })
        runner.cancel()
    }

    @Test
    fun aMovingRunNeverStalls() = runTest {
        val states = MutableStateFlow<ProductionState?>(ProductionState("k", stage = Stage.Render, step = "render 0/900"))
        val runner = runner(Silent(4 * minute, states))
        runner.start(ProductionRequest("clip.mp4", prompt = "brief", id = "k"))
        repeat(30) { i ->
            advanceTimeBy(minute)
            states.value = states.value!!.copy(step = "render ${i * 30}/900")
        }
        runCurrent()
        assertNull(runner.active.value?.stall)
        runner.cancel()
    }
}
