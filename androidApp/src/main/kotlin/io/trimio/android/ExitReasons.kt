package io.trimio.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.trimio.core.data.CrashLog

/**
 * Why the app's process ended last time, as Android remembers it (API 30+): a native crash in
 * llama.cpp, whisper or ONNX, an out-of-memory kill or a freeze leaves no Kotlin exception for
 * [CrashLog] to catch, so each such exit is written to the same crash log on the next start —
 * the reason, the system's description and, for native crashes and freezes, the top of the trace.
 * Settings shares the newest («ارسال آخرین گزارش»).
 */
object ExitReasons {
    fun record(context: Context, log: CrashLog, version: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_SEEN, 0L)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX).filter { it.timestamp > seen && it.reason in WORTH }
            exits.sortedBy { it.timestamp }.forEach { log.recordText(text(it, version), it.timestamp) }
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.let { prefs.edit().putLong(KEY_SEEN, maxOf(seen, it.timestamp)).apply() }
        }
    }

    private fun text(e: ApplicationExitInfo, version: String): String = buildString {
        appendLine("Trimio $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${e.timestamp}")
        appendLine("process ended: ${name(e.reason)} (status ${e.status}) · ${e.description.orEmpty()}")
        appendLine("memory at the end: pss ${e.pss / KIB} MB · rss ${e.rss / KIB} MB")
        val trace = runCatching { e.traceInputStream?.bufferedReader()?.useLines { it.take(TRACE_LINES).toList() } }.getOrNull()
        if (!trace.isNullOrEmpty()) {
            appendLine("trace:")
            trace.forEach(::appendLine)
        }
    }

    private fun name(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for resource use"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        else -> "reason $reason"
    }

    private val WORTH = setOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )
    private const val PREFS = "exit-reasons"
    private const val KEY_SEEN = "seen"
    private const val MAX = 16
    private const val TRACE_LINES = 200
    private const val KIB = 1024
}
