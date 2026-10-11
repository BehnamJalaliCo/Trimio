package io.trimio.core.data

import io.trimio.core.model.input.MediaUri

/** What the user can bring in. */
/** What to pick: the source video or voice, or a picture (an attachment beside the prompt). */
enum class MediaKind { Video, Audio, Image }

/** System media chooser (Android Photo Picker / document picker). Null when the user backs out. */
fun interface MediaPicker {
    suspend fun pick(kind: MediaKind): MediaUri?
}

/** Hands a finished video, or project files for other editors, to other apps. */
interface Sharer {
    fun share(uri: String, title: String)

    /** Writes text files (name → content) to app storage and shares them together. */
    fun shareFiles(files: Map<String, String>, title: String) {}

    /**
     * Hands files already written (an export's video, a project package) to other apps together;
     * on desktop, shows them in their folder.
     */
    fun sharePaths(paths: List<String>, title: String) {
        paths.firstOrNull()?.let { share(it, title) }
    }
}

/** Saves a finished MP4 to the system gallery (Android: Movies/Trimio; desktop: the Videos folder). */
fun interface GallerySaver {
    suspend fun save(path: String, title: String): Boolean
}

/** Whether the internet is reachable now (a cloud director needs it). */
fun interface Connectivity {
    suspend fun online(): Boolean

    companion object {
        val Always = Connectivity { true }
    }
}

/** Facts about the device the UI adapts to. */
data class DeviceInfo(
    val ramGb: Int,
    val platform: String,
    val appVersion: String,
    /** Monotonic build number, compared with the server's minimum supported build. */
    val versionCode: Int = 0,
    /** Where this build updates from (its store page), for the forced-update prompt. */
    val storeUrl: String? = null,
    /** Engines are simulated on this platform (web/iOS until they are wired); the UI says so. */
    val previewOnly: Boolean = false,
    /** Processor cores; 0 when unknown. */
    val cores: Int = 0,
)

/** Free space where the app keeps models and renders; null when the platform cannot tell. */
fun interface StorageInfo {
    fun freeBytes(): Long?

    companion object {
        val Unknown = StorageInfo { null }
    }
}
