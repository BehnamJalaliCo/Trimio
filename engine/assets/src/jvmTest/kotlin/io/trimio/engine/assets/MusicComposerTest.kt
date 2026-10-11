package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.media.WavCodec
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Composed cues, their grid, the beat tracker and the SFX palettes. Every cue is written to build/music-samples for listening. */
class MusicComposerTest {

    private val rate = 48_000
    private val out = File("build/music-samples").apply { mkdirs() }

    private fun save(name: String, pcm: PcmAudio) = File(out, "$name.wav").writeBytes(WavCodec.encodePcm16(pcm))

    @Test
    fun theDropAndTheEndingLandOnDownbeats() {
        val cases = listOf(
            listOf(0.0, 66.8, 4.2, 52.0), listOf(1.6, 30.0, 1.6, 24.0), listOf(0.0, 15.0, 3.1, null), listOf(0.0, 8.0, null, null),
            listOf(0.0, 4.0, null, null), listOf(2.2, 45.3, 9.7, 40.1),
        )
        for (mood in MusicMood.entries) for (c in cases) {
            val (start, end) = c[0]!! to c[1]!!
            val a = Arranger.fit(mood, start, end, drop = c[2], breakdown = c[3])
            val label = "${mood.id} $c → ${a.grid.bpm} bpm ${a.sections.map { "${it.kind}@${r2(it.from)}" }}"
            c[2]?.let { assertEquals(it, assertNotNull(a.drop, label), 1e-9, label) }
            assertTrue(a.grid.isDownbeat(a.ending, 1e-6), "ending off the downbeat: $label")
            assertTrue(a.ending <= end - 0.45 && a.ending >= start, label)
            assertTrue(abs(a.grid.bpm / mood.bpm - 1.0) <= Arranger.MAX_STRETCH + 1e-9, label)
            assertEquals(start, a.sections.first().from, 1e-9, label)
            assertEquals(end, a.sections.last().to, 1e-9, label)
            a.sections.zipWithNext().forEach { (x, y) -> assertEquals(x.to, y.from, 1e-9, label) }
            a.sections.drop(1).forEach { assertTrue(a.grid.isDownbeat(it.from, 1e-6), "section off the bar: $label") }
            c[3]?.let { cta -> a.breakdown?.let { assertTrue(it <= cta + a.grid.period && cta - it < a.grid.bar + a.grid.period, label) } }
        }
        // A long piece stretches the tempo so the ending is exactly a whole number of bars after the drop.
        val long = Arranger.fit(MusicMood.Uplifting, 0.0, 66.8, drop = 4.2, breakdown = 52.0)
        assertEquals(66.8 - Arranger.RING, long.ending, 1e-6)
        assertNotNull(long.breakdown)
    }

    @Test
    fun theBeatTrackerFindsTheGridOfComposedMusic() {
        for (mood in MusicMood.entries) {
            val a = Arranger.fit(mood, 0.0, 34.0, drop = 6.37, breakdown = 26.0)
            val pcm = MusicComposer(rate).compose(a, seed = 3)
            val drop = a.drop!!
            val found = assertNotNull(BeatTracker.analyse(pcm, drop, a.breakdown ?: a.ending), mood.id)
            val label = "${mood.id}: truth ${r2(a.grid.bpm)} bpm, found ${r2(found.grid.bpm)} (confidence ${r2(found.confidence)})"
            assertEquals(a.grid.bpm, found.grid.bpm, 0.2, label)
            val worst = found.beats.maxOf { b -> abs(a.grid.nearestBeat(b) - b) }
            assertTrue(worst < 0.008, "$label: a beat ${(worst * 1000).toInt()} ms off")
            assertTrue(a.grid.isDownbeat(found.grid.offset, 0.012), "$label: downbeat at ${r2(found.grid.offset)} is beat ${a.grid.beatInBar(found.grid.offset)} of the bar")
            println("$label, worst beat ${(worst * 1000).toInt()} ms")
        }
        // The loop beds too, at their nominal tempo with the downbeat at zero.
        val bed = ProceduralMusic(rate).render(MusicMood.Energetic, 2)
        val twice = PcmAudio(bed.audio.samples + bed.audio.samples, rate)
        val found = assertNotNull(BeatTracker.analyse(twice))
        assertEquals(126.0, found.grid.bpm, 0.2)
        assertTrue(bed.grid.isDownbeat(found.grid.offset, 0.012))
    }

