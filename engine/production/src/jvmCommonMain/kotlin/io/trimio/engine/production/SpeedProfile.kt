package io.trimio.engine.production

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the last «سنجش سرعت این دستگاه» chose, kept beside the models: the rows the settings show as the way every build
 * runs now, and the processor thread count that drove the director fastest, which the picture engine uses too (a
 * generator is matrix work on the same cores). Absent before the first test; then the builds use their defaults.
 */
@Serializable
internal data class SpeedProfile(val chosen: List<Chosen> = emptyList(), val cpuThreads: Int? = null) {
    @Serializable
    data class Chosen(val kind: String, val label: String, val value: String)

    fun save(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), this))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val FILE = "speed-profile.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(file: File): SpeedProfile? = file.takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(serializer(), f.readText()) }.getOrNull() }
    }
}
