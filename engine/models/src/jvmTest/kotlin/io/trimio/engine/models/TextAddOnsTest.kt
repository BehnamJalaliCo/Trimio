package io.trimio.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextAddOnsTest {
    @Test
    fun everyTextAddOnIsInTheCatalogueWithAVerifiedHashAndAnAllowedLicence() {
        for (s in TextAddOns.all) {
            assertEquals(s, ModelCatalog.byId(s.id), s.id)
            assertEquals(64, s.sha256.length)
            assertTrue(s.license in setOf("Apache-2.0", "MIT"), "${s.id}: ${s.license}")
            assertTrue(s.urls.first().startsWith(ModelCatalog.CDN) && s.urls.last().startsWith("https://huggingface.co/"))
            assertEquals(AddOnGroup.Retrieval, s.addOn?.group)
            assertEquals(AddOnRuntime.Llama, s.addOn?.runtime)
            // Never the f16 EmbeddingGemma (NaNs): quantised or bf16 files only.
            assertFalse(s.fileName.lowercase().contains("f16"), s.fileName)
            s.companions.forEach { c -> assertTrue(ModelCatalog.byId(c) != null, "${s.id} companion $c") }
        }
        assertEquals(ModelKind.Embedding, TextAddOns.embedder.kind)
    }

    @Test
    fun aTwelveGigabytePhoneGetsTheEssentialPairAndNoLaptopModels() {
        val phone = DeviceMemory.phone(12)
        val picked = AddOnAdvice.recommended(phone)
        assertTrue(TextAddOns.embedder in picked, picked.map { it.id }.toString())
        assertTrue(TextAddOns.reranker06b in picked)
        assertFalse(TextAddOns.reranker4b in picked || TextAddOns.embedding4b in picked)
        // Laptop-only add-ons are not even offered on a phone.
        val offered = AddOnAdvice.offers(phone).map { it.spec.id }
        assertFalse(TextAddOns.reranker4b.id in offered)
        assertTrue(TextAddOns.bgeM3.id in offered)
    }

    @Test
    fun aSixteenGigabyteLaptopGetsTheLargeReranker() {
        val laptop = DeviceMemory.desktop(16)
        val picked = AddOnAdvice.recommended(laptop, ModelCatalog.qwen35_4b)
        assertTrue(TextAddOns.reranker4b in picked, picked.map { it.id }.toString())
        assertTrue(TextAddOns.embedder in picked)
        // The alternative embedders are offered but never pushed.
        assertFalse(TextAddOns.bgeM3 in picked || TextAddOns.embedding4b in picked)
        assertTrue(AddOnAdvice.offers(laptop).any { it.spec == TextAddOns.embedding4b })
    }

    @Test
    fun theOneTapDownloadCountsCompanions() {
        val (files, bytes) = AddOnAdvice.download(listOf(TextAddOns.vlReranker2b))
        assertEquals(listOf(TextAddOns.vlReranker2b.id, TextAddOns.vlReranker2bEyes.id), files.map { it.id })
        assertEquals(TextAddOns.vlReranker2b.sizeBytes + TextAddOns.vlReranker2bEyes.sizeBytes, bytes)
    }

    @Test
    fun aSmallPhoneIsNeverPushedPastWhatFitsBesideItsDirector() {
        for (ram in listOf(6, 8, 12, 16)) {
            val device = DeviceMemory.phone(ram)
            val picked = AddOnAdvice.recommended(device)
            val retrieval = picked.filter { it.addOn?.group == AddOnGroup.Retrieval && it != TextAddOns.vlReranker2b }
            val together = DeviceFit.footprint(ModelCatalog.qwen35_4b) + retrieval.sumOf { DeviceFit.footprint(it) - DeviceFit.APP_BYTES }
            assertTrue(retrieval.isEmpty() || DeviceFit.rate(together, device).comfortable, "$ram GB: ${retrieval.map { it.id }}")
        }
    }
}
