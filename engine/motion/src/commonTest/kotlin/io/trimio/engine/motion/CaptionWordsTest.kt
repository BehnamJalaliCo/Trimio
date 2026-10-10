package io.trimio.engine.motion

import io.trimio.engine.motion.score.Words
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionWordsTest {
    @Test
    fun hesitationsAreNeverCaptioned() {
        for (w in listOf("اِم", "اِمم", "امم", "اوم", "ااا", "آه", "آآ", "um,", "Um", "uh", "uhm", "umm.", "hmm")) assertTrue(Words.isFiller(w), w)
        // Words that look alike are words: «ام» (am), «اوه» (oh, said on purpose), «آهن», "hum", "us".
        for (w in listOf("ام", "اوه", "آهن", "امروز", "hum", "us", "uber", "her", "اما")) assertFalse(Words.isFiller(w), w)
    }
}
