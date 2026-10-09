package io.trimio.core.data

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Crash reports kept on the device. Nothing is sent anywhere unless the user turns on sharing in
 * settings (consent); then the newest report can be shared, and phase 10's server accepts uploads.
 * Reports hold the stack trace and device model only: no media, prompts or transcripts.
 */
class CrashLog(private val directory: Path, private val fs: FileSystem = SystemFileSystem, private val keep: Int = 10) {

    fun record(throwable: Throwable, device: String, appVersion: String, timeMs: Long) {
        runCatching {
            fs.createDirectories(directory)
            val text = buildString {
                appendLine("Trimio $appVersion · $device · $timeMs")
                appendLine(throwable.stackTraceToString())
            }
            fs.sink(Path(directory, "crash-$timeMs.txt")).buffered().use { it.writeString(text) }
            reports().drop(keep).forEach { fs.delete(it, mustExist = false) }
        }
    }

    /** Newest first. */
    fun reports(): List<Path> =
        if (!fs.exists(directory)) emptyList() else fs.list(directory).filter { it.name.startsWith("crash-") }.sortedByDescending { it.name }

    fun read(path: Path): String = fs.source(path).buffered().use { it.readString() }

    fun clear() = reports().forEach { fs.delete(it, mustExist = false) }
}
