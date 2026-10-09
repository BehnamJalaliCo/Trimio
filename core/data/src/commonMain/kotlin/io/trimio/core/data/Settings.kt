package io.trimio.core.data

import io.trimio.core.model.input.Resolution
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.DirectorBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** Null follows the system (per-app language on Android 13+). */
    val language: Language? = null,
    val onboardingDone: Boolean = false,
    val director: DirectorBackend = DirectorBackend.OnDevice,
    /** Catalogue id of the on-device director model, or null for the best installed one. */
    val localModelId: String? = null,
    val speechModelId: String? = null,
    val exportResolution: Resolution = Resolution.FullHd,
    val exportFrameRate: Int = 30,
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val haptics: Boolean = true,
    /** Consent to share crash reports (off by default). */
    val shareCrashReports: Boolean = false,
)

/** Settings as observable state, persisted as one small JSON file. */
class SettingsRepository(private val file: Path?, private val fs: FileSystem? = null) {
    // Resolved on first use: browsers have no file system, and web builds pass no file.
    private val files by lazy { fs ?: SystemFileSystem }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.Default) {
        val path = file ?: return@withContext
        if (!files.exists(path)) return@withContext
        runCatching { DataJson.decodeFromString(AppSettings.serializer(), files.source(path).buffered().use { it.readString() }) }
            .onSuccess { _settings.value = it }
    }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        val path = file ?: return
        withContext(Dispatchers.Default) {
            path.parent?.let(files::createDirectories)
            val tmp = Path("$path.tmp")
            files.sink(tmp).buffered().use { it.writeString(DataJson.encodeToString(AppSettings.serializer(), next)) }
            files.atomicMove(tmp, path)
        }
    }
}
