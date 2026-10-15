package io.trimio.engine.autopilot

import io.trimio.engine.llm.RgbImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A reference read from its frames alone: its tone, its accent, the style that answers it and how often it cuts. */
class ReferenceStyleTest {

    /** A [w]×[h] frame of [ground] with a band of [patch] over [share] of its height. */
    private fun frame(ground: Int, patch: Int, share: Float, w: Int = 40, h: Int = 60): RgbImage {
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = if (y < h * share) patch else ground
            val o = (y * w + x) * 3
            rgb[o] = (c shr 16).toByte()
            rgb[o + 1] = (c shr 8).toByte()
            rgb[o + 2] = c.toByte()
        }
        return RgbImage(w, h, rgb)
    }

    @Test
    fun aBlackAndGoldReelIsDarkWithAGoldAccent() {
        val read = assertNotNull(ReferenceStyle.read(List(4) { frame(0x14110F, 0xD4AF6A, 0.2f) }))
        assertTrue(read.dark)
        val accent = assertNotNull(read.accent)
        assertTrue((accent shr 16 and 0xFF) > 180 && (accent and 0xFF) < 140, "gold: ${read.accentHex}")
        val style = assertNotNull(ReferenceStyle.style(read))
        assertTrue("noir" in style.tones, "a dark reference takes a dark style: ${style.id}")
    }

    @Test
    fun aPaperCollageIsLightAndAGreyOneHasNoAccent() {
        val red = assertNotNull(ReferenceStyle.read(List(3) { frame(0xF2EDE4, 0xC8102E, 0.3f) }))
        assertFalse(red.dark)
        assertNotNull(red.accent)
        val grey = assertNotNull(ReferenceStyle.read(List(3) { frame(0xDDDDDD, 0x555555, 0.3f) }))
        assertNull(grey.accent, "no colour to match: ${grey.accentHex}")
        assertNull(ReferenceStyle.style(grey))
    }

    @Test
    fun cutsSetThePace() {
        val a = frame(0x101010, 0xFFFFFF, 0.5f)
        val b = frame(0xFFFFFF, 0x101010, 0.5f)
        // Ten samples over four seconds, the picture changing completely at every one: a fast cut.
        val fast = assertNotNull(ReferenceStyle.read(List(10) { if (it % 2 == 0) a else b }, duration = 4f))
        assertEquals("fast", fast.pace)
        val still = assertNotNull(ReferenceStyle.read(List(10) { a }, duration = 12f))
        assertEquals("calm", still.pace)
    }
}
