package io.trimio.engine.autopilot.probe

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Media
import io.trimio.engine.autopilot.SpeechAnalysis
import io.trimio.engine.motion.score.CutPoints
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/** TEMPORARY probe (delete before hand-off). */
class BenchProbeTest {
    @kotlinx.serialization.Serializable
    data class W(val w: String, val s: Float, val e: Float)

    @Test
    fun cuts() {
        if (System.getProperty("auto.mode") != "probe") return
        val video = File(System.getProperty("auto.video"))
        val out = File(System.getProperty("auto.out"))
        val saved = Json.decodeFromString(ListSerializer(W.serializer()), File(out, "transcript.json").readText())
        val t = Transcript(Language.Persian, saved.map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) })
        val voice = Media.audio(video, 48_000)
        val a = SpeechAnalysis.of(voice, t)
        val cp = CutPoints(voice)
        println("speechDb ${cp.speechDb}")
        val words = a.transcript.words
        fun near(x: Float) = words.filter { it.range.startMs / 1000f - 0.4f < x && it.range.endMs / 1000f + 0.4f > x }
            .joinToString(" ") { "${it.text}[${it.range.startMs / 1000f}-${it.range.endMs / 1000f}]" }
        a.edit.segments.forEachIndexed { i, s ->
            val inDb = cp.db(s.sourceStart, s.sourceStart + CutPoints.FADE)
            val outDb = cp.db(s.sourceEnd - CutPoints.FADE, s.sourceEnd)
            val bad = (if (i > 0 && inDb > cp.speechDb - 16) "IN! " else "") + (if (i < a.edit.segments.lastIndex && outDb > cp.speechDb - 16) "OUT! " else "")
            println("seg $i src ${s.sourceStart}-${s.sourceEnd} out ${s.outStart} len ${s.length} in ${inDb.toInt()} out ${outDb.toInt()} $bad| ${near(s.sourceStart)} || ${near(s.sourceEnd)}")
        }
        println("pauses: " + a.pauses.joinToString { "%.2f-%.2f".format(it.start, it.endInclusive) })
    }
}
