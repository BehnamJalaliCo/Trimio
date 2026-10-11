package io.trimio.core.model.tools

import java.io.File

/**
 * The ffmpeg and ffprobe every desktop engine shells out to, and the H.264 encoder they use.
 *
 * Development machines and CI use the tools on the PATH with libx264. The packaged desktop app
 * bundles its own LGPL build and points `-Dtrimio.ffmpeg.dir` at it (docs/DESKTOP.md); on Windows
 * it also sets `-Dtrimio.h264.encoder=h264_mf`, the operating system's Media Foundation encoder,
 * because libx264 would make the bundled ffmpeg (and so its distribution terms) GPL.
 */
object MediaTools {
    /** Encoders [h264] knows how to drive. */
    const val LIBX264 = "libx264"
    const val MEDIA_FOUNDATION = "h264_mf"

    val ffmpeg: String get() = tool("ffmpeg")
    val ffprobe: String get() = tool("ffprobe")

    /** `-Dtrimio.h264.encoder`, else libx264. */
    val h264Encoder: String get() = System.getProperty("trimio.h264.encoder")?.takeIf { it.isNotBlank() } ?: LIBX264

    /**
     * Video encoder arguments. With libx264 the caller's own tuning ([x264], starting at `-c:v`)
     * is used as is; Media Foundation has no CRF, so it gets an unconstrained VBR at most
     * [maxrateMbps] (averaging three quarters of it) with closed GOPs of [gop] frames, which the
     * lossless joins of parallel renders rely on.
     */
    fun h264(x264: List<String>, maxrateMbps: Int, gop: Int): List<String> = when (h264Encoder) {
        MEDIA_FOUNDATION -> listOf(
            "-c:v", MEDIA_FOUNDATION, "-rate_control", "u_vbr", "-scenario", "archive",
            "-b:v", "${maxrateMbps * 3 / 4}M", "-maxrate", "${maxrateMbps}M", "-bufsize", "${maxrateMbps * 2}M", "-g", "$gop",
        )
        else -> x264
    }

    /** A bundled tool when `trimio.ffmpeg.dir` holds it (`name.exe` on Windows), else [name] from the PATH. */
    private fun tool(name: String): String {
        val dir = System.getProperty("trimio.ffmpeg.dir")?.takeIf { it.isNotBlank() } ?: return name
        return listOf("$name.exe", name).map { File(dir, it) }.firstOrNull { it.isFile }?.absolutePath ?: name
    }
}