    @Test
    fun aComposedCueHasItsSectionsAndEndsOnTheFinalHit() {
        for (mood in MusicMood.entries) {
            val a = Arranger.fit(mood, 1.2, 40.0, drop = 9.0, breakdown = 31.0)
            val pcm = MusicComposer(rate).compose(a, seed = 1)
            save("cue-${mood.id}", pcm)
            val x = pcm.samples
            assertEquals((40.0 * rate).toInt(), x.size)
            assertTrue(x.none { it.isNaN() } && x.maxOf { abs(it) } <= 0.71f, mood.id)
            assertTrue((0 until (1.2 * rate).toInt()).all { x[it] == 0f }, "${mood.id}: silent before the start")
            val drop = db(x, a.drop!!, a.drop!! + 4 * a.grid.bar.coerceAtMost(4.0))
            val down = db(x, a.breakdown!! + 0.3, a.ending - 0.1)
            val intro = db(x, 1.4, a.sections.first { it.kind == SectionKind.Build }.from)
            assertTrue(drop > down + 3.0, "${mood.id}: drop $drop dB vs breakdown $down dB")
            assertTrue(drop > intro + 2.0, "${mood.id}: drop $drop dB vs intro $intro dB")
            // The final hit: a burst on the ending's downbeat, ringing down into the last frame.
            assertTrue(db(x, a.ending, a.ending + 0.25) > db(x, a.ending - 0.3, a.ending - 0.05) + 1.0, "${mood.id}: no final hit")
            assertTrue(abs(x.last()) < 1e-3f, mood.id)
        }
        val cue = Arranger.fit(MusicMood.Energetic, 0.0, 66.8, drop = 4.2, breakdown = 52.0)
        save("cue-energetic-66s", MusicComposer(rate).compose(cue, seed = 7))
    }

    @Test
    fun everyPaletteVoicesEveryCueInLayers() {
        val sfx = ProceduralSfx(rate)
        for (p in SfxPalette.entries) for (cue in SfxPalette.CUES) {
            val pcm = assertNotNull(sfx.generate("$cue@${p.id}", seed = 2), "$cue@${p.id}")
            if (cue in setOf("impact", "whoosh", "notify", "swell")) save("sfx-$cue-${p.id}", pcm)
            assertTrue(pcm.durationMs in 30..2_000, "$cue@${p.id}: ${pcm.durationMs} ms")
            assertEquals(0.89f, pcm.samples.maxOf { abs(it) }, 0.01f, "$cue@${p.id}")
            assertTrue(abs(pcm.samples.last()) < 0.01f, "$cue@${p.id} ends without a click")
        }
        // Transient + body + tail: the hit's attack is immediate, its tail still audible 300 ms on.
        val hit = sfx.generate("impact@cinematic")!!.samples
        assertTrue(db(hit, 0.0, 0.01) > -20.0 && db(hit, 0.3, 0.4) > -45.0)
        assertEquals(null, sfx.generate("impact@nope"))
    }

    @Test
    fun everyStyleMoodTagHasAPreset() {
        for (tag in SoundPresets.TAGS) assertEquals(tag, SoundPresets.forTag(tag)?.tag)
        assertEquals("luxury", SoundPresets.forTag("Premium gold")?.tag)
        assertEquals(SfxPalette.Digital, SoundPresets.forTag("neon-cyber")?.palette)
        assertEquals(null, SoundPresets.forTag("zzz"))
    }

    private fun db(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * rate).toInt().coerceIn(0, x.size)
        val b = (to * rate).toInt().coerceIn(a + 1, x.size)
        var sum = 0.0
        for (i in a until b) sum += x[i].toDouble() * x[i]
        return 10 * log10(sum / (b - a) + 1e-12)
    }

    private fun r2(v: Double) = kotlin.math.round(v * 100) / 100
}
