package io.trimio.core.data

import io.trimio.core.model.input.MediaUri

/** What the user can bring in. */
enum class MediaKind { Video, Audio }

/** System media chooser (Android Photo Picker / document picker). Null when the user backs out. */
fun interface MediaPicker {
    suspend fun pick(kind: MediaKind): MediaUri?
}

/** Hands a finished video, or project files for other editors, to other apps. */
interface Sharer {
    fun share(uri: String, title: String)

    /** Writes text files (name → content) to app storage and shares them together. */
    fun shareFiles(files: Map<String, String>, title: String) {}
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
)
