package io.trimio.engine.autopilot.workshop

import io.trimio.engine.autopilot.FacePixels
import io.trimio.engine.autopilot.FaceScene
import io.trimio.engine.autopilot.HeadReader
import io.trimio.engine.autopilot.SkinModel
import kotlin.test.Test

class TmpSubjectDebugTest {
    @Test
    fun dump() {
        for (name in listOf("beard")) {
            val f = SyntheticFootage.hard(name)
            val frames = (1..5).map { FacePixels.of(f.rgba(90, 160, 30f * it / 6f), 90, 160, 4) }
            val scene = FaceScene(frames)
            val model = SkinModel.seed(scene, true)
            println("$name model=$model luma=${model?.luma} motionMean=${scene.motionMean}")
            if (model == null) continue
            val r = HeadReader(scene, 0, model)
            val h = r.best()
            println("best ${h?.box?.toList()} ${h?.score}")
            val sb = StringBuilder()
            for (y in 0 until 160) {
                for (x in 0 until 90) {
                    val i = y * 90 + x
                    sb.append(
                        when {
                            model.skin(frames[0], scene.background[0][i], i) -> '#'
                            scene.background[0][i] <= SkinModel.BACKGROUND -> '.'
                            frames[0].luma[i] < model.luma * 0.72f -> 'h'
                            else -> 'o'
                        },
                    )
                }
                sb.append('\n')
            }
            println(sb)
        }
    }
}
