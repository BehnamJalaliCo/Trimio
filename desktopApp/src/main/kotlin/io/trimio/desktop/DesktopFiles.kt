package io.trimio.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.MediaUri
import io.trimio.feature.create.Attachment
import io.trimio.feature.create.AttachmentKind
import io.trimio.feature.create.AttachmentPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** What the desktop accepts, by extension (pickers filter on it, drops are sorted by it). */
object MediaTypes {
    val video = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi")
    val audio = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "opus")
    val picture = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
    val logo = picture + "svg"

    fun kindOf(file: File): MediaKind? = when (file.extension.lowercase()) {
        in video -> MediaKind.Video
        in audio -> MediaKind.Audio
        else -> null
    }

    /** The attachment a dropped or picked file becomes, by its extension. */
    fun attachmentOf(file: File): AttachmentKind? = when (file.extension.lowercase()) {
        in video -> AttachmentKind.Clip
        in picture -> AttachmentKind.Picture
        "svg" -> AttachmentKind.Logo
        else -> null
    }
}

/**
 * The system's own open dialog (Explorer on Windows, Finder on macOS) for the source video or
 * voice recording and for attachments. Media is referred to by absolute path: the engines open
 * files directly, and desktop files keep their paths across restarts.
 */
class DesktopFilePicker(private val owner: () -> Frame?) : MediaPicker, AttachmentPicker {
    override suspend fun pick(kind: MediaKind): MediaUri? {
        val (title, types) = when (kind) {
            MediaKind.Video -> "Choose a video" to MediaTypes.video
            MediaKind.Audio -> "Choose a voice recording" to MediaTypes.audio
            MediaKind.Image -> "Choose a picture" to MediaTypes.logo
        }
        return choose(title, types)?.let { MediaUri(it.absolutePath) }
    }

    override suspend fun pick(kind: AttachmentKind): Attachment? {
        val types = when (kind) {
            AttachmentKind.Picture -> MediaTypes.picture
            AttachmentKind.Clip -> MediaTypes.video
            AttachmentKind.Logo -> MediaTypes.logo
        }
        val file = choose("Attach a file", types) ?: return null
        return attachment(file, kind)
    }

    private suspend fun choose(title: String, extensions: Set<String>): File? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(owner(), title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        // Windows ignores FilenameFilter; a wildcard list is what its dialog filters on.
        dialog.file = extensions.joinToString(";") { "*.$it" }
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext null
        File(dialog.directory, name).takeIf { it.isFile && it.extension.lowercase() in extensions }
    }

    companion object {
        private const val THUMB = 256

        /** An attachment card for [file], with a small still for pictures. */
        suspend fun attachment(file: File, kind: AttachmentKind): Attachment = withContext(Dispatchers.IO) {
            Attachment(
                id = "a-" + file.absolutePath.hashCode().toUInt().toString(16),
                kind = kind,
                uri = file.absolutePath,
                name = file.name,
                thumbnail = if (kind != AttachmentKind.Clip) thumbnail(file) else null,
            )
        }

        private fun thumbnail(file: File): ImageBitmap? = runCatching {
            val image = Image.makeFromEncoded(file.readBytes())
            val scale = minOf(1f, THUMB.toFloat() / maxOf(image.width, image.height))
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().toComposeImageBitmap()
        }.getOrNull()
    }
}

/**
 * "Share" on desktop means showing the file where it is: finished videos are already in
 * Videos\Trimio, so the file manager opens with the file selected, ready to upload or drag.
 */
class DesktopSharer(private val output: File, private val os: DesktopOs = DesktopOs.current) : Sharer {
    override fun share(uri: String, title: String) = reveal(File(uri.removePrefix("file:")))

    override fun shareFiles(files: Map<String, String>, title: String) {
        val dir = File(output, safeName(title)).apply { mkdirs() }
        files.forEach { (name, content) -> File(dir, safeName(name)).writeText(content) }
        reveal(dir)
    }

    fun reveal(file: File) {
        val command = when (os) {
            DesktopOs.Windows -> if (file.isFile) listOf("explorer", "/select,", file.absolutePath) else listOf("explorer", file.absolutePath)
            DesktopOs.MacOs -> if (file.isFile) listOf("open", "-R", file.absolutePath) else listOf("open", file.absolutePath)
            DesktopOs.Linux -> listOf("xdg-open", (if (file.isFile) file.parentFile else file).absolutePath)
        }
        runCatching { ProcessBuilder(command).start() }
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Trimio" }
}
