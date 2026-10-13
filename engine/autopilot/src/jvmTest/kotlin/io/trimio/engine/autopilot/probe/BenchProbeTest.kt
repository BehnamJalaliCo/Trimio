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
        val log = StringBuilder()
        fun println(x: String) { log.appendLine(x) }
        val a = SpeechAnalysis.of(voice, t)
        val cp = CutPoints(io.trimio.engine.autopilot.VoiceLevel.levelled(voice))
        // The old cut list: VAD on the raw voice, tightened, nothing else.
        val dur = voice.samples.size / 48_000f
        val oldPauses = SpeechAnalysis.silences(io.trimio.engine.audio.VoiceActivityDetector(hangoverMs = 70, mergeGapMs = 120, belowSpeechDb = 24f).detect(voice), dur)
        val old = io.trimio.engine.motion.score.EditPlan.tighten(io.trimio.engine.motion.score.EditPlan.alignToSpeech(t.words, oldPauses), oldPauses, dur)
        fun near(x: Float) = t.words.filter { it.range.startMs / 1000f - 0.4f < x && it.range.endMs / 1000f + 0.4f > x }
            .joinToString(" ") { "${it.text}[${it.range.startMs / 1000f}-${it.range.endMs / 1000f}]" }
        fun report(name: String, e: io.trimio.engine.motion.score.EditPlan) {
            var bad = 0
            e.segments.forEachIndexed { i, s ->
                val inBad = i > 0 && io.trimio.engine.autopilot.JumpCuts.inWord(cp, s.sourceStart, true)
                val outBad = i < e.segments.lastIndex && io.trimio.engine.autopilot.JumpCuts.inWord(cp, s.sourceEnd, false)
                if (inBad) bad++
                if (outBad) bad++
                println("$name seg $i src ${s.sourceStart}-${s.sourceEnd} out ${s.outStart} len ${s.length} ${if (inBad) "IN!" else ""} ${if (outBad) "OUT!" else ""} | ${near(s.sourceStart)} || ${near(s.sourceEnd)}")
            }
            val starts = e.segments.map { it.outStart }.filter { it > 0f }
            val dense = starts.count { t0 -> starts.count { it >= t0 && it < t0 + 10f } > 7 }
            println("$name: ${e.segments.size} segments, ${e.duration}s, cuts inside words $bad, flashes ${e.segments.count { it.length < 0.3f }}, dense windows $dense")
        }
        report("OLD", old)
        report("NEW", a.edit)
        println("pauses: " + a.pauses.joinToString { "%.2f-%.2f".format(it.start, it.endInclusive) })
        println("speechDb levelled ${cp.speechDb}")
        for (p in a.pauses) {
            var lo = 0f
            var x = p.start
            val vals = mutableListOf<Float>()
            while (x < p.endInclusive) { vals += cp.db(x, x + 0.02f) - cp.speechDb; x += 0.01f }
            println("pause %.2f-%.2f min %.1f max %.1f median %.1f".format(p.start, p.endInclusive, vals.min(), vals.max(), vals.sorted()[vals.size / 2]))
        }
        File(out.parentFile, "probe-cuts.txt").writeText(log.toString())
    }
}
