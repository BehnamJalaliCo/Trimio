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
import kotlin.math.exp
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
        val work = Work(onProgress, MEMORY + (if (model != null) PROBE * (threadOptions().size + MTP_PROBES + (if (gpu) 1 else 0)) else 0.0) +
            (if (voice != null) SPEECH * speechOptions().size else 0.0))
        onRow(memory())
        work.done(MEMORY)
        val chosen = ArrayList<SpeedRow>()
        var cpuThreads: Int? = null
        if (model == null) onRow(SpeedRow(SpeedRow.Kind.Note, "کارگردان", "نصب نیست")) else {
            cpuThreads = directorRows(model, gpu, work) { row -> onRow(row); if (row.best) chosen += row }
        }
        if (voice == null) onRow(SpeedRow(SpeedRow.Kind.Note, "Whisper", "نصب نیست")) else speechRows(voice, work) { row -> onRow(row); if (row.best) chosen += row }
        cpuThreads?.let { t -> chosen += SpeedRow(SpeedRow.Kind.Note, "تصویرساز و افزونه‌ها", "CPU · ${digits(t)} هسته", best = true).also(onRow) }
        profile?.let { SpeedProfile(chosen.map { SpeedProfile.Chosen(it.kind.name, it.label, it.value) }, cpuThreads).save(it) }
        onProgress(1f)
    }

    override fun current(): List<SpeedRow> = profile?.let(SpeedProfile::load)?.chosen.orEmpty().map { c ->
        SpeedRow(runCatching { SpeedRow.Kind.valueOf(c.kind) }.getOrDefault(SpeedRow.Kind.Note), c.label, c.value, best = true)
    }

    private fun threadOptions(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val fast = FastCores.count() ?: cores
        return listOf(1, (cores / 2).coerceAtLeast(1), (fast - 1).coerceAtLeast(1), fast).distinct().sorted()
    }

    private fun speechOptions(): List<Int> = threadOptions().last().let { fast -> listOf((fast - 1).coerceAtLeast(1), fast).distinct() }

    /** The director every way; returns the processor thread count that read and wrote fastest (null when none ran). */
    private suspend fun directorRows(model: String, gpu: Boolean, work: Work, onRow: (SpeedRow) -> Unit): Int? {
        val probes = ArrayList<Pair<Int, Placement.Probe>>()
        for (t in threadOptions()) {
            work.timed(PROBE) { LlamaProbe.run(model, t, 0) }?.let { p -> probes += t to p; onRow(row("CPU · ${digits(t)} هسته", p)) }
        }
        val bestCpu = probes.minByOrNull { it.second.callSeconds }
        if (gpu) {
            val t = bestCpu?.first ?: threadOptions().last()
            work.timed(PROBE) { LlamaProbe.run(model, t, GPU) }?.takeIf { it.onGpu }?.let { p -> probes += t to p; onRow(row("کارت گرافیک", p)) }
        } else if (LlamaProbe.gpuBuild) {
            onRow(SpeedRow(SpeedRow.Kind.Note, "کارت گرافیک", "حافظهٔ آزاد کافی نیست"))
        }
        val cpu = bestCpu?.second
        val onGpu = probes.map { it.second }.firstOrNull { it.onGpu }
        val choice = Placement.choose(onGpu, cpu)
        val threads = if (choice.gpuLayers != 0) probes.first { it.second.onGpu }.first else bestCpu?.first ?: return null
        val best = if (choice.gpuLayers != 0) onGpu!! else cpu!!
        // The model's own MTP head (when it has one) guessing tokens ahead: kept only when it writes clearly faster.
        val mtp = work.timed(PROBE) { LlamaProbe.run(model, threads, choice.gpuLayers, MTP_DRAFT) }?.takeIf { it.draft > 0 }
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
            runCatching {
                WhisperCppRecognizer(model).use { w ->
                    SpeechThreads.keep(model, t)
                    val start = System.nanoTime()
                    // Whisper says how far it has read: the percentage follows it, not a guess.
                    w.transcribe(sound, RecognitionOptions(Language.Persian, threads = t), onProgress = { work.within(SPEECH, it) })
                    t to (System.nanoTime() - start) / NANOS
                }
            }.getOrNull().also { work.done(SPEECH) }?.also { (t, s) -> onRow(SpeedRow(SpeedRow.Kind.Speech, "Whisper · ${digits(t)} هسته", "${one(s / SPEECH_SECONDS)} برابر زمان صدا")) }
        }
        val best = timed.minByOrNull { it.second } ?: return
        SpeechThreads.keep(model, best.first)
        onRow(SpeedRow(SpeedRow.Kind.Speech, "گفتار در ساخت", "Whisper · ${digits(best.first)} هسته", best = true))
    }

    /**
     * The percentage: each measurement holds its share of [total]; a model call that cannot say how far it is moves
     * smoothly toward the end of its share (never past it) by the time the previous one took, and lands when it ends.
     */
    private class Work(private val onProgress: (Float) -> Unit, private val total: Double) {
        private var finished = 0.0
        private var shown = 0f
        private var lastSeconds = FIRST_GUESS

        fun within(share: Double, fraction: Float) = emit(finished + share * fraction.coerceIn(0f, 1f))

        fun done(share: Double) {
            finished += share
            emit(finished)
        }

        suspend fun <T> timed(share: Double, call: () -> T): T = coroutineScope {
            val start = System.nanoTime()
            val tick = launch {
                while (isActive) {
                    delay(TICK_MS)
                    val seconds = (System.nanoTime() - start) / NANOS
                    within(share, (CAP * (1 - exp(-seconds / lastSeconds))).toFloat())
                }
            }
            try {
                call()
            } finally {
                tick.cancel()
                lastSeconds = ((System.nanoTime() - start) / NANOS).coerceAtLeast(1.0)
                done(share)
            }
        }

        @Synchronized
        private fun emit(units: Double) {
            val p = (units / total).toFloat().coerceIn(0f, 1f)
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

        /** Shares of the percentage: a director call, a Whisper read (model load included), the memory copy. */
        const val PROBE = 3.0
        const val SPEECH = 4.0
        const val MEMORY = 0.5
        const val MTP_PROBES = 1

        /** Before the first call ends, how long one is guessed to take; the curve stops short of its share. */
        const val FIRST_GUESS = 20.0
        const val CAP = 0.95
        const val TICK_MS = 120L
    }
}
