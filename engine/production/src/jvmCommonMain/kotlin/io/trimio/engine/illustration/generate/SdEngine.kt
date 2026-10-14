package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.Raster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A generator add-on through stable-diffusion.cpp: SDXS-512 (one file, one step) or FLUX.2 klein 4B (the diffusion model
 * with its Qwen3 text encoder and VAE; four steps, the library's sketch as its init picture and the film's first picture
 * as its style reference). The model loads with the first picture and is freed by [close] once a film's pictures are
 * drawn, so it never sits beside the director.
 */
class SdEngine(
    override val modelId: String,
    private val files: Files,
    private val threads: Int,
    private val placement: Placement = Placement(),
) : DiffusionEngine, AutoCloseable {
    /** A model's files: one ([model] alone), or a diffusion model with its [textEncoder] and [vae]. */
    data class Files(val model: String, val textEncoder: String? = null, val vae: String? = null)

    /**
     * Where each part runs, as the speed test chose: [backend] in sd.cpp's assignments ("" the default device), [maxVram]
     * the GPU budget, [gpuLibrary] the GPU build (the phone's OpenCL one).
     */
    data class Placement(val backend: String = "", val maxVram: String = "", val gpuLibrary: Boolean = false) {
        val onGpu: Boolean get() = gpuLibrary && backend != "cpu"
    }

    /**
     * The model's recipe; on a processor alone (no GPU build loaded) the studio illustrator draws at 512 px instead of 768
     * (2.3 times fewer pixels to denoise: minutes become a couple of minutes), which the film's hero size still holds.
     */
    override val recipe: DiffusionRecipe = (Recipes.of(modelId) ?: Recipes.sdxs).let { r ->
        if (r.size > CPU_SIZE && !onGpu) r.copy(size = CPU_SIZE) else r
    }

    private val onGpu: Boolean get() = placement.onGpu || runCatching { SdNative.variant }.getOrDefault("") in GPU_BUILDS

    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun generate(job: DiffusionJob): Raster? = mutex.withLock {
        val progress = currentCoroutineContext()[DrawProgress]?.let { p -> SdNative.StepListener { step, steps -> p.onStep(step, steps) } }
        withContext(Dispatchers.Default) {
            // The library loads with the first look at it; the engine never calls into one that is not there.
            if (handle == 0L) SdNative.wantGpu = SdNative.wantGpu || placement.gpuLibrary
            if (handle == 0L && SdNative.available) handle = runCatching { load() }.getOrDefault(0L)
            if (handle == 0L) return@withContext null
            val init = job.init?.takeIf { recipe.img2img }?.let { rgb(it.resized(job.width, job.height)) }
            val refs = job.references.takeIf { recipe.references && it.isNotEmpty() }?.map { rgb(it.resized(job.width, job.height)) }?.toTypedArray()
            val out = SdNative.generate(handle, job.prompt, job.negative, job.width, job.height, job.seed, job.steps, job.cfg, init, job.strength, refs, progress)
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

    private fun load(): Long = files.textEncoder?.let { SdNative.initParts(files.model, it, files.vae.orEmpty(), threads, placement.backend, placement.maxVram) }
        ?: SdNative.init(files.model, threads, placement.backend, placement.maxVram)

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
        const val CPU_SIZE = 512
        val GPU_BUILDS = setOf("vulkan", "metal", "cuda")
        const val CHANNELS = 3
        const val BYTE = 0xFF
        const val OPAQUE = -0x1000000
        const val ALPHA = 24
        const val RED = 16
        const val GREEN = 8
    }
}
