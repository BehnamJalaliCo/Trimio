package io.trimio.engine.production

import io.trimio.engine.production.diagnostics.MemoryUse
import java.io.File

/**
 * The process's memory as Linux keeps it (`/proc/self/status`: VmRSS now, VmHWM the peak), on
 * Android and Linux desktops; elsewhere (Windows, macOS) the JVM heap's peak, which leaves the
 * native engines' memory out and says so by leaving [MemoryUse.peakRssMb] empty.
 */
object ProcessMemory {
    /** [heapPeakMb] is the platform's own fallback where /proc is unreadable (the desktop JVM's heap). */
    fun read(heapPeakMb: () -> Int? = { null }): MemoryUse? {
        val status = runCatching { File("/proc/self/status").takeIf(File::canRead)?.readLines() }.getOrNull().orEmpty()
        fun kb(key: String) = status.firstOrNull { it.startsWith("$key:") }?.let { DIGITS.find(it)?.value?.toLongOrNull() }
        val rss = kb("VmRSS")?.let { (it / KB).toInt() }
        val peak = kb("VmHWM")?.let { (it / KB).toInt() }
        val heapPeak = if (peak == null) runCatching(heapPeakMb).getOrNull() else null
        return if (rss == null && peak == null && heapPeak == null) null else MemoryUse(rss, peak, heapPeak)
    }

    private val DIGITS = Regex("\\d+")
    private const val KB = 1024L
}
