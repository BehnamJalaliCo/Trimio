package io.trimio.engine.assets

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Procedural audio is listened to in review: every sound is also written to build/asset-previews. */
class AssetsTest {

    private val out = File("build/asset-previews").apply { mkdirs() }
    private val library = AssetLibrary()

    private fun save(name: String, pcm: PcmAudio) = File(out, "$name.wav").writeBytes(WavCodec.encodePcm16(pcm))

    private fun peak(pcm: PcmAudio) = pcm.samples.maxOf { abs(it) }

    @Test
    fun everyEffectIsShortCleanAndAudible(): Unit = runBlocking {
        for (effect in library.effects) {
            val pcm = assertNotNull(library.load("sfx/${effect.id}"), effect.id)
            save("sfx-${effect.id}", pcm)
            assertTrue(pcm.durationMs in 20..1_500, "${effect.id}: ${pcm.durationMs} ms")
            assertEquals(0.89f, peak(pcm), 0.01f, "${effect.id} peaks at -1 dBFS")
            assertTrue(pcm.samples.none { it.isNaN() }, effect.id)
            assertTrue(abs(pcm.samples.last()) < 0.01f, "${effect.id} ends without a click")
        }
        assertNull(library.load("sfx/does-not-exist"))
        assertNull(library.load("lottie/rocket"))
    }

    @Test
    fun effectsAreDeterministic() {
        val a = ProceduralSfx().generate("whoosh", seed = 5)!!.samples
        val b = ProceduralSfx().generate("whoosh", seed = 5)!!.samples
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun musicBedsLoopSeamlesslyOnTheGrid(): Unit = runBlocking {
        for (mood in MusicMood.entries) {
            val start = System.nanoTime()
            val track = library.track(mood)
            val ms = (System.nanoTime() - start) / 1_000_000
            save("music-${mood.id}", track.audio)
            val expectedMs = 8 * 4 * 60_000.0 / mood.bpm
            assertEquals(expectedMs, track.loopMs.toDouble(), 2.0, mood.id)
            val lufs = LoudnessMeter.measure(track.audio).integratedLufs
            assertTrue(lufs in -30.0..-8.0, "${mood.id}: $lufs LUFS")
            // Seam: the jump from the last sample to the first is no bigger than normal motion.
            val s = track.audio.samples
            val seam = abs(s.first() - s.last())
            val typical = (1 until s.size step 97).map { abs(s[it] - s[it - 1]) }.sorted()[s.size / 97 * 99 / 100]
            assertTrue(seam <= typical * 1.5f + 0.01f, "${mood.id} seam $seam vs $typical")
            assertEquals(0L, track.beats(10_000).first())
            assertEquals(track.beatMs * 4, (track.downbeats(10_000)[1]).toDouble(), 1.0)
            println("${mood.id}: ${mood.bpm} BPM, ${track.loopMs} ms loop, $lufs LUFS, rendered in $ms ms")
            assertTrue(ms < 5_000, "${mood.id} took $ms ms")
        }
    }

    @Test
    fun semanticIndexUnderstandsInflections() {
        val index = SemanticIndex(listOf("rocket" to listOf("موشک", "rocket", "launch"), "fire" to listOf("آتش", "fire", "hot")))
        assertEquals("rocket", index.best("rockets")?.first)
        assertEquals("rocket", index.best("موشکی")?.first)
        assertEquals("fire", index.best("آتیش\u200Cپاره آتش")?.first)
        assertNull(index.best("zzzz qqq"))
    }

    @Test
    fun elementsSnapToNearbyBeatsWithTheirSounds() {
        val stage = AssetMatchingStage(library)
        val timeline = Timeline(
            styleId = "liquid-glass", seed = 1, canvas = CanvasSpec(), durationMs = 6_000,
            clips = listOf(
                ElementClip(TimeRange(1_050, 2_550), assetId = "icon/star", preset = "pop"),
                SfxClip(TimeRange(1_050, 1_650), assetId = "sfx/whoosh"),
                ElementClip(TimeRange(3_300, 4_800), assetId = "icon/fire", preset = "pop"), // no beat within 120 ms
                MusicClip(TimeRange(0, 6_000), assetId = "music/uplifting"),
            ),
        )
        val beats = (0..12).map { it * 500L } // 120 BPM grid
        val synced = stage.beatSync(timeline, beats)
        val elements = synced.clipsOf<ElementClip>()
        assertEquals(TimeRange(1_000, 2_500), elements[0].range)
        assertEquals(1_000, synced.clipsOf<SfxClip>().single().range.startMs)
        assertEquals(TimeRange(3_300, 4_800), elements[1].range)
    }
}
