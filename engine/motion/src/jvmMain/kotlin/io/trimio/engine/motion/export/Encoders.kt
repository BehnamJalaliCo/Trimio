package io.trimio.engine.motion.export

import io.trimio.core.model.tools.MediaTools
import java.util.concurrent.TimeUnit

/**
 * The H.264 encoder an export uses. The graphics card's own encoder when ffmpeg has one that
 * works on this machine (NVIDIA NVENC, Intel Quick Sync, AMD AMF: all in the LGPL ffmpeg build,
 * loaded from the driver at run time), else the software one [MediaTools] names (libx264 in
 * development, Media Foundation in the Windows app).
 *
 * Each candidate is tried once per process on a tiny clip with the exact settings an export
 * uses, so a listed encoder without its GPU or driver (a laptop without NVIDIA, a server) is
 * never chosen. `-Dtrimio.h264.hw=off` keeps the software encoder.
 */
object Encoders {
    const val NVENC = "h264_nvenc"
    const val QSV = "h264_qsv"
    const val AMF = "h264_amf"
    private val HARDWARE = listOf(NVENC, QSV, AMF)

    /** The hardware encoder that works here, or null; probed on first use. */
    val hardware: String? by lazy { if (System.getProperty("trimio.h264.hw") == "off") null else probe() }

    /** Encoder name for the report: the hardware one, or the software one. */
    val name: String get() = hardware ?: MediaTools.h264Encoder

    /**
     * Arguments from `-c:v` on. [x264] is the software tuning (libx264); a hardware encoder gets a
     * quality-targeted VBR capped at [maxrateMbps] with closed GOPs of [gop] frames (the parallel
     * parts are joined without re-encoding).
     */
    fun args(x264: List<String>, maxrateMbps: Int, gop: Int, encoder: String? = hardware): List<String> {
        val avg = "${maxrateMbps * 3 / 4}M"
        val rate = listOf("-b:v", avg, "-maxrate", "${maxrateMbps}M", "-bufsize", "${maxrateMbps * 2}M", "-g", "$gop")
        return when (encoder) {
            NVENC -> listOf("-c:v", NVENC, "-preset", "p5", "-tune", "hq", "-rc", "vbr", "-cq", "19", "-profile:v", "high", "-spatial-aq", "1") + rate
            QSV -> listOf("-c:v", QSV, "-preset", "slow", "-profile:v", "high") + rate
            AMF -> listOf("-c:v", AMF, "-quality", "quality", "-rc", "vbr_peak", "-profile:v", "high") + rate
            else -> MediaTools.h264(x264, maxrateMbps, gop)
        }
    }

    private fun probe(): String? {
        val listed = runCatching { run(listOf(MediaTools.ffmpeg, "-hide_banner", "-encoders"))?.second }.getOrNull().orEmpty()
        return HARDWARE.filter { " $it " in listed }.firstOrNull(::works)
    }

    /** Encodes a few frames with [encoder] exactly as an export would; true when ffmpeg succeeds. */
    private fun works(encoder: String): Boolean {
        val cmd = listOf(MediaTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30", "-frames:v", "8", "-vf", "format=yuv420p") +
            args(emptyList(), 4, 30, encoder) + listOf("-f", "null", "-")
        return run(cmd)?.first == 0
    }

    private fun run(cmd: List<String>): Pair<Int, String>? {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        if (!p.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(PROBE_SECONDS * MS)
        return p.exitValue() to out.toString()
    }

    private const val PROBE_SECONDS = 15L
    private const val MS = 1000L
}
