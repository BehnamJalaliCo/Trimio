package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.Raster
import io.trimio.engine.models.IllustrationAddOns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The generator add-on ([IllustrationAddOns.sdxs], SDXS-512 one step) through stable-diffusion.cpp: loaded on the first
 * picture (about 1.2 GB) and freed by [close] once a film's pictures are drawn, so it never sits beside the director.
 */
class SdxsEngine(private val modelPath: String, private val threads: Int) : DiffusionEngine, AutoCloseable {
    override val modelId: String = IllustrationAddOns.sdxs.id
    override val recipe: DiffusionRecipe = Recipes.sdxs

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun generate(job: DiffusionJob): Raster? = mutex.withLock {
        withContext(Dispatchers.Default) {
            // The library loads with the first look at it; the engine never calls into one that is not there.
            if (handle == 0L && SdNative.available) handle = runCatching { SdNative.init(modelPath, threads) }.getOrDefault(0L)
            if (handle == 0L) return@withContext null
            val rgb = SdNative.generate(handle, job.prompt, job.negative, job.width, job.height, job.seed, job.steps, job.cfg) ?: return@withContext null
            if (rgb.size < job.width * job.height * CHANNELS) return@withContext null
            Raster(job.width, job.height, IntArray(job.width * job.height) { i ->
                val r = rgb[i * CHANNELS].toInt() and BYTE
                val g = rgb[i * CHANNELS + 1].toInt() and BYTE
                val b = rgb[i * CHANNELS + 2].toInt() and BYTE
                OPAQUE or (r shl RED) or (g shl GREEN) or b
            })
        }
    }

    override fun close() {
        if (handle != 0L) {
            SdNative.free(handle)
            handle = 0L
        }
    }

    private companion object {
        const val CHANNELS = 3
        const val BYTE = 0xFF
        const val OPAQUE = -0x1000000
        const val RED = 16
        const val GREEN = 8
    }
}
