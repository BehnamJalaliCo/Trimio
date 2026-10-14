package io.trimio.engine.production

import io.trimio.engine.autopilot.longform.YouTubeChapters
import io.trimio.engine.autopilot.longform.YouTubePackage
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * A long-form edit's companions beside its MP4 (`<out>.srt`, `<out>.vtt`, `<out>.youtube.txt`): the subtitles to
 * upload, and one text the creator copies into YouTube Studio — titles, the description's chapters, the thumbnail
 * lines and the Shorts worth cutting. They live and go with the video file ([paths]).
 */
object YouTubeFiles {
    fun paths(out: String): List<String> = listOf("$out.srt", "$out.vtt", "$out$SUMMARY")

    fun write(out: String, pack: YouTubePackage, persian: Boolean) {
        runCatching {
            put("$out.srt", pack.srt)
            put("$out.vtt", pack.vtt)
            put("$out$SUMMARY", summary(pack, persian))
        }
    }

    /** The summary written beside [out], or null for an edit that has none (a reel). */
    fun summary(out: String): String? = runCatching {
        Path("$out$SUMMARY").takeIf { SystemFileSystem.exists(it) }?.let { p -> SystemFileSystem.source(p).buffered().use { it.readString() } }
    }.getOrNull()

    fun summary(pack: YouTubePackage, persian: Boolean): String = buildString {
        fun t(fa: String, en: String) = if (persian) fa else en
        if (pack.titles.isNotEmpty()) {
            append(t("عنوان‌ها", "Titles")).append('\n')
            pack.titles.forEach { append("• ").append(it).append('\n') }
            append('\n')
        }
        if (pack.description.isNotBlank()) append(t("فصل‌ها (برای توضیحات)", "Chapters (for the description)")).append('\n').append(pack.description).append("\n\n")
        if (pack.thumbnail.isNotEmpty()) {
            append(t("متن تامبنیل", "Thumbnail text")).append('\n')
            pack.thumbnail.forEach { append("• ").append(it).append('\n') }
            append('\n')
        }
        if (pack.shorts.isNotEmpty()) {
            append(t("پیشنهاد شورتس", "Shorts to cut")).append('\n')
            pack.shorts.forEach { append("• ").append(YouTubeChapters.stamp(it.from)).append("–").append(YouTubeChapters.stamp(it.to)).append(" ").append(it.title).append('\n') }
        }
    }.trimEnd()

    private fun put(path: String, text: String) = SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }

    private const val SUMMARY = ".youtube.txt"
}
