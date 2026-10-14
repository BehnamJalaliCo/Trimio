package io.trimio.engine.production

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A video's own music is a beat grid the finder's beats sit on, even with beats missed and onsets added under a voice. */
class SourceMusicTest {
    @Test
    fun aBedUnderTheVoiceIsHeardAndSpeechAloneIsNot() {
        val r = Random(7)
        // 100 bpm for 64 s: a third of the beats missed under words, a few jittered, extra onsets from the voice.
        val grid = (0 until 107).map { it * 0.6 + 0.02 }
        val heard = grid.filter { r.nextFloat() > 0.33f }.map { it + (r.nextDouble() - 0.5) * 0.05 }
        val extra = (0 until 30).map { r.nextDouble() * 64 }
        val bed = assertNotNull(SourceMusic.tempo((heard + extra).sorted(), 64f), "the bed under the voice")
        assertTrue(bed.first in 99f..101f, "bpm ${bed.first}")
        // Speech alone: onsets at syllable-ish random intervals.
        var t = 0.0
        val speech = buildList { while (t < 64) { t += 0.15 + r.nextDouble() * 1.2; add(t) } }
        assertNull(SourceMusic.tempo(speech, 64f), "a talk without music")
        // Too few beats, or a short stretch of the video, is not the video's music.
        assertNull(SourceMusic.tempo(grid.take(20), 64f))
        assertNull(SourceMusic.tempo(grid.take(40), 64f))
    }
}
