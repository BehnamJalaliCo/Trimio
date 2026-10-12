package io.trimio.engine.motion.export

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.GpuPriority
import java.io.File

/**
 * Frames drawn by the graphics card (Windows: Skia on Direct3D 12, the backend Compose already
 * draws the app's window with), on the dedicated GPU when the machine has one. Each export
 * worker owns its device, context and surface; the frame is read back as 16-bit pixels.
 *
 * `-Dtrimio.render.gpu`: `auto` (default; Windows only), `on`, `off`. A failed start leaves
 * the worker on the CPU raster. A process that died inside a GPU render leaves [marker] behind;
 * the next one finds it and stays on the CPU (`-Dtrimio.render.gpu.marker` names the file; the
 * desktop app puts it in its cache).
 */
class GpuCanvas private constructor(private val device: Long, val context: DirectContext, val surface: Surface) : AutoCloseable {
    override fun close() {
        surface.close()
        context.flush()
        context.submit(true)
        context.close()
        runCatching { DirectX.call("disposeDevice", device) }
    }

    companion object {
        @Volatile private var distrusted = false

        /** A GPU frame did not match the CPU's: this process draws on the CPU from now on. */
        fun distrust() {
            distrusted = true
        }

        /** Whether exports should try the GPU on this machine at all. */
        val wanted: Boolean
            get() = !distrusted && when (System.getProperty("trimio.render.gpu")?.lowercase()) {
                "off" -> false
                "on" -> !crashedBefore()
                else -> System.getProperty("os.name").orEmpty().startsWith("Windows") && !crashedBefore()
            }

        /** A GPU surface of [info], or null where the GPU cannot draw (no device, no driver, not Windows). */
        fun open(info: ImageInfo): GpuCanvas? = runCatching {
            val adapter = DirectX.api.javaClass.getMethod("chooseAdapter", GpuPriority::class.java).invoke(DirectX.api, GpuPriority.Discrete) as Long
            val device = DirectX.call("createDirectXOffscreenDevice", adapter) as Long
            if (device == 0L) return null
            val pointer = DirectX.call("makeDirectXContext", device) as Long
            val context = DirectContext::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(pointer)
            val surface = Surface.makeRenderTarget(context, false, info)
            GpuCanvas(device, context, surface)
        }.getOrNull()

        /**
         * Skiko's Direct3D 12 device API (what its own offscreen and Swing renderers use). It is
         * internal to Skiko, so it is reached by name; a Skiko without it leaves rendering on the CPU.
         */
        private object DirectX {
            val api: Any by lazy { Class.forName("org.jetbrains.skiko.graphicapi.InternalDirectXApi").getField("INSTANCE").get(null) }

            fun call(name: String, arg: Long): Any? = api.javaClass.getMethod(name, Long::class.javaPrimitiveType).invoke(api, arg)
        }

        private val marker: File?
            get() = System.getProperty("trimio.render.gpu.marker")?.takeIf { it.isNotBlank() }?.let(::File)

        /** Closing the app mid-render is not a crash: a normal exit clears the marker. */
        private val exitHook: Unit by lazy {
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { marker?.takeIf { it.isFile }?.writeText("ok") } })
        }

        private fun crashedBefore(): Boolean = marker?.isFile == true && marker?.readText()?.trim() != "ok"

        /**
         * Runs a GPU render, leaving the marker while it is inside native GPU work: a crash there
         * keeps it, and later processes draw on the CPU.
         */
        fun <T> guarded(block: () -> T): T {
            val file = marker
            if (file != null) exitHook
            runCatching { file?.apply { parentFile?.mkdirs() }?.writeText("rendering") }
            try {
                return block()
            } finally {
                runCatching { file?.writeText("ok") }
            }
        }
    }
}
