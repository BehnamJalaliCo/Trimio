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
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * «سنجش سرعت این دستگاه» ([SpeedTest]): the [director] a build would run, timed on one core, half the cores, all
 * the fast cores (less one, and all) and the GPU when this build has one and memory allows; the memory's copy speed;
 * the [speech] model encoding 15 s of sound at two thread counts. The fastest way for each model is kept beside it
 * ([Placement], [SpeechThreads]) and every build after uses it.
 */
internal class DeviceSpeedTest(
    private val director: () -> String?,
    private val speech: () -> String?,
) : SpeedTest {
    override suspend fun run(onRow: (SpeedRow) -> Unit) = withContext(Dispatchers.Default) {
        onRow(memory())
        val model = director()
        if (model == null) onRow(SpeedRow(SpeedRow.Kind.Note, "کارگردان", "نصب نیست")) else directorRows(model, onRow)
        val voice = speech()
        if (voice == null) onRow(SpeedRow(SpeedRow.Kind.Note, "Whisper", "نصب نیست")) else speechRows(voice, onRow)
    }

    private fun threadOptions(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val fast = FastCores.count() ?: cores
        return listOf(1, (cores / 2).coerceAtLeast(1), (fast - 1).coerceAtLeast(1), fast).distinct().sorted()
    }

    private fun directorRows(model: String, onRow: (SpeedRow) -> Unit) {
        val probes = threadOptions().mapNotNull { t ->
            LlamaProbe.run(model, t, 0)?.let { p -> (t to p).also { onRow(row("CPU · ${digits(t)} هسته", p)) } }
        }.toMutableList()
        val gpuRoom = Placement.gpuFits(File(model).length(), Placement.freeBytes())
        val bestCpu = probes.minByOrNull { it.second.callSeconds }
        if (LlamaProbe.gpuBuild && gpuRoom) {
            LlamaProbe.run(model, bestCpu?.first ?: threadOptions().last(), GPU)?.takeIf { it.onGpu }?.let { p ->
                probes += (bestCpu?.first ?: threadOptions().last()) to p
                onRow(row("کارت گرافیک", p))
            }
        } else if (LlamaProbe.gpuBuild) {
            onRow(SpeedRow(SpeedRow.Kind.Note, "کارت گرافیک", "حافظهٔ آزاد کافی نیست"))
        }
        val cpu = bestCpu?.second
        val gpu = probes.map { it.second }.firstOrNull { it.onGpu }
        val choice = Placement.choose(gpu, cpu)
        val threads = if (choice.gpuLayers != 0) probes.first { it.second.onGpu }.first else bestCpu?.first ?: return
        val best = if (choice.gpuLayers != 0) gpu!! else cpu!!
        // The model's own MTP head (when it has one) guessing tokens ahead: kept only when it writes clearly faster.
        val mtp = LlamaProbe.run(model, threads, choice.gpuLayers, MTP_DRAFT)?.takeIf { it.draft > 0 }
        mtp?.let { onRow(row("پیش‌بینی چندتوکنی (MTP)", it)) }
        val draft = mtp?.takeIf { it.writePerSecond > best.writePerSecond * MTP_GAIN }?.draft
        Placement.keep(model, Placement.build(), choice.copy(line = "speed test: " + choice.line.removePrefix("measured: ") + (draft?.let { " · mtp $it" } ?: ""), threads = threads, draft = draft))
        val chosen = mtp?.takeIf { draft != null } ?: best
        val way = (if (best.onGpu) "کارت گرافیک" else "CPU · ${digits(threads)} هسته") + (if (draft != null) " · MTP" else "")
        onRow(SpeedRow(SpeedRow.Kind.Director, "انتخاب برای ساخت", "$way · ${rates(chosen)}", best = true))
    }

    private suspend fun speechRows(model: String, onRow: (SpeedRow) -> Unit) {
        val fast = threadOptions().last()
        val options = listOf((fast - 1).coerceAtLeast(1), fast).distinct()
        // Quiet noise: the encoder works on a full window whatever is said, which is what the test times.
        val rng = Random(1)
        val sound = PcmAudio(FloatArray(SPEECH_SECONDS * PcmAudio.WHISPER_RATE) { (rng.nextFloat() - 0.5f) * NOISE }, PcmAudio.WHISPER_RATE)
        val timed = options.mapNotNull { t ->
            runCatching {
                WhisperCppRecognizer(model).use { w ->
                    SpeechThreads.keep(model, t)
                    val start = System.nanoTime()
                    w.transcribe(sound, RecognitionOptions(Language.Persian, threads = t))
                    t to (System.nanoTime() - start) / NANOS
                }
            }.getOrNull()?.also { (t, s) -> onRow(SpeedRow(SpeedRow.Kind.Speech, "Whisper · ${digits(t)} هسته", "${one(s / SPEECH_SECONDS)} برابر زمان صدا")) }
        }
        val best = timed.minByOrNull { it.second } ?: return
        SpeechThreads.keep(model, best.first)
        onRow(SpeedRow(SpeedRow.Kind.Speech, "انتخاب برای ساخت", "Whisper · ${digits(best.first)} هسته", best = true))
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
    }
}
