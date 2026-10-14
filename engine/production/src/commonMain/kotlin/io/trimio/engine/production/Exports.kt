package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.score.Compiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeIntLe
import kotlinx.io.writeShortLe
import kotlinx.io.writeString
import kotlin.math.roundToInt

/**
 * Where an export goes: the frame ([format], the compiler's "9:16", "16:9", "1:1" or "4:5"), the
 * short side in pixels (720, 1080, 1440, 2160) and the frame rate. The motion engine lays an edit
 * out on a 1080-wide grid; other sizes draw that layout at [scale], so a 4K file is drawn at 4K.
 */
data class ExportSpec(val format: String = "9:16", val shortEdge: Int = BASE, val fps: Int = 30) {
    val scale: Float get() = shortEdge / BASE.toFloat()

    /** The file's frame size in pixels. */
    val size: Pair<Int, Int>
        get() {
            val (w, h) = layout(format)
            return even(w * scale) to even(h * scale)
        }

    companion object {
        const val BASE = 1080

        /** The compiler's frame for [format] (its own table). */
        fun layout(format: String): Pair<Int, Int> = when (format.trim()) {
            "1:1" -> BASE to BASE
            "4:5" -> BASE to BASE * 5 / 4
            "16:9" -> BASE * 16 / 9 to BASE
            else -> BASE to BASE * 16 / 9
        }

        private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
    }
}

/** What a professional-editor package holds: its folder and every file written, the project files first. */
data class ProjectPackage(val folder: String, val files: List<String>)

/**
 * Exports of a finished [Edit]: the video again at another frame, size or rate (laid out anew by
 * the compiler for the frame, then drawn by the platform's encoder), captions, and the package for
 * Premiere, Final Cut and DaVinci. Nothing here re-directs: the score, words, cuts, pictures and
 * mastered sound are the edit's own.
 */
class EditExports(private val platform: ProductionPlatform) {

    /** The folder exports of [edit] go to: beside its finished render. */
    fun folder(edit: Edit): String = Path(platform.outputFor(edit.request)).parent?.toString() ?: "."

    /** Whether the device's encoder can write [spec] (phones top out below some 4K rates). */
    fun canEncode(spec: ExportSpec): Boolean = spec.size.let { (w, h) -> platform.canEncode(w, h, spec.fps) }

    /**
     * The video at [spec] to [out]; [onProgress] gets 0..1. The finished render itself when the
     * spec is the one it was made at.
     */
    suspend fun video(edit: Edit, spec: ExportSpec, out: String, onProgress: (Float) -> Unit = {}): String {
        val c = edit.production.compiled.composition
        if (spec == ExportSpec(edit.production.score.format, BASE_EDGE, c.fps) && SystemFileSystem.exists(Path(edit.output))) {
            onProgress(1f)
            return edit.output
        }
        val output = laidOut(edit, spec)
        val progress = { done: Int, total: Int -> onProgress(done / total.coerceAtLeast(1).toFloat()) }
        return platform.render(output.composition, edit.production.soundtrack.audio, edit.request, assets(edit), out, spec.scale, progress, edit.production.sourceSound(edit.request))
    }

