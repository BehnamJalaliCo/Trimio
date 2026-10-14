package io.trimio.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IllustrationAddOnsTest {
    private val phone = DeviceMemory.phone(12)
    private val laptop = DeviceMemory.desktop(16)

    @Test
    fun generatorsAreCatalogueAddOnsWithTheirCompanions() {
        for (spec in IllustrationAddOns.all) {
            // Not offered until the engine draws on the app's ggml (9.2).
            assertTrue(spec !in ModelCatalog.all, spec.id)
            assertEquals(AddOnRuntime.Diffusion, spec.addOn?.runtime)
            assertEquals(AddOnGroup.Picture, spec.addOn?.group)
            assertEquals(64, spec.sha256.length)
            assertTrue(spec.license in setOf("Apache-2.0", "MIT", "OpenRAIL++-M"), "${spec.id}: ${spec.license}")
        }
        val ids = IllustrationAddOns.all.map { it.id }.toSet()
        IllustrationAddOns.klein.companions.forEach { id -> assertTrue(id in ids, id) }
    }

    @Test
    fun deviceFitMatchesTheOwnersDevices() {
        // The one-step generator runs on the 12 GB phone and the laptop; FLUX.2 klein (≈ 5.2 GB with its encoder) only on the laptop.
        assertTrue(AddOnFit.of(IllustrationAddOns.sdxs, phone).comfortable)
        assertTrue(AddOnFit.of(IllustrationAddOns.sdxs, laptop).comfortable)
        assertEquals(ModelFit.TooBig, AddOnFit.of(IllustrationAddOns.klein, phone))
        assertTrue(AddOnFit.of(IllustrationAddOns.klein, laptop).runnable)
        // Neither is offered while the engine cannot draw on the app's ggml.
        assertTrue(IllustrationAddOns.sdxs.id !in AddOnFit.offered(AddOnTier.Pro, phone).map { it.id })
        assertTrue(IllustrationAddOns.klein.id !in AddOnFit.offered(AddOnTier.Ultra, laptop).map { it.id })
    }
}
