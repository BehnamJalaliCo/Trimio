package io.trimio.engine.asr.whisper

import java.io.File

/**
 * The thread count the speed test found fastest for a speech model on this device, kept beside the model
 * (`<model>.threads`); builds use it instead of the device's default. Absent until the test has run.
 */
object SpeechThreads {
    fun cached(model: String): Int? = runCatching { File("$model.threads").takeIf(File::isFile)?.readText()?.trim()?.toInt() }.getOrNull()?.takeIf { it > 0 }

    fun keep(model: String, threads: Int) {
        runCatching { File("$model.threads").writeText("$threads\n") }
    }

    /**
     * [threads], or fewer when this process may use fewer cores now (`Cpus_allowed_list`; Android narrows an app off
     * the screen): two of whisper's threads on one core make every step wait.
     */
    fun allowed(threads: Int): Int {
        val list = runCatching { File("/proc/self/status").useLines { l -> l.firstOrNull { it.startsWith(KEY) } }?.substringAfter(':')?.trim() }.getOrNull()
        val cores = list?.split(',')?.filter { it.isNotBlank() }?.sumOf { part ->
            val ends = part.trim().split('-').mapNotNull(String::toIntOrNull)
            if (ends.size == 2) ends[1] - ends[0] + 1 else 1
        }
        return cores?.takeIf { it > 0 }?.let { threads.coerceAtMost(it) } ?: threads
    }

    private const val KEY = "Cpus_allowed_list:"
}
