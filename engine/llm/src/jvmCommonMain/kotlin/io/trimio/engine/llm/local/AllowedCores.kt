package io.trimio.engine.llm.local

import java.io.File

/**
 * The cores this process may run on right now (Linux and Android `Cpus_allowed_list`). Android gives an app fewer
 * cores once it leaves the screen (a build under its notification, the screen off) or while it is a background
 * service. More model threads than these cores is the worst case of ggml's spinning barrier ([LlamaLanguageModel]):
 * two threads share a core and every step waits for the one whose turn it is not.
 */
object AllowedCores {
    /** Null where it is unreadable (Windows, macOS). */
    fun count(): Int? = runCatching {
        File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith(KEY) } }?.substringAfter(':')?.trim()?.let(::parse)
    }.getOrNull()?.takeIf { it > 0 }

    /** [threads], or fewer when this process may use fewer cores now. */
    fun cap(threads: Int): Int = count()?.let { threads.coerceAtMost(it) } ?: threads

    /** "0-3,6,7" → 6. */
    internal fun parse(list: String): Int = list.split(',').filter { it.isNotBlank() }.sumOf { part ->
        val ends = part.trim().split('-').map(String::toInt)
        if (ends.size == 2) ends[1] - ends[0] + 1 else 1
    }

    private const val KEY = "Cpus_allowed_list:"
}
