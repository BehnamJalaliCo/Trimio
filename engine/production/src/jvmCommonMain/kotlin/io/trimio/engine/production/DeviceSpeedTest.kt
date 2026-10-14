package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.whisper.SpeechThreads
import io.trimio.engine.asr.whisper.WhisperCppRecognizer
import io.trimio.engine.llm.local.FastCores
import io.trimio.engine.llm.local.LlamaProbe
import io.trimio.engine.llm.local.Placement
import io.trimio.engine.models.SpeedRow
import io.trimio.engine.models.SpeedTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * «سنجش سرعت این دستگاه» ([SpeedTest]): the [director] a build would run, timed on one core, half the cores, all
 * the fast cores (less one, and all) and the GPU when this build has one and memory allows; the memory's copy speed;
 * the [speech] model reading 15 s of sound at two thread counts. The fastest way for each model is kept beside it
 * ([Placement], [SpeechThreads]) and every build after uses it; the director's best processor thread count also
 * drives the picture engine ([SpeedProfile]). The work is announced as a percentage that only moves forward.
 */
internal class DeviceSpeedTest(
    private val director: () -> String?,
    private val speech: () -> String?,
    /** Where the chosen ways are kept for the builds and the settings screen; null keeps nothing. */
    private val profile: File? = null,
) : SpeedTest {
    override suspend fun run(onRow: (SpeedRow) -> Unit, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        val model = director()
        val voice = speech()
        val gpu = model != null && LlamaProbe.gpuBuild && Placement.gpuFits(File(model).length(), Placement.freeBytes())
        // Every step's expected time: the percentage follows the clock, re-estimated as each measurement lands.
        val call = model?.let { File(it).length() / GB * CALL_SECONDS_PER_GB } ?: 0.0
        val read = voice?.let { SPEECH_SECONDS * SPEECH_FACTOR + File(it).length() / GB * LOAD_SECONDS_PER_GB } ?: 0.0
        val threads = threadOptions()
        val plan = buildList {
            add(Step.Memory to MEMORY_SECONDS)
            if (model != null) {
                threads.forEach { t -> add(Step.Cpu(t) to call * threads.first() / t) }
                if (gpu) add(Step.Gpu to call)
                add(Step.Mtp to call)
            }
            if (voice != null) speechOptions().forEach { t -> add(Step.Speech(t) to read) }
        }
        val work = Work(onProgress, plan)
        work.run(Step.Memory) { onRow(memory()) }
        val chosen = ArrayList<SpeedRow>()
        var cpuThreads: Int? = null
        if (model == null) onRow(SpeedRow(SpeedRow.Kind.Note, "کارگردان", "نصب نیست")) else {
            cpuThreads = directorRows(model, gpu, work) { row -> onRow(row); if (row.best) chosen += row }
        }
        if (voice == null) onRow(SpeedRow(SpeedRow.Kind.Note, "Whisper", "نصب نیست")) else speechRows(voice, work) { row -> onRow(row); if (row.best) chosen += row }
        cpuThreads?.let { t -> chosen += SpeedRow(SpeedRow.Kind.Note, "تصویرساز و افزونه‌ها", "CPU · ${digits(t)} هسته", best = true).also(onRow) }
        profile?.let { SpeedProfile(chosen.map { SpeedProfile.Chosen(it.kind.name, it.label, it.value) }, cpuThreads).save(it) }
        work.finish()
    }

    override fun current(): List<SpeedRow> = profile?.let(SpeedProfile::load)?.chosen.orEmpty().map { c ->
        SpeedRow(runCatching { SpeedRow.Kind.valueOf(c.kind) }.getOrDefault(SpeedRow.Kind.Note), c.label, c.value, best = true)
    }

    /**
     * The processor thread counts timed, the most first: all the fast cores, one less (left to the screen), half the
     * cores. One core alone is timed only on a device with two or fewer: it is never the fastest and took the longest.
     */
    private fun threadOptions(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val fast = FastCores.count() ?: cores
        val single = if (cores <= 2) listOf(1) else emptyList()
        return (listOf(fast, (fast - 1).coerceAtLeast(1), (cores / 2).coerceAtLeast(1)) + single).distinct().sortedDescending()
    }

    private fun speechOptions(): List<Int> = threadOptions().first().let { fast -> listOf(fast, (fast - 1).coerceAtLeast(1)).distinct() }

    /** The director every way; returns the processor thread count that read and wrote fastest (null when none ran). */
    private suspend fun directorRows(model: String, gpu: Boolean, work: Work, onRow: (SpeedRow) -> Unit): Int? {
        val probes = ArrayList<Pair<Int, Placement.Probe>>()
        for (t in threadOptions()) {
            work.run(Step.Cpu(t)) { LlamaProbe.run(model, t, 0) }?.let { p -> probes += t to p; onRow(row("CPU · ${digits(t)} هسته", p)) }
        }
        val bestCpu = probes.minByOrNull { it.second.callSeconds }
        if (gpu) {
            val t = bestCpu?.first ?: threadOptions().first()
            work.run(Step.Gpu) { LlamaProbe.run(model, t, GPU) }?.takeIf { it.onGpu }?.let { p -> probes += t to p; onRow(row("کارت گرافیک", p)) }
        } else if (LlamaProbe.gpuBuild) {
            onRow(SpeedRow(SpeedRow.Kind.Note, "کارت گرافیک", "حافظهٔ آزاد کافی نیست"))
        }
        val cpu = bestCpu?.second
        val onGpu = probes.map { it.second }.firstOrNull { it.onGpu }
        val choice = Placement.choose(onGpu, cpu)
        val threads = if (choice.gpuLayers != 0) probes.first { it.second.onGpu }.first else bestCpu?.first ?: return null.also { work.skip(Step.Mtp) }
        val best = if (choice.gpuLayers != 0) onGpu!! else cpu!!
        // The model's own MTP head (when it has one) guessing tokens ahead: kept only when it writes clearly faster.
        val mtp = work.run(Step.Mtp) { LlamaProbe.run(model, threads, choice.gpuLayers, MTP_DRAFT) }?.takeIf { it.draft > 0 }
        mtp?.let { onRow(row("پیش‌بینی چندتوکنی (MTP)", it)) }
        val draft = mtp?.takeIf { it.writePerSecond > best.writePerSecond * MTP_GAIN }?.draft
        Placement.keep(model, Placement.build(), choice.copy(line = "speed test: " + choice.line.removePrefix("measured: ") + (draft?.let { " · mtp $it" } ?: ""), threads = threads, draft = draft))
        val chosen = mtp?.takeIf { draft != null } ?: best
        val way = (if (best.onGpu) "کارت گرافیک" else "CPU · ${digits(threads)} هسته") + (if (draft != null) " · MTP" else "")
        onRow(SpeedRow(SpeedRow.Kind.Director, "کارگردان در ساخت", "$way · ${rates(chosen)}", best = true))
        return bestCpu?.first
    }

    private suspend fun speechRows(model: String, work: Work, onRow: (SpeedRow) -> Unit) {
        // Quiet noise: the encoder works on a full window whatever is said, which is what the test times.
        val rng = Random(1)
        val sound = PcmAudio(FloatArray(SPEECH_SECONDS * PcmAudio.WHISPER_RATE) { (rng.nextFloat() - 0.5f) * NOISE }, PcmAudio.WHISPER_RATE)
        val timed = speechOptions().mapNotNull { t ->
            work.run(Step.Speech(t)) {
                runCatching {
                    WhisperCppRecognizer(model).use { w ->
                        SpeechThreads.keep(model, t)
                        val start = System.nanoTime()
                        w.transcribe(sound, RecognitionOptions(Language.Persian, threads = t))
                        t to (System.nanoTime() - start) / NANOS
                    }
                }.getOrNull()
            }?.also { (t, s) -> onRow(SpeedRow(SpeedRow.Kind.Speech, "Whisper · ${digits(t)} هسته", "${one(s / SPEECH_SECONDS)} برابر زمان صدا")) }
        }
        val best = timed.minByOrNull { it.second } ?: return
        SpeechThreads.keep(model, best.first)
        onRow(SpeedRow(SpeedRow.Kind.Speech, "گفتار در ساخت", "Whisper · ${digits(best.first)} هسته", best = true))
    }

    /** One timed measurement of the test, for the percentage's clock. */
    private sealed interface Step {
        data object Memory : Step
        data class Cpu(val threads: Int) : Step
        data object Gpu : Step
        data object Mtp : Step
        data class Speech(val threads: Int) : Step
    }

    /**
     * The percentage as time: the seconds spent over the seconds spent plus those still expected. Every step has an
     * expected time ([plan]); when one ends, the steps like it are expected to take what it took (a director call on
     * fewer threads proportionally longer), so the number moves evenly, forward only, and lands on 100 at the end.
     */
    private class Work(private val onProgress: (Float) -> Unit, plan: List<Pair<Step, Double>>) {
        private val expected = LinkedHashMap<Step, Double>().apply { plan.forEach { (k, v) -> put(k, v) } }
        private var spent = 0.0
        private var shown = 0f

        suspend fun <T> run(step: Step, call: suspend () -> T): T = coroutineScope {
            val start = System.nanoTime()
            val tick = launch {
                while (isActive) {
                    show(step, (System.nanoTime() - start) / NANOS)
                    delay(TICK_MS)
                }
            }
            try {
                call()
            } finally {
                tick.cancel()
                end(step, (System.nanoTime() - start) / NANOS)
            }
        }

        @Synchronized
        private fun end(step: Step, took: Double) {
            learn(step, took)
            expected.remove(step)
            spent += took
            show(null, 0.0)
        }

        /** A planned step that will not run (no director answer to draw the MTP call from). */
        @Synchronized
        fun skip(step: Step) {
            expected.remove(step)
        }

        @Synchronized
        fun finish() {
            if (shown < 1f) {
                shown = 1f
                onProgress(1f)
            }
        }

        /** The fastest director call so far: the GPU and MTP calls are expected to run about as long. */
        private var fastestCall: Double? = null

        /** What [step] took says what the ones like it will take. */
        private fun learn(step: Step, took: Double) {
            when (step) {
                is Step.Cpu -> expected.keys.filterIsInstance<Step.Cpu>().forEach { o -> expected[o] = took * step.threads / o.threads }
                is Step.Speech -> expected.keys.filterIsInstance<Step.Speech>().forEach { o -> expected[o] = took * step.threads / o.threads }
                else -> Unit
            }
            if (step is Step.Cpu || step is Step.Gpu) {
                val fastest = minOf(took, fastestCall ?: took).also { fastestCall = it }
                listOf(Step.Gpu, Step.Mtp).filter { it in expected && it != step }.forEach { expected[it] = fastest }
            }
        }

        @Synchronized
        private fun show(current: Step?, elapsed: Double) {
            // The running step never looks done: past its estimate, its estimate grows with it.
            val own = current?.let { maxOf(expected[it] ?: elapsed, elapsed / CAP) } ?: 0.0
            val rest = expected.entries.filter { it.key != current }.sumOf { it.value }
            val total = spent + own + rest
            if (total <= 0.0) return
            val p = ((spent + elapsed) / total).toFloat().coerceIn(0f, LAST)
            if (p > shown) {
                shown = p
                onProgress(p)
            }
        }
    }

    /** Copy speed of a 64 MB block, the bound on how fast a model writes. */
    private fun memory(): SpeedRow {
        val a = LongArray(MEMORY_LONGS) { it.toLong() }
        val b = LongArray(MEMORY_LONGS)
        System.arraycopy(a, 0, b, 0, a.size)
        val start = System.nanoTime()
        repeat(MEMORY_PASSES) { System.arraycopy(if (it % 2 == 0) a else b, 0, if (it % 2 == 0) b else a, 0, a.size) }
        val seconds = (System.nanoTime() - start) / NANOS
        val gbs = MEMORY_PASSES * a.size * Long.SIZE_BYTES * 2.0 / seconds / GB
        return SpeedRow(SpeedRow.Kind.Memory, "حافظه (رم)", "${one(gbs)} گیگابایت بر ثانیه")
    }

    private fun row(label: String, p: Placement.Probe) = SpeedRow(SpeedRow.Kind.Director, label, rates(p))

    private fun rates(p: Placement.Probe) = "خواندن ${one(p.readPerSecond)} · نوشتن ${one(p.writePerSecond)} توکن/ثانیه"

    private fun one(v: Double): String = digits(((v * 10).toLong() / 10.0).toString().removeSuffix(".0"))

    private fun digits(v: Any): String = v.toString().map { if (it in '0'..'9') '۰' + (it - '0') else if (it == '.') '٫' else it }.joinToString("")

    private companion object {
        const val GPU = -1
        const val MTP_DRAFT = 3
        const val MTP_GAIN = 1.1
        const val SPEECH_SECONDS = 15
        const val NOISE = 0.002f
        const val NANOS = 1e9
        const val GB = 1e9
        const val MEMORY_LONGS = 8 * 1024 * 1024
        const val MEMORY_PASSES = 8

        /** First guesses, until a measurement says better: a director call per GB of model on all the fast cores, a Whisper read. */
        const val CALL_SECONDS_PER_GB = 25.0
        const val SPEECH_FACTOR = 1.5
        const val LOAD_SECONDS_PER_GB = 4.0
        const val MEMORY_SECONDS = 0.5

        /** A running step shows at most this share of its estimate; the percentage holds below 100 until the end. */
        const val CAP = 0.97
        const val LAST = 0.995f
        const val TICK_MS = 100L
    }
}
