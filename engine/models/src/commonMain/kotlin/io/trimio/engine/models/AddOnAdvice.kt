package io.trimio.engine.models

/**
 * «پیشنهاد برای این دستگاه»: the add-ons worth downloading on this device, one tap. Add-ons run as
 * pipeline stages one at a time ([AddOnFit] rates each alone), except retrieval's pair: the
 * embedder stays resident beside the director while it plans, and the reranker joins it for the
 * pictures, so those three must fit together. A phone gets what fits (12 GB: the Essential pair
 * and the Pro/Ultra stages that fit alone); a laptop (16 GB + a 4 GB GPU) also gets the large
 * reranker. Alternatives (bge-m3, the 4B embedder) are offered on the screen, never pushed.
 */
object AddOnAdvice {
    /** What one add-on costs on this device and whether it is in the one-tap set. */
    data class Offer(val spec: ModelSpec, val fit: ModelFit, val recommended: Boolean, val footprintBytes: Long)

    /** Every add-on offered on [device] (helpers left out), each rated, with the recommended ones marked. */
    fun offers(device: DeviceMemory, director: ModelSpec? = null, catalog: List<ModelSpec> = ModelCatalog.all): List<Offer> {
        val byId = catalog.associateBy { it.id }
        val picked = recommended(device, director, catalog).map { it.id }.toSet()
        return catalog.filter { it.addOn?.helper == false }.mapNotNull { spec ->
            val fit = AddOnFit.of(spec, device) { byId[it] ?: ModelCatalog.byId(it) }
            if (!fit.runnable) return@mapNotNull null
            Offer(spec, fit, spec.id in picked, DeviceFit.footprint(spec) { byId[it] ?: ModelCatalog.byId(it) } - DeviceFit.APP_BYTES)
        }
    }

    /** The one-tap set for [device] beside [director] (the default director when null). */
    fun recommended(device: DeviceMemory, director: ModelSpec? = null, catalog: List<ModelSpec> = ModelCatalog.all): List<ModelSpec> {
        val byId = catalog.associateBy { it.id }
        fun lookup(id: String) = byId[id] ?: ModelCatalog.byId(id)
        fun alone(spec: ModelSpec) = DeviceFit.footprint(spec, ::lookup) - DeviceFit.APP_BYTES
        fun comfortable(spec: ModelSpec) = spec.id in byId && AddOnFit.of(spec, device, ::lookup).comfortable
        val brain = director ?: ModelCatalog.qwen35_4b
        val base = DeviceFit.footprint(brain, ::lookup)
        val out = mutableListOf<ModelSpec>()
        // Retrieval: the embedder beside the director, then the best reranker that still fits with both.
        val embedder = TextAddOns.embedder
        if (comfortable(embedder) && DeviceFit.rate(base + alone(embedder), device).comfortable) {
            out += embedder
            val rerankers = listOf(TextAddOns.rerankerFor(device), TextAddOns.reranker06b).distinct()
            rerankers.firstOrNull { r -> comfortable(r) && DeviceFit.rate(base + alone(embedder) + alone(r), device).comfortable }?.let { out += it }
        }
        // Every other add-on runs as a stage of its own: recommended when it is comfortable alone, Ultra only from 12 GB.
        val retrieval = setOf(AddOnGroup.Retrieval)
        catalog.filter { s ->
            val a = s.addOn ?: return@filter false
            !a.helper && !s.experimental && a.group !in retrieval && (a.tier != AddOnTier.Ultra || device.ramGb >= ULTRA_RAM) && comfortable(s)
        }.forEach { out += it }
        // The visual judge (Ultra retrieval) only where it fits beside the director as a stage.
        TextAddOns.vlReranker2b.takeIf { device.ramGb >= ULTRA_RAM && comfortable(it) && DeviceFit.rate(base + alone(it), device).comfortable }?.let { out += it }
        return out.distinctBy { it.id }
    }

    /** Files the one-tap set downloads, companions included, and their total size. */
    fun download(specs: List<ModelSpec>, catalog: List<ModelSpec> = ModelCatalog.all): Pair<List<ModelSpec>, Long> {
        val byId = catalog.associateBy { it.id }
        val files = specs.flatMap { s -> listOf(s) + s.companions.mapNotNull { byId[it] ?: ModelCatalog.byId(it) } }.distinctBy { it.id }
        return files to files.sumOf { it.sizeBytes }
    }

    private const val ULTRA_RAM = 12
}
