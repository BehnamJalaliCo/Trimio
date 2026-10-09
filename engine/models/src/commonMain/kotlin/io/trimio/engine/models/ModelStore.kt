package io.trimio.engine.models

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.kotlincrypto.hash.sha2.SHA256

sealed interface DownloadState {
    data class Progress(val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
    data class Done(val path: Path) : DownloadState
}

/** The file is wrong (bad checksum or oversized) and was discarded. */
class ModelIntegrityException(message: String) : Exception(message)

/** The transfer stopped early; the partial file is kept for resuming. */
class IncompleteDownloadException(message: String) : Exception(message)

/**
 * Installs models into [directory]. Downloads resume after interruption (HTTP Range on the
 * `.part` file), fall through mirrors in order, and are verified with SHA-256 before the final
 * atomic rename, so a model file that exists is always complete and authentic.
 */
class ModelStore(
    /** Null where models cannot be stored (the browser). */
    private val directory: Path?,
    private val client: HttpClient,
    private val fs: FileSystem? = null,
) {
    /** Null without a directory (the browser): nothing is installed and installs fail. */
    private val storage: FileSystem? by lazy { directory?.let { fs ?: SystemFileSystem } }
    private val dir: Path get() = directory ?: throw UnsupportedOperationException("No model storage on this platform")
    private val files: FileSystem get() = storage ?: throw UnsupportedOperationException("No file system on this platform")

    fun pathOf(spec: ModelSpec): Path = Path(dir, spec.fileName)

    fun isInstalled(spec: ModelSpec): Boolean = storage != null &&
        files.exists(pathOf(spec)) && files.metadataOrNull(pathOf(spec))?.size == spec.sizeBytes

    fun delete(spec: ModelSpec) {
        files.delete(pathOf(spec), mustExist = false)
        files.delete(partOf(spec), mustExist = false)
    }

    suspend fun install(spec: ModelSpec, onState: (DownloadState) -> Unit = {}): Path {
        if (isInstalled(spec)) return pathOf(spec).also { onState(DownloadState.Done(it)) }
        files.createDirectories(dir)
        var lastError: Throwable? = null
        for (url in spec.urls) {
            try {
                download(spec, url, onState)
                verifyAndCommit(spec)
                return pathOf(spec).also { onState(DownloadState.Done(it)) }
            } catch (e: CancellationException) {
                throw e // keep the .part file: the next attempt resumes
            } catch (e: ModelIntegrityException) {
                files.delete(partOf(spec), mustExist = false)
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No URL for ${spec.id}")
    }

    private suspend fun download(spec: ModelSpec, url: String, onState: (DownloadState) -> Unit) {
        val part = partOf(spec)
        var have = files.metadataOrNull(part)?.size ?: 0L
        if (have >= spec.sizeBytes) return

        client.prepareGet(url) {
            if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
        }.execute { response ->
            if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value} from $url")
            // A server that ignores Range restarts from zero.
            val append = have > 0 && response.status == HttpStatusCode.PartialContent
            if (!append) have = 0
            val channel = response.bodyAsChannel()
            files.sink(part, append = append).buffered().use { sink ->
                while (!channel.isClosedForRead) {
                    val chunk = channel.readRemaining(CHUNK).readByteArray()
                    if (chunk.isEmpty()) continue
                    sink.write(chunk)
                    have += chunk.size
                    onState(DownloadState.Progress(have, spec.sizeBytes))
                }
            }
        }
        // A dropped connection leaves a short file; keep it so the next attempt resumes from here.
        if (have < spec.sizeBytes) throw IncompleteDownloadException("${spec.id}: received $have of ${spec.sizeBytes} bytes")
    }

    private fun verifyAndCommit(spec: ModelSpec) {
        val part = partOf(spec)
        val size = files.metadataOrNull(part)?.size ?: 0L
        if (size != spec.sizeBytes) throw ModelIntegrityException("${spec.id}: size $size, expected ${spec.sizeBytes}")
        val digest = SHA256()
        files.source(part).buffered().use { source ->
            val buffer = ByteArray(CHUNK.toInt())
            while (true) {
                val n = source.readAtMostTo(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        if (!hex.equals(spec.sha256, ignoreCase = true)) throw ModelIntegrityException("${spec.id}: checksum mismatch")
        files.atomicMove(part, pathOf(spec))
    }

    private fun partOf(spec: ModelSpec) = Path(dir, spec.fileName + ".part")

    private companion object {
        const val CHUNK = 256L * 1024
    }
}
