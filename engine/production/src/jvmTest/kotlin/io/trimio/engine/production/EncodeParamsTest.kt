package io.trimio.engine.production

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's encoder settings: tagged BT.709 and audio at desktop parity. */
class EncodeParamsTest {

    @Test
    fun colourTagsMarkTheMasterBt709Limited() {
        val tags = EncodeParams.colourTags()
        assertEquals(3, tags.size, "exactly the colour keys, got $tags")
        assertEquals(1, tags["color-standard"], "MediaFormat.KEY_COLOR_STANDARD must be BT.709 (COLOR_STANDARD_BT709)")
        assertEquals(2, tags["color-range"], "MediaFormat.KEY_COLOR_RANGE must be limited (COLOR_RANGE_LIMITED)")
        assertEquals(3, tags["color-transfer"], "MediaFormat.KEY_COLOR_TRANSFER must be the BT.709 curve (COLOR_TRANSFER_SMPTE_170M)")
    }

    @Test
    fun audioKeepsParityWithTheDesktopMaster() {
        assertTrue(EncodeParams.AUDIO_BITRATE >= 256_000, "phone AAC must be ≥256 kbps, got ${EncodeParams.AUDIO_BITRATE}")
        assertEquals(48_000, EncodeParams.AUDIO_RATE, "both paths must master at 48 kHz")
    }

    @Test
    fun resampleIsIdentityAtOneRate() {
        val src = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertEquals(src.toList(), EncodeParams.resample(src, 48_000, 48_000).toList(), "same rate must not touch the samples")
    }

    @Test
    fun resampleKeepsLengthAndLevel() {
        val src = FloatArray(16_000) { 0.5f }
        val out = EncodeParams.resample(src, 16_000, 48_000)
        assertEquals(48_000, out.size, "16k→48k must be three times as long")
        assertTrue(out.all { abs(it - 0.5f) < 1e-3f }, "a constant signal must stay constant")
    }

    @Test
    fun resampleFollowsARamp() {
        val src = FloatArray(100) { it / 100f }
        val out = EncodeParams.resample(src, 10_000, 20_000)
        assertEquals(200, out.size, "10k→20k must be twice as long")
        assertTrue(abs(out[100] - 0.5f) < 0.01f, "mid-ramp must stay mid-ramp, got ${out[100]}")
    }
}
