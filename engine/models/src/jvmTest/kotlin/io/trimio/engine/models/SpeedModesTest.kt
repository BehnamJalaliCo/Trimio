package io.trimio.engine.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedModesTest {
    private val directors = ModelCatalog.all.filter { it.kind == ModelKind.Language }
    private val phone12 = DeviceMemory.phone(12)

    private fun plan(device: DeviceMemory, vararg installed: ModelSpec, gpu: GraphicsMemory? = null, saved: String? = null, offered: List<ModelSpec> = directors) =
        SpeedModes.plan(offered, { it in installed }, device, gpu, saved)

    @Test
    fun theThreeSpeedsAreTheMoeThe4bAndThe9b() {
        val p = plan(phone12, ModelCatalog.lfm25_8bA1b, ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9bLight)
        assertEquals(ModelCatalog.lfm25_8bA1b, p.of(SpeedMode.Fast).model)
        assertEquals(ModelCatalog.qwen35_4b, p.of(SpeedMode.Balanced).model)
        assertEquals(ModelCatalog.qwen35_9bLight, p.of(SpeedMode.Pro).model)
        assertEquals(SpeedMode.Balanced, p.default, "a 12 GB phone starts on the comfortable 4B")
        assertFalse(p.tight(SpeedMode.Balanced))
        assertTrue(p.tight(SpeedMode.Pro), "the 9B only fits a 12 GB phone: the screen warns")
    }

    @Test
    fun aTwelveGbPhoneGetsTheLight9bAndASixteenGbPhoneTheFullOne() {
        val both = arrayOf(ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9b, ModelCatalog.qwen35_9bLight)
        assertEquals(ModelCatalog.qwen35_9bLight, plan(phone12, *both).of(SpeedMode.Pro).model, "the safer of two that only fit")
        assertEquals(ModelCatalog.qwen35_9b, plan(DeviceMemory.phone(16), *both).of(SpeedMode.Pro).model)
        assertEquals(ModelFit.Recommended, plan(DeviceMemory.phone(16), *both).of(SpeedMode.Pro).fit)
    }

    @Test
    fun aSaved9bDoesNotBecomeAPhonesDefaultUnlessItHasRoom() {
        val p = plan(phone12, ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9b, saved = ModelCatalog.qwen35_9b.id)
        assertEquals(SpeedMode.Balanced, p.default)
        val big = plan(DeviceMemory.phone(16), ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9b, saved = ModelCatalog.qwen35_9b.id)
        assertEquals(SpeedMode.Pro, big.default, "the creator's pick when the phone holds it comfortably")
    }

    @Test
    fun missingSpeedsSuggestADownloadAndNothingInstalledStillDefaultsToBalanced() {
        val p = plan(phone12, ModelCatalog.qwen35_4b)
        assertNull(p.of(SpeedMode.Fast).model)
        assertEquals(ModelCatalog.lfm25_8bA1b, p.of(SpeedMode.Fast).suggested)
        assertEquals(ModelCatalog.qwen35_9bLight, p.of(SpeedMode.Pro).suggested)
        assertEquals(SpeedMode.Balanced, plan(phone12).default)
        assertEquals(ModelCatalog.qwen35_4b, plan(phone12).of(SpeedMode.Balanced).suggested)
        // Nothing for the 9B on an 8 GB phone.
        assertNull(plan(DeviceMemory.phone(8), ModelCatalog.qwen35_4b).of(SpeedMode.Pro).suggested)
    }

    @Test
    fun aDesktopCardThatHoldsThe9bStartsOnProAndA4GbCardStaysOnBalanced() {
        val desktop = DeviceMemory.desktop(16)
        val offered = listOf(ModelCatalog.qwen35_9b, ModelCatalog.qwen35_4b)
        val laptop = GraphicsMemory(listOf("NVIDIA GeForce RTX 3050 Ti Laptop GPU"), 4L shl 30)
        val p = plan(desktop, ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9b, gpu = laptop, offered = offered)
        assertEquals(SpeedMode.Balanced, p.default)
        val four = p.of(SpeedMode.Balanced).gpuShare!!
        val nine = p.of(SpeedMode.Pro).gpuShare!!
        assertTrue(four > nine && nine < SpeedModes.WHOLE_ON_GPU, "4B $four, 9B $nine on a 4 GB card")
        assertNull(p.of(SpeedMode.Fast).model, "the desktop is not offered the phone MoE")
        assertNull(p.of(SpeedMode.Fast).suggested)
        val big = plan(desktop, ModelCatalog.qwen35_4b, ModelCatalog.qwen35_9b, gpu = GraphicsMemory(listOf("RTX 4080"), 16L shl 30), offered = offered)
        assertEquals(SpeedMode.Pro, big.default)
        assertEquals(1f, big.of(SpeedMode.Pro).gpuShare)
        assertFalse(big.tight(SpeedMode.Pro), "a desktop is never warned like a phone")
    }
}
