package io.trimio.engine.motion.export

import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.StillMedia
import org.jetbrains.skia.Image
import java.io.File

/**
 * The creator's attached files as media sources for the renderer: pictures decoded once (PNG
 * transparency kept, so a logo sits on its tile without a box), clips streamed through ffmpeg
 * under their own id beside the main footage.
 */
object UserMediaFiles {
    private val VIDEO = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp")

    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO

    /** [file] under [id]; a clip is scaled to cover [width]×[height] like the main footage. */
    fun source(id: String, file: File, fps: Int, width: Int, height: Int): MediaSource =
        if (isVideo(file)) FfmpegFootage(file, fps, width, height, id) else StillMedia(id, Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap())
}
