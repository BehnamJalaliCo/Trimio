package io.trimio.engine.llm.local

import java.io.File

/**
 * The cores that model work should run on: those clocked within 75% of the fastest (Linux and
 * Android cpufreq). Little cores would hold every step back to their pace, while newer flagships
 * have no little cores at all, so "half the cores" would leave half the chip idle there.
 */
object FastCores {
    /** Null where cpufreq is unreadable (Windows, macOS): callers keep their own estimate. */
    fun count(): Int? = runCatching {
        val clocks = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(CPU) }.orEmpty()
            .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
        val top = clocks.maxOrNull() ?: return@runCatching null
        clocks.count { it * SHARE_DEN >= top * SHARE_NUM }
    }.getOrNull()

    private val CPU = Regex("cpu\\d+")
    private const val SHARE_NUM = 3
    private const val SHARE_DEN = 4
}