    /**
     * A still of [edit] for the export screen ([width] px wide): the first moment a graphic is on
     * screen, drawn by the same renderer as the file. Null when it cannot be drawn.
     */
    suspend fun poster(edit: Edit, width: Int = POSTER_WIDTH): ImageBitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val output = edit.production.compiled
            val c = output.composition
            val at = output.beats.firstOrNull { it.recipe != edit.production.score.captions.recipe }?.let { it.at + POSTER_DELAY } ?: (c.duration * POSTER_SHARE)
            val previewer = Previewer(platform.measurer(), MotionFonts.load(), platform.previewShaders)
            val media = platform.media(edit.request, assets(edit), c.fps, width, (width * c.height / c.width.toFloat()).roundToInt())
            try {
                previewer.frame(output, media, at.coerceIn(0f, c.duration), width)
            } finally {
                (media as? AutoCloseable)?.close()
            }
        }.getOrNull()
    }

    /** SubRip captions of every spoken line, on the edit's clock. */
    fun srt(edit: Edit): String = ProjectFiles.srt(captions(edit))

    /** Writes [srt] of [edit] to [out] and returns its path. */
    suspend fun srtFile(edit: Edit, out: String): String = write(out, srt(edit))

    /**
     * The package for another editor in [folder]/[name]: FCPXML 1.10, a CMX 3600 EDL, the captions,
     * the mastered mix (WAV), and the motion graphics as a layer with alpha (ProRes 4444) where the
     * platform can write one; on phones the finished video at [spec] rides along as a reference
     * instead. [onProgress] follows the render (0..1).
     */
    suspend fun project(edit: Edit, spec: ExportSpec, folder: String, name: String, onProgress: (Float) -> Unit = {}): ProjectPackage {
        val dir = Path(folder)
        SystemFileSystem.createDirectories(dir)
        val output = laidOut(edit, spec)
        val assets = assets(edit)
        val c = output.composition
        val graphics = platform.renderGraphics(c, edit.request, assets, Path(dir, "$name-graphics.mov").toString(), spec.scale) { d, t ->
            onProgress(d / t.coerceAtLeast(1).toFloat())
        }
        val reference = if (graphics == null) inFolder(video(edit, spec, Path(dir, "$name.mp4").toString(), onProgress), Path(dir, "$name.mp4")) else null
        val mix = writeWav(Path(dir, "$name-mix.wav").toString(), edit.production.soundtrack.audio)
        val source = platform.probe(edit.request.media) ?: SourceFacts(edit.request.media.substringAfterLast('/'), null, 0f, 0, 0)
        val (w, h) = spec.size
        val timeline = ProjectFiles.Timeline(
            name = name, fps = spec.fps, width = w, height = h, frames = ProjectFiles.frames(c.duration, spec.fps),
            events = ProjectFiles.events(edit.production.edit, spec.fps),
            source = if (edit.request.kind == SourceKind.Audio) source.copy(width = 0, height = 0) else source,
            captions = captions(edit),
        )
        // Files beside the project: absolute where the source is a local file (desktop), by name where the package travels (phones).
        fun url(path: String?) = path?.let { if (source.url != null) fileUrl(it) else "./" + Path(it).name.replace(" ", "%20") }
        val files = listOf(
            write(Path(dir, "$name.fcpxml").toString(), ProjectFiles.fcpxml(timeline, url(graphics), url(mix), url(reference))),
            write(Path(dir, "$name.edl").toString(), ProjectFiles.edl(timeline, graphics?.substringAfterLast('/'))),
            write(Path(dir, "$name.srt").toString(), ProjectFiles.srt(timeline.captions)),
        ) + listOfNotNull(graphics, reference, mix)
        onProgress(1f)
        return ProjectPackage(folder, files)
    }

    /** [file] at [target] (copied there when the export reused the finished render). */
    private suspend fun inFolder(file: String, target: Path): String = withContext(Dispatchers.Default) {
        if (Path(file) != target) SystemFileSystem.source(Path(file)).buffered().use { from -> SystemFileSystem.sink(target).buffered().use { from.transferTo(it) } }
        target.toString()
    }

    private fun laidOut(edit: Edit, spec: ExportSpec): Compiler.Output =
        edit.production.relaid(spec.format, spec.fps) ?: error("this edit cannot be laid out for ${spec.format} again")

    private fun captions(edit: Edit): List<ProjectFiles.Caption> {
        val p = edit.production
        val transcript = p.source?.input?.transcript ?: edit.transcript
        return ProjectFiles.captions(p.edit, transcript, p.score.captions.maxWords)
    }

    /** The creator's attachments as the edit read them, or read again (the renderer opens each under its own id). */
    private suspend fun assets(edit: Edit): List<UserAsset> = edit.assets.takeIf { it.size == edit.request.assets.size }
        ?: edit.request.assets.mapIndexed { i, a -> platform.asset(a.source, "asset-$i", a.caption) }

    private suspend fun write(path: String, text: String): String = withContext(Dispatchers.Default) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
        path
    }

    /** 16-bit PCM WAV, the mix's own rate and channel count (mono). */
    private suspend fun writeWav(path: String, audio: PcmAudio): String = withContext(Dispatchers.Default) {
        val samples = audio.samples
        val bytes = samples.size * 2
        SystemFileSystem.sink(Path(path)).buffered().use { out ->
            val header = Buffer()
            header.writeString("RIFF"); header.writeIntLe(WAV_HEADER + bytes); header.writeString("WAVE")
            header.writeString("fmt "); header.writeIntLe(FMT_SIZE); header.writeShortLe(1); header.writeShortLe(1)
            header.writeIntLe(audio.sampleRate); header.writeIntLe(audio.sampleRate * 2); header.writeShortLe(2); header.writeShortLe(BITS)
            header.writeString("data"); header.writeIntLe(bytes)
            out.write(header, header.size)
            val chunk = Buffer()
            for (s in samples) {
                chunk.writeShortLe((s.coerceIn(-1f, 1f) * PCM_MAX).roundToInt().toShort())
                if (chunk.size >= CHUNK) out.write(chunk, chunk.size)
            }
            out.write(chunk, chunk.size)
        }
        path
    }

    private fun fileUrl(path: String): String =
        if (path.contains("://")) path else "file://" + (if (path.startsWith("/")) "" else "/") + path.replace('\\', '/').replace(" ", "%20")

    private companion object {
        const val BASE_EDGE = ExportSpec.BASE
        const val POSTER_WIDTH = 216
        const val POSTER_DELAY = 0.6f
        const val POSTER_SHARE = 0.2f
        const val WAV_HEADER = 36
        const val FMT_SIZE = 16
        const val BITS: Short = 16
        const val PCM_MAX = 32_767f
        const val CHUNK = 1L shl 16
    }
}
