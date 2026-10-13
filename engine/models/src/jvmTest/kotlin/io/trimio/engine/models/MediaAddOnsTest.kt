package io.trimio.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaAddOnsTest {
    private val all = MediaAddOns.all

    @Test
    fun everyEntryIsAVerifiedAddOnWithItsCompanionsInTheCatalogue() {
        assertTrue(all.isNotEmpty())
        all.forEach { spec ->
            val addOn = spec.addOn
            assertTrue(addOn != null, "${spec.id} has tier and group")
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")), spec.id)
            assertTrue(spec.urls.first().startsWith(ModelCatalog.CDN) && spec.urls.size == 2, spec.id)
            assertTrue(spec.license in setOf("MIT", "Apache-2.0"), "${spec.id}: ${spec.license}")
            spec.companions.forEach { assertTrue(ModelCatalog.byId(it) != null, "${spec.id} → $it") }
            assertTrue(spec in ModelCatalog.all)
        }
        // Helpers (tokenizers, filters, dictionaries) belong to a model that lists them.
        all.filter { it.addOn!!.helper }.forEach { h -> assertTrue(all.any { h.id in it.companions }, "${h.id} has an owner") }
    }

    @Test
    fun addOnsNeverLeakIntoSpeechOrDirectorChoices() {
        assertEquals(ModelCatalog.whisperLargeV3Q5, ModelCatalog.bestFor(ModelKind.Speech, 16))
        assertTrue(all.none { it.kind == ModelKind.Language || it.isDefault })
    }

    @Test
    fun eachAddOnRunsAloneOnTheOwnersDevices() {
        val phone = DeviceMemory.phone(12)
        val laptop = DeviceMemory.desktop(16)
        all.forEach { spec ->
            assertTrue(AddOnFit.of(spec, phone).comfortable, "${spec.id} on a 12 GB phone: ${AddOnFit.of(spec, phone)}")
            assertTrue(AddOnFit.of(spec, laptop).comfortable, "${spec.id} on the laptop")
        }
        val tiers = AddOnTier.entries.associateWith { AddOnFit.offered(it, phone, all).map { s -> s.id } }
        assertTrue(tiers.values.flatten().none { "vad" in it }, "speech detection was removed: it dropped spoken words")
        assertTrue("yunet-2023mar" in tiers.getValue(AddOnTier.Pro))
        assertTrue(tiers.values.flatten().none { it.endsWith("-spm") || it.endsWith("-tokens") }, "helpers are not rows")
    }

    @Test
    fun aLaptopOnlyAddOnIsNotOfferedOnAPhone() {
        val big = MediaAddOns.faces.copy(id = "x", addOn = MediaAddOns.faces.addOn!!.copy(devices = setOf(DeviceClass.Laptop)))
        assertEquals(ModelFit.TooBig, AddOnFit.of(big, DeviceMemory.phone(12)))
        assertTrue(AddOnFit.of(big, DeviceMemory.desktop(16)).runnable)
    }
}
