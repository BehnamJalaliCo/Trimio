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
)

/** Settings as observable state, persisted as one small JSON file. */
class SettingsRepository(private val file: Path?, private val fs: FileSystem = SystemFileSystem) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.Default) {
        val path = file ?: return@withContext
        if (!fs.exists(path)) return@withContext
        runCatching { DataJson.decodeFromString(AppSettings.serializer(), fs.source(path).buffered().use { it.readString() }) }
            .onSuccess { _settings.value = it }
    }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        val path = file ?: return
        withContext(Dispatchers.Default) {
            path.parent?.let(fs::createDirectories)
            val tmp = Path("$path.tmp")
            fs.sink(tmp).buffered().use { it.writeString(DataJson.encodeToString(AppSettings.serializer(), next)) }
            fs.atomicMove(tmp, path)
        }
    }
}
