package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.Raster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A generator add-on through stable-diffusion.cpp: SDXS-512 (one file, one step) or FLUX.2 klein 4B (the diffusion model
 * with its Qwen3 text encoder and VAE; four steps, the library's sketch as its init picture and the film's first picture
 * as its style reference). The model loads with the first picture and is freed by [close] once a film's pictures are
 * drawn, so it never sits beside the director.
 */
class SdEngine(override val modelId: String, private val files: Files, private val threads: Int) : DiffusionEngine, AutoCloseable {
    /** A model's files: one ([model] alone), or a diffusion model with its [textEncoder] and [vae]. */
    data class Files(val model: String, val textEncoder: String? = null, val vae: String? = null)

    override val recipe: DiffusionRecipe = Recipes.of(modelId) ?: Recipes.sdxs

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun generate(job: DiffusionJob): Raster? = mutex.withLock {
        withContext(Dispatchers.Default) {
            // The library loads with the first look at it; the engine never calls into one that is not there.
            if (handle == 0L && SdNative.available) handle = runCatching { load() }.getOrDefault(0L)
            if (handle == 0L) return@withContext null
            val init = job.init?.takeIf { recipe.img2img }?.let { rgb(it.resized(job.width, job.height)) }
            val refs = job.references.takeIf { recipe.references && it.isNotEmpty() }?.map { rgb(it.resized(job.width, job.height)) }?.toTypedArray()
            val out = SdNative.generate(handle, job.prompt, job.negative, job.width, job.height, job.seed, job.steps, job.cfg, init, job.strength, refs)
                ?: return@withContext null
            if (out.size < job.width * job.height * CHANNELS) return@withContext null
            Raster(job.width, job.height, IntArray(job.width * job.height) { i ->
                val r = out[i * CHANNELS].toInt() and BYTE
                val g = out[i * CHANNELS + 1].toInt() and BYTE
                val b = out[i * CHANNELS + 2].toInt() and BYTE
                OPAQUE or (r shl RED) or (g shl GREEN) or b
            })
        }
    }

    private fun load(): Long =
        files.textEncoder?.let { SdNative.initParts(files.model, it, files.vae.orEmpty(), threads) } ?: SdNative.init(files.model, threads)

    /** A raster as RGB bytes on white (models take opaque pictures). */
    private fun rgb(r: Raster): ByteArray {
        val out = ByteArray(r.width * r.height * CHANNELS)
        for (i in r.argb.indices) {
            val p = r.argb[i]
            val a = (p ushr ALPHA) and BYTE
            fun over(c: Int) = ((c * a + BYTE * (BYTE - a)) / BYTE).toByte()
            out[i * CHANNELS] = over((p shr RED) and BYTE)
            out[i * CHANNELS + 1] = over((p shr GREEN) and BYTE)
            out[i * CHANNELS + 2] = over(p and BYTE)
        }
        return out
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
        const val ALPHA = 24
        const val RED = 16
        const val GREEN = 8
    }
}
