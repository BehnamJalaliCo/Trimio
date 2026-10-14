package io.trimio.engine.production

import io.trimio.engine.asr.whisper.SpeechThreads
import io.trimio.engine.llm.local.FastCores
import io.trimio.engine.llm.local.Placement
import io.trimio.engine.models.SpeedRow
import io.trimio.engine.models.SpeedTest
import io.trimio.engine.production.bench.BenchResult
import io.trimio.engine.production.bench.BenchRunner
import io.trimio.engine.production.bench.BenchStep
import io.trimio.engine.production.bench.LocalBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «سنجش سرعت این دستگاه» ([SpeedTest]), each engine loaded once per placement and every measurement warmed up, each in its
 * own process on a phone ([bench]) so a GPU driver that crashes marks that way unusable instead of closing the app:
 *  - the [director] on the processor at all the fast cores, one less and half (one load, the better of two passes each);
 *    then on the GPU with all the layers the free memory holds and half of them (the rest on the processor), and with its
 *    MTP head when it has one;
 *  - the [speech] model at two thread counts (one load);
 *  - the [picture] engine on the processor, with its diffusion model (and decoder) on the GPU and the text encoder on the
 *    processor, and wholly on the GPU when it fits.
 * The fastest way of each is kept ([Placement], [SpeechThreads], [SpeedProfile]) and every build after uses it; the
 * percentage follows the clock, only forward.
 */
