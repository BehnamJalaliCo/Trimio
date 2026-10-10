package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenSpellingTest {
    private val lexicon = runBlocking { PersianSpelling.load() }
    private val spoken = "سیصد و پنجاه تتر در قالب ووچر که می‌تونید دریافت کنید".split(' ')

    @Test
    fun aModelMisspellingGoesBackToTheSpokenWordOrOut() {
        val score = Score(scenes = listOf(SceneScore(beats = listOf(
            BeatScore(recipe = "spread", text = "وچر"),
            BeatScore(recipe = "slam", text = "تتر Tether ۳۵۰"),
            BeatScore(recipe = "slam", text = "قژثصخ"),
        ))))
        val log = mutableListOf<String>()
        val beats = ScreenSpelling(lexicon, spoken, "کارت ووچر لوکس").check(score) { log += it }.scenes.single().beats
        assertEquals(listOf("ووچر", "تتر Tether ۳۵۰"), beats.map { it.text })
        assertEquals(3, log.size, log.joinToString())
    }
}
