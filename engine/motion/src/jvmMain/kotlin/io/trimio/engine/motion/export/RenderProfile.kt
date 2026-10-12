package io.trimio.engine.motion.export

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Where an export's time went, summed over its workers: drawing the scene (the footage wait
 * inside it counted apart), reading the frame out as 16-bit pixels, and handing it to the
 * encoder (time blocked there means the encoder is the slower side). Filled while
 * [MotionExporter] runs; [summary] is one line for logs and the technical report.
 */
class RenderProfile {
    val frames = AtomicInteger()
    val drawNs = AtomicLong()

    /** CPU time of the drawing threads (on a busy machine, wall time also counts other programs' turns). */
    val drawCpuNs = AtomicLong()
    val footageNs = AtomicLong()
    val readNs = AtomicLong()
    val encodeWaitNs = AtomicLong()

    @Volatile var wallNs = 0L
    @Volatile var workers = 0
    @Volatile var encoder = ""
    @Volatile var canvas = ""
    @Volatile var width = 0
    @Volatile var height = 0

    val seconds: Float get() = wallNs / NANOS
    val fps: Float get() = if (wallNs <= 0) 0f else frames.get() / seconds

    /** Milliseconds per frame of one worker in a stage. */
    fun msPerFrame(ns: AtomicLong): Float = if (frames.get() == 0) 0f else ns.get() / MILLION / frames.get()

    /** "render 1980 frames 1080×1920 in 120.4 s (16.4 fps; 2 workers, raster, libx264): per frame draw 80.1 ms (cpu 75.0, footage 9.2), read 4.0, encoder wait 30.2". */
    fun summary(): String =
        "render ${frames.get()} frames ${width}×$height in ${one(seconds)} s (${one(fps)} fps; $workers workers, $canvas, $encoder): per frame " +
            "draw ${one(msPerFrame(drawNs))} ms (cpu ${one(msPerFrame(drawCpuNs))}, footage ${one(msPerFrame(footageNs))}), " +
            "read ${one(msPerFrame(readNs))}, encoder wait ${one(msPerFrame(encodeWaitNs))}"

    private fun one(v: Float) = (kotlin.math.round(v * TEN) / TEN).toString()

    private companion object {
        const val NANOS = 1e9f
        const val MILLION = 1e6f
        const val TEN = 10f
    }
}