internal class DeviceSpeedTest(
    private val director: () -> String?,
    private val speech: () -> String?,
    /** The installed picture model's files (one, or the diffusion model, text encoder and VAE); null when none is. */
    private val picture: () -> List<String>? = { null },
    /** Where the chosen ways are kept for the builds and the settings screen; null keeps nothing. */
    private val profile: File? = null,
    private val bench: BenchRunner = LocalBench,
    private val freeBytes: () -> Long? = Placement::freeBytes,
) : SpeedTest {
    override suspend fun run(onRow: (SpeedRow) -> Unit, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        val model = director()
        val voice = speech()
        val pictureFiles = picture()
        val work = Work(onProgress, plan(model, voice, pictureFiles), threadOptions().size)
        try {
            measure(model, voice, pictureFiles, work, onRow)
        } finally {
            (bench as? AutoCloseable)?.close()
        }
        work.finish()
    }

    private suspend fun measure(model: String?, voice: String?, pictureFiles: List<String>?, work: Work, onRow: (SpeedRow) -> Unit) {
        work.run(Step.Memory) { onRow(memory()) }
        val chosen = ArrayList<SpeedRow>()
        val keep = { row: SpeedRow -> onRow(row); if (row.best) chosen += row }
        val cpuThreads = if (model == null) null.also { onRow(note("کارگردان", "نصب نیست")) } else directorRows(model, work, keep)
        if (voice == null) onRow(note("Whisper", "نصب نیست")) else speechRows(voice, work, keep)
        val placed = pictureFiles?.let { pictureRows(it, cpuThreads ?: threadOptions().first(), work, keep) }
        profile?.let { SpeedProfile(chosen.map { SpeedProfile.Chosen(it.kind.name, it.label, it.value) }, cpuThreads, placed).save(it) }
    }

    override fun current(): List<SpeedRow> = profile?.let(SpeedProfile::load)?.chosen.orEmpty().map { c ->
        SpeedRow(runCatching { SpeedRow.Kind.valueOf(c.kind) }.getOrDefault(SpeedRow.Kind.Note), c.label, c.value, best = true)
    }

    /** Every step's first expected time; the measured ones correct the rest as they land. */
    private fun plan(model: String?, voice: String?, picture: List<String>?): List<Pair<Step, Double>> = buildList {
        add(Step.Memory to MEMORY_SECONDS)
        model?.let { m ->
            val gb = File(m).length() / GB
            val pass = gb * PASS_SECONDS_PER_GB
            val load = gb * LOAD_SECONDS_PER_GB
            add(Step.Cpu to load + pass * (1 + threadOptions().size * BenchStep.PASSES))
            GPU_SHARES.forEach { share -> add(Step.Gpu(share) to load + pass * (1 + BenchStep.PASSES)) }
            add(Step.Mtp to load + pass * (1 + BenchStep.PASSES))
        }
        voice?.let { v -> add(Step.Speech to File(v).length() / GB * LOAD_SECONDS_PER_GB + SPEECH_PASS_SECONDS * (1 + speechOptions().size)) }
        picture?.let { files ->
            val gb = files.sumOf { File(it).length() } / GB
            add(Step.Devices to DEVICES_SECONDS)
            // A tiny picture then a small one of one step after the load; on the processor some seconds a gigabyte of model.
            PictureWay.entries.forEach { way -> add(Step.Picture(way) to gb * (LOAD_SECONDS_PER_GB + PICTURE_SECONDS_PER_GB)) }
        }
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

    /** The director every way; returns the processor thread count that ran it fastest (null when none ran). */
    private suspend fun directorRows(model: String, work: Work, onRow: (SpeedRow) -> Unit): Int? {
        val started = System.nanoTime()
        val cpu = work.run(Step.Cpu) { bench.run(BenchStep.Director(model, threadOptions(), 0), null) }
        // What one thread count took on the processor bounds the GPU's ways: far slower is stopped, not waited for.
        val gpuLimit = limit((System.nanoTime() - started) / NANOS * (1 + BenchStep.PASSES) / (1 + threadOptions().size * BenchStep.PASSES))
        if (cpu.ok) cpu.rates.forEach { r -> onRow(SpeedRow(SpeedRow.Kind.Director, "CPU · ${digits(r.threads)} هسته", rates(r.read, r.write))) }
        if (!cpu.ok) onRow(failed("CPU", cpu))
        val fastest = cpu.rates.takeIf { cpu.ok }?.minByOrNull { callSeconds(cpu.copy(read = it.read, write = it.write)) }
        val threads = fastest?.threads ?: return null.also { GPU_SHARES.forEach { work.skip(Step.Gpu(it)) }; work.skip(Step.Mtp) }
        val bestCpu = cpu.copy(read = fastest.read, write = fastest.write)
        if (!cpu.mtpHead) work.skip(Step.Mtp)
        val bestGpu = gpuRows(model, threads, bestCpu, work, gpuLimit, onRow)
        // The GPU only when it wins clearly: its weights are pinned, the processor's can be paged out under pressure.
        val gpuWins = bestGpu != null && callSeconds(bestGpu.second) * GPU_MARGIN < callSeconds(bestCpu)
        val layers = if (gpuWins) bestGpu!!.first else 0
        val best = if (gpuWins) bestGpu!!.second else bestCpu
        val mtp = if (!cpu.mtpHead) null else work.run(Step.Mtp) { bench.run(BenchStep.Director(model, listOf(threads), layers, MTP_DRAFT), gpuLimit) }.takeIf { it.ok && it.draft > 0 }
        mtp?.let { onRow(directorRow("پیش‌بینی چندتوکنی (MTP)", it)) }
        val draft = mtp?.takeIf { it.write > best.write * MTP_GAIN }?.draft
        val line = "speed test: ${if (gpuWins) "gpu $layers/${best.totalLayers} layers" else "cpu"} · ${threads}t" + (draft?.let { " · mtp $it" } ?: "")
        Placement.keep(model, Placement.build(), Placement.Choice(layers, line, threads = threads, draft = draft, totalLayers = best.totalLayers.takeIf { it > 0 }))
        val chosen = mtp?.takeIf { draft != null } ?: best
        val way = (if (gpuWins) "CPU ${digits(threads)} هسته + کارت گرافیک ${digits(layers)} لایه" else "CPU · ${digits(threads)} هسته") + (if (draft != null) " · MTP" else "")
        onRow(SpeedRow(SpeedRow.Kind.Director, "کارگردان", "$way · ${rates(chosen.read, chosen.write)}", best = true))
        return threads
    }

    /**
     * The GPU, with as many of the layers as the free memory holds now (the rest stay on the processor); returns the
     * fastest layer count that really ran there (null when none did).
     */
    private suspend fun gpuRows(model: String, threads: Int, cpu: BenchResult, work: Work, limitMs: Long, onRow: (SpeedRow) -> Unit): Pair<Int, BenchResult>? {
        val gpu = ArrayList<Pair<Int, BenchResult>>()
        val levels = gpuLevels(model, cpu)
        GPU_SHARES.forEachIndexed { i, share ->
            val layers = levels.getOrNull(i)
            if (layers == null) {
                work.skip(Step.Gpu(share))
                return@forEachIndexed
            }
            val r = work.run(Step.Gpu(share)) { bench.run(BenchStep.Director(model, listOf(threads), layers), limitMs) }
            val label = "کارت گرافیک · ${digits(layers)} از ${digits(cpu.totalLayers)} لایه"
            onRow(if (r.ok && !r.onGpu) note(label, "روی کارت گرافیک ننشست") else directorRow(label, r))
            if (r.ok && r.onGpu) gpu += layers to r
        }
        if (levels.isEmpty()) onRow(note("کارت گرافیک", if (!cpu.gpuBuild) "این گوشی کارت گرافیکِ قابل استفاده ندارد" else "حافظهٔ آزاد کافی نیست"))
        return gpu.minByOrNull { (_, r) -> callSeconds(r) }
    }

    /**
     * The GPU layer counts worth timing: all of the layers the free memory holds now (all of the model when it fits; the
     * GPU's copy is pinned and [GPU_HEADROOM] stays for the app and the system) and half of them, the rest of the layers on
     * the processor.
     */
    private fun gpuLevels(model: String, cpu: BenchResult): List<Int> {
        val total = cpu.totalLayers
        if (!cpu.gpuBuild || total <= 0) return emptyList()
        val perLayer = File(model).length().toDouble() / total
        val free = freeBytes()
        val fit = if (free == null) total else ((free - GPU_HEADROOM) / perLayer).toInt().coerceIn(0, total)
        if (fit <= 0) return emptyList()
        return GPU_SHARES.map { share -> (fit * share).toInt().coerceAtLeast(1) }.distinct()
    }

    private suspend fun speechRows(model: String, work: Work, onRow: (SpeedRow) -> Unit) {
        val r = work.run(Step.Speech) { bench.run(BenchStep.Speech(model, speechOptions()), null) }
        if (!r.ok) return onRow(failed("Whisper", r))
        r.rates.forEach { t -> onRow(SpeedRow(SpeedRow.Kind.Speech, "Whisper · ${digits(t.threads)} هسته", "${one(t.seconds / BenchStep.SPEECH_SECONDS)} برابر زمان صدا")) }
        val best = r.rates.minByOrNull { it.seconds } ?: return
        SpeechThreads.keep(model, best.threads)
        onRow(SpeedRow(SpeedRow.Kind.Speech, "گفتار", "Whisper · ${digits(best.threads)} هسته · ${one(best.seconds / BenchStep.SPEECH_SECONDS)} برابر زمان صدا", best = true))
    }

    /** The picture engine each way it can run here; returns the fastest way (null when none drew). */
    private suspend fun pictureRows(files: List<String>, threads: Int, work: Work, onRow: (SpeedRow) -> Unit): SpeedProfile.PictureWay? {
        val devices = work.run(Step.Devices) { bench.run(BenchStep.Devices, null) }
        val gpuName = devices.devices.firstOrNull { !it.equals("CPU", ignoreCase = true) }
        val modelBytes = files.sumOf { File(it).length() }
        val free = freeBytes()
        val budget = free?.let { ((it - GPU_HEADROOM) / GIB).coerceAtLeast(0.0) }
        val timed = ArrayList<Pair<SpeedProfile.PictureWay, Double>>()
        // The processor's way goes first; a GPU way far slower than it is stopped, not waited for.
        var limitMs: Long? = null
        for (way in PictureWay.entries) {
            val placed = way.place(gpuName, modelBytes, free, budget, threads)
            if (placed == null) {
                work.skip(Step.Picture(way))
                continue
            }
            val started = System.nanoTime()
            val r = work.run(Step.Picture(way)) {
                bench.run(BenchStep.Picture(files, threads, placed.backend, placed.maxVram, placed.gpuLibrary), limitMs)
            }
            if (way == PictureWay.Cpu && r.ok) limitMs = limit((System.nanoTime() - started) / NANOS)
            onRow(if (r.ok) SpeedRow(SpeedRow.Kind.Note, way.label, "${one(r.seconds)} ثانیه برای یک تصویر کوچک") else failed(way.label, r))
            if (r.ok) timed += placed to r.seconds
        }
        if (gpuName == null) onRow(note("تصویرساز روی کارت گرافیک", "این گوشی کارت گرافیکِ قابل استفاده برای تصویرساز ندارد"))
        val best = timed.minByOrNull { it.second } ?: return null
        val way = PictureWay.entries.first { it.label == best.first.label }
        onRow(SpeedRow(SpeedRow.Kind.Note, "تصویرساز", "${way.chosen(threads)} · ${one(best.second)} ثانیه", best = true))
        return best.first
    }

    /** The picture engine's ways: all on the processor, the heavy parts on the GPU (text encoder on the processor), all on the GPU. */
    private enum class PictureWay(val label: String) {
        Cpu("تصویرساز · CPU"),
        Mixed("تصویرساز · CPU + کارت گرافیک"),
        Gpu("تصویرساز · فقط کارت گرافیک");

        fun place(gpu: String?, modelBytes: Long, free: Long?, budget: Double?, threads: Int): SpeedProfile.PictureWay? {
            val vram = if (gpu != null && budget != null) "$gpu=${"%.1f".format(java.util.Locale.ROOT, budget)}" else ""
            return when (this) {
                Cpu -> SpeedProfile.PictureWay(label, "cpu", "", gpuLibrary = false, threads = threads)
                Mixed -> gpu?.let { SpeedProfile.PictureWay(label, "all=$it,te=cpu", vram, gpuLibrary = true, threads = threads) }
                Gpu -> gpu?.takeIf { free == null || modelBytes + GPU_HEADROOM <= free }?.let { SpeedProfile.PictureWay(label, "all=$it", vram, gpuLibrary = true, threads = threads) }
            }
        }

        fun chosen(threads: Int): String = when (this) {
            Cpu -> "CPU · ${digits(threads)} هسته"
            Mixed -> "کارت گرافیک + متن روی CPU"
            Gpu -> "فقط کارت گرافیک"
        }
    }

    private fun callSeconds(r: BenchResult) = Placement.Probe(0, r.onGpu, r.read, r.write).callSeconds

    private fun directorRow(label: String, r: BenchResult) = if (r.ok) SpeedRow(SpeedRow.Kind.Director, label, rates(r.read, r.write)) else failed(label, r)

    private fun failed(label: String, r: BenchResult) =
        note(
            label,
            when {
                r.tooSlow -> "خیلی کندتر از CPU؛ قطع شد"
                r.crashed && "low memory" in r.note -> "سیستم برای کمبود حافظه بستش؛ این حالت استفاده نمی‌شود"
                r.crashed -> "از کار افتاد؛ این حالت استفاده نمی‌شود"
                else -> "اجرا نشد" + (r.note.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            },
        )

    private fun note(label: String, value: String) = SpeedRow(SpeedRow.Kind.Note, label, value)

    /** A GPU way's time limit: a minute for its load and kernels, and three times what the processor's way took. */
    private fun limit(processorSeconds: Double): Long = ((LIMIT_BASE_SECONDS + processorSeconds * LIMIT_FACTOR) * MS).toLong()

    private fun rates(read: Double, write: Double) = "خواندن ${one(read)} · نوشتن ${one(write)} توکن/ثانیه"

    /** One timed measurement of the test, for the percentage's clock. */
    private sealed interface Step {
        data object Memory : Step
        data object Cpu : Step
        data class Gpu(val share: Double) : Step
        data object Mtp : Step
        data object Speech : Step
        data object Devices : Step
        data class Picture(val way: PictureWay) : Step
    }

    /**
     * The percentage as time: the seconds spent over the seconds spent plus those still expected. Every step has an
     * expected time; when one ends, the steps like it are expected to take what it took (a GPU load what the processor's
     * took for one thread count), so the number moves evenly, forward only, and lands on 100 at the end.
     */
    private class Work(private val onProgress: (Float) -> Unit, plan: List<Pair<Step, Double>>, private val cpuCounts: Int) {
        private val expected = LinkedHashMap<Step, Double>().apply { plan.forEach { (k, v) -> put(k, v) } }
        private var spent = 0.0
        private var shown = 0f
        private var fastestCall: Double? = null

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

        /** A planned step that will not run (no GPU, too little memory, no director answer). */
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

        @Synchronized
        private fun end(step: Step, took: Double) {
            learn(step, took)
            expected.remove(step)
            spent += took
            show(null, 0.0)
        }

        /** What [step] took says what the ones like it will take. */
        private fun learn(step: Step, took: Double) {
            if (step is Step.Picture) expected.keys.filterIsInstance<Step.Picture>().forEach { o -> expected[o] = took }
            val one = when (step) {
                // The processor's load timed every thread count; a GPU load times one.
                Step.Cpu -> took * (1 + BenchStep.PASSES) / (1 + cpuCounts * BenchStep.PASSES)
                is Step.Gpu -> took
                else -> return
            }
            val fastest = minOf(one, fastestCall ?: one).also { fastestCall = it }
            expected.keys.filter { (it is Step.Gpu || it == Step.Mtp) && it != step }.forEach { expected[it] = fastest }
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

    private companion object {
        const val MTP_DRAFT = 3
        const val MTP_GAIN = 1.1
        const val GPU_MARGIN = 1.15
        const val LIMIT_BASE_SECONDS = 60.0
        const val LIMIT_FACTOR = 3.0
        const val MS = 1000.0
        val GPU_SHARES = listOf(1.0, 0.5)

        /** Kept free beside a GPU's pinned weights: the same headroom the builds ask for ([Placement.GPU_HEADROOM]). */
        const val GPU_HEADROOM = Placement.GPU_HEADROOM
        const val NANOS = 1e9
        const val GB = 1e9
        const val GIB = 1_073_741_824.0
        const val MEMORY_LONGS = 8 * 1024 * 1024
        const val MEMORY_PASSES = 8

        /** First guesses, until a measurement says better. */
        const val PASS_SECONDS_PER_GB = 1.5
        const val SPEECH_PASS_SECONDS = 10.0
        const val LOAD_SECONDS_PER_GB = 4.0
        const val MEMORY_SECONDS = 0.5
        const val DEVICES_SECONDS = 2.0
        const val PICTURE_SECONDS_PER_GB = 8.0

        /** A running step shows at most this share of its estimate; the percentage holds below 100 until the end. */
        const val CAP = 0.97
        const val LAST = 0.995f
        const val TICK_MS = 100L

        fun one(v: Double): String = digits(((v * 10).toLong() / 10.0).toString().removeSuffix(".0"))

        fun digits(v: Any): String = v.toString().map { if (it in '0'..'9') '۰' + (it - '0') else if (it == '.') '٫' else it }.joinToString("")
    }
}
