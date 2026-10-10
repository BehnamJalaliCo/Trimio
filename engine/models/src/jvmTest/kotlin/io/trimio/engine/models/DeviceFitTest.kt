package io.trimio.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceFitTest {

    private val phones = listOf(8, 12, 16, 24).map(DeviceMemory::phone)
    private val desktop = DeviceMemory.desktop(32)
    private val directors = ModelCatalog.all.filter { it.kind == ModelKind.Language }

    private fun fit(spec: ModelSpec, device: DeviceMemory) = DeviceFit.of(spec, device)

    @Test
    fun everyDirectorCarriesItsRuntimeNumbers() {
        directors.forEach { assertTrue(it.kvBytesPerToken > 0 && it.vocabSize > 0, "${it.id} needs kv/vocab for an honest budget") }
        assertTrue(ModelCatalog.qwen35_08bLooker.vocabSize > 0, "the looker is a model of its own")
    }

    @Test
    fun theDefaultRunsEverywhereAndIsComfortableFrom12Gb() {
        val default = ModelCatalog.qwen35_4b
        phones.forEach { assertTrue(fit(default, it).runnable, "default must run on ${it.ramGb} GB") }
        listOf(12, 16, 24).forEach { assertEquals(ModelFit.Recommended, fit(default, DeviceMemory.phone(it))) }
        // Without its eyes it is comfortable even on 8 GB: the fallback when the phone is tight.
        assertTrue(DeviceFit.report(default, DeviceMemory.phone(8)).blindFit.comfortable)
    }

    @Test
    fun the9bHasAQuantFor12GbPhones() {
        val phone12 = DeviceMemory.phone(12)
        val light = ModelCatalog.qwen35_9bLight
        assertTrue(fit(light, phone12).comfortable, "light 9B on 12 GB: ${DeviceFit.report(light, phone12)}")
        assertTrue(DeviceFit.footprint(light) < DeviceFit.footprint(ModelCatalog.qwen35_9b))
        assertEquals(ModelFit.TooBig, fit(light, DeviceMemory.phone(8)))
        assertEquals(ModelCatalog.visionFor(ModelCatalog.qwen35_9b), ModelCatalog.visionFor(light), "both 9B quants share one projector")
    }

    @Test
    fun theExperimentalMoeIsForBigDevicesOnlyAndNeverRatedAboveExperimental() {
        val moe = ModelCatalog.qwen36_35bA3b
        assertEquals(ModelFit.TooBig, fit(moe, DeviceMemory.phone(12)))
        assertEquals(ModelFit.TooBig, fit(moe, DeviceMemory.phone(16)))
        assertEquals(ModelFit.Experimental, fit(moe, DeviceMemory.phone(24)))
        assertEquals(ModelFit.Experimental, fit(moe, desktop), "memory is ample on a desktop; the badge is about calibration")
        assertEquals(listOf(ModelCatalog.qwen36_35bA3bEyes), ModelCatalog.visionFor(moe))
        assertTrue(moe.experimental && moe.activeParamsB == 3f)
    }

    @Test
    fun rowLookupEmbeddingsStayMapped() {
        // Gemma 4's 2.3 GB per-layer embedding table is read a row at a time and never counted whole.
        val gemma = ModelCatalog.gemma4E4b
        assertTrue(DeviceFit.report(gemma, DeviceMemory.phone(12)).blindFootprintBytes < gemma.sizeBytes)
        assertEquals(ModelFit.Recommended, fit(gemma, DeviceMemory.phone(12)))
    }

    @Test
    fun moreMemoryNeverRatesWorse() {
        (directors + ModelCatalog.all.filter { it.kind == ModelKind.Speech }).forEach { spec ->
            val fits = phones.map { fit(spec, it).ordinal }
            assertEquals(fits.sortedDescending(), fits, "${spec.id}: $fits")
        }
        assertEquals(ModelFit.Recommended, DeviceFit.rate(DeviceFit.GIB, DeviceMemory.phone(8)))
        assertEquals(ModelFit.TooBig, DeviceFit.rate(8 * DeviceFit.GIB, DeviceMemory.phone(8)))
        assertEquals(ModelFit.Fits, DeviceFit.rate(23 * DeviceFit.GIB, DeviceMemory.desktop(32)))
    }

    @Test
    fun legacyTierStillDescribesTheDevice() {
        assertEquals(DeviceTier.Standard, DeviceMemory.phone(8).tier)
        assertEquals(DeviceTier.High, DeviceMemory.phone(12).tier)
        assertEquals(DeviceTier.Max, DeviceMemory.phone(24).tier)
        assertEquals(DeviceTier.Standard, DeviceMemory.phone(6).tier)
        assertEquals(directors.size, ModelCatalog.fitsOn(DeviceMemory.phone(12)).size)
    }

    @Test
    fun profilesAskEachFamilyTheWayItWasTrained() {
        directors.forEach { assertNotNull(DirectorProfile.of(it)) }
        val qwen = DirectorProfile.of(ModelCatalog.qwen35_4b)
        assertTrue(qwen.suppressesThinking && qwen.assistantPrefix == "<think>\n\n</think>\n\n")
        assertTrue(DirectorProfile.of(ModelCatalog.qwen36_35bA3b).suppressesThinking)
        val gemma = DirectorProfile.of(ModelCatalog.gemma4E4b)
        assertTrue(!gemma.suppressesThinking && gemma.maxImageTokens in listOf(70, 140, 280, 560, 1120))
        assertEquals(ChatFormat.Gemma4, ModelCatalog.gemma4E4b.chatFormat)
        assertEquals(4096, DirectorProfile.of(ModelCatalog.qwen35_08bLooker).contextSize)
        assertEquals(DirectorProfile.Qwen35, DirectorProfile.forModelId("qwen3.5-4b-q4km"))
        assertEquals(null, DirectorProfile.forModelId("claude-opus-5-5"))

        // The same face box in both conventions lands on the same normalised rectangle.
        val face = listOf(0.35f, 0.40f, 0.68f, 0.71f)
        assertEquals(face, assertNotNull(qwen.grounding).toUnitXyxy(listOf(350f, 400f, 680f, 710f)))
        assertEquals(face, assertNotNull(gemma.grounding).toUnitXyxy(listOf(400f, 350f, 710f, 680f)))
        assertTrue(gemma.grounding!!.ask.contains("y1, x1"))
        assertEquals(null, qwen.grounding!!.toUnitXyxy(listOf(1f, 2f)))
    }

    /** The table for the report: footprint and badge per director on 8/12/16/24 GB phones and a 32 GB desktop. */
    @Test
    fun printsTheFitTable() {
        val devices = phones + desktop
        println("model".padEnd(28) + "GB".padStart(6) + devices.joinToString("") { (if (it.desktop) "desk${it.ramGb}" else "${it.ramGb}GB").padStart(14) })
        directors.forEach { spec ->
            val gb = DeviceFit.footprint(spec) / 10_000_000 / 100.0
            println(spec.id.padEnd(28) + gb.toString().padStart(6) + devices.joinToString("") { fit(spec, it).name.padStart(14) })
        }
    }
}
