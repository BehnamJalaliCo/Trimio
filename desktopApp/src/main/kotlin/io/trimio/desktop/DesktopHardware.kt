package io.trimio.desktop

import io.trimio.engine.models.DeviceFit
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import java.lang.management.ManagementFactory

/** A graphics adapter; [vramMb] is known for NVIDIA cards (nvidia-smi) and unknown otherwise. */
data class Gpu(val name: String, val vramMb: Long? = null)

/**
 * The machine as the model picker sees it. A desktop swaps instead of killing the app, so its
 * memory is rated with [DeviceMemory.desktop] bands; the GPU decides whether llama.cpp offloads
 * layers (the Vulkan build loads only where a Vulkan driver exists, see `LlamaNative`).
 */
data class DesktopHardware(val ramGb: Int, val cores: Int, val gpus: List<Gpu> = emptyList()) {
    val memory: DeviceMemory get() = DeviceMemory.desktop(ramGb)

    companion object {
        /** RAM and cores only: instant, safe on the UI thread. */
        fun basic(): DesktopHardware = DesktopHardware(ramGb(), Runtime.getRuntime().availableProcessors())

        /** Also asks the OS for its GPUs (a helper process, up to a few seconds): call off the UI thread. */
        fun detect(os: DesktopOs = DesktopOs.current): DesktopHardware = basic().copy(gpus = gpus(os))

        /** Marketed RAM: a "32 GB" machine reports a little less to the OS. */
        fun ramGb(totalBytes: Long = totalMemory()): Int = ((totalBytes + DeviceFit.GIB / 2) / DeviceFit.GIB).toInt()

        private fun totalMemory(): Long =
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: (8 * DeviceFit.GIB)

        private fun gpus(os: DesktopOs): List<Gpu> {
            val nvidia = Commands.run(listOf("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"))?.let(::parseNvidiaSmi)
            if (!nvidia.isNullOrEmpty()) return nvidia
            val names = when (os) {
                DesktopOs.Windows -> Commands.run(
                    listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_VideoController).Name"),
                )?.lines()
                DesktopOs.MacOs -> Commands.run(listOf("system_profiler", "SPDisplaysDataType"))
                    ?.lines()?.filter { "Chipset Model:" in it }?.map { it.substringAfter(':') }
                DesktopOs.Linux -> null
            }
            return names.orEmpty().map(String::trim).filter(String::isNotEmpty).distinct().map { Gpu(it) }
        }

        /** `name, memoryMiB` per line, as `nvidia-smi --format=csv,noheader,nounits` prints. */
        fun parseNvidiaSmi(out: String): List<Gpu> = out.lines().mapNotNull { line ->
            val name = line.substringBeforeLast(',', "").trim()
            if (name.isEmpty()) null else Gpu(name, line.substringAfterLast(',').trim().toLongOrNull())
        }
    }
}

/**
 * Which on-device directors the desktop offers (roadmap 7.6): the 35B-A3B mixture of experts from
 * [LARGE_RAM_GB] up, where its 12 GB of weights, cache and the renderer fit beside the OS; the 9B
 * and 4B everywhere else. Other kinds (speech, eyes, the looker) pass through.
 */
object DesktopDirectors {
    const val LARGE_RAM_GB = 24

    fun offered(ramGb: Int): List<ModelSpec> =
        if (ramGb >= LARGE_RAM_GB) listOf(ModelCatalog.qwen36_35bA3b, ModelCatalog.qwen35_9b, ModelCatalog.qwen35_4b)
        else listOf(ModelCatalog.qwen35_9b, ModelCatalog.qwen35_4b)

    /** [catalog] with the directors this machine is not offered left out. */
    fun catalogFor(ramGb: Int, catalog: List<ModelSpec> = ModelCatalog.all): List<ModelSpec> {
        val directors = offered(ramGb).map { it.id }.toSet()
        return catalog.filter { it.kind != ModelKind.Language || it.id in directors }
    }

    /** The director to suggest first: the largest offered one that fits comfortably, else the 4B. */
    fun recommended(hardware: DesktopHardware): ModelSpec =
        offered(hardware.ramGb).firstOrNull { !it.experimental && DeviceFit.of(it, hardware.memory).comfortable } ?: ModelCatalog.qwen35_4b
}
