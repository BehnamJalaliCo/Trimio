package io.trimio.core.data

import io.trimio.core.model.input.MediaUri

/** What the user can bring in. */
enum class MediaKind { Video, Audio }

/** System media chooser (Android Photo Picker / document picker). Null when the user backs out. */
fun interface MediaPicker {
    suspend fun pick(kind: MediaKind): MediaUri?
}

/** Hands a finished video to other apps (Instagram, Telegram…). */
fun interface Sharer {
    fun share(uri: String, title: String)
}

/** Facts about the device the UI adapts to. */
data class DeviceInfo(
    val ramGb: Int,
    val platform: String,
    val appVersion: String,
)
