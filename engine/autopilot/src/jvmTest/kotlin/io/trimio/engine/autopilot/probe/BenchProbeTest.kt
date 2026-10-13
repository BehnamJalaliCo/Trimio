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

    /** The old outputs (scratchpad/jurybug/out) counted with the new yardsticks: the "before" column. */
    @Test
    fun before() {
        if (System.getProperty("auto.mode") != "probe") return
        val out = File(System.getProperty("auto.out"))
        val lexicon = kotlinx.coroutines.runBlocking { io.trimio.engine.autopilot.PersianSpelling.load() }
        val saved = Json.decodeFromString(ListSerializer(W.serializer()), File(out, "transcript.json").readText())
        val log = StringBuilder()
        for (seed in 1..6) {
            val report = File(out, "seed-$seed-report.txt").takeIf { it.isFile }?.readLines() ?: continue
            // The old captions: the heard words with the old lexicon fixes, in order.
            val fixes = report.filter { it.startsWith("spelling (") }.map { it.substringAfter("): ").split(" → ") }.toMutableList()
            val words = mutableListOf<String>()
            for (w in saved.map { it.w }) {
                val f = fixes.firstOrNull()
                if (f != null && f[0] == w) {
                    fixes.removeAt(0)
                    if (f[1] != "(joined)") words += f[1].split(' ')
                } else words += w
            }
            val u = Json { ignoreUnknownKeys = true }.decodeFromString(io.trimio.engine.autopilot.Understanding.serializer(), File(out, "seed-$seed-understanding.json").readText())
            val names = u.entities.map { it.name } + io.trimio.engine.autopilot.ScreenWords.namesIn(words) { io.trimio.engine.autopilot.ScreenWords(lexicon).shows(it) }
            val screen = io.trimio.engine.autopilot.ScreenWords(lexicon, names)
            val listing = report.dropWhile { !it.startsWith("jury: the first cut") && !it.startsWith("jury: variation") }.drop(1).takeWhile { it.startsWith("  ") && !it.startsWith("  rhythm") }
            val graphics = listing.map { l -> l.trim().split(Regex("\\s+"), limit = 4).let { p -> p[0] to (p.getOrNull(3) ?: "") } }
            val score = io.trimio.engine.motion.score.Score.parse(File(out, "seed-$seed-score.json").readText())
            val labels = score.scenes.flatMap { sc -> sc.beats.flatMap { listOfNotNull(it.label) + it.items } }
            val unknownGraphics = (graphics.map { it.second } + labels).flatMap { screen.unknown(it) }
            val unknownCaptions = words.flatMap { screen.unknown(it) }
            val fragments = graphics.filter { it.first in io.trimio.engine.autopilot.Fragments.TITLES }.mapNotNull { (r, t) -> io.trimio.engine.autopilot.Fragments.fault(t)?.let { "$r «$t» ($it)" } }
            val jury = report.firstOrNull { it.startsWith("jury: not unanimous") || it.startsWith("jury: every judge") }.orEmpty()
            log.appendLine("seed $seed: $jury")
            log.appendLine("  unknown graphics ${unknownGraphics.size}: ${unknownGraphics.joinToString(" ")}")
            log.appendLine("  unknown captions ${unknownCaptions.size}: ${unknownCaptions.joinToString(" ")}")
            log.appendLine("  fragments ${fragments.size}: ${fragments.joinToString(" | ")}")
            log.appendLine("  graphics: ${graphics.joinToString(" | ") { "${it.first}: ${it.second}" }}")
        }
        File(out.parentFile, "probe-before.txt").writeText(log.toString())
    }

    @Test
    fun goldenWords() {
        if (System.getProperty("auto.mode") != "golden-words") return
        val out = File(System.getProperty("auto.out"))
        val log = StringBuilder()
        val ids = listOf("fa-testimonial-ielts", "fa-tutorial-english", "fa-fitness-abs", "fa-news-inflation", "fa-launch-perfume-assets", "fa-flash-sale-15s", "en-tutorial-excel")
        for (s in io.trimio.engine.autopilot.workshop.Scenarios.all.filter { it.id in ids }) {
            val p = io.trimio.engine.autopilot.workshop.Workshop.run(s, 1L)
            log.appendLine("== ${s.id}: " + io.trimio.engine.autopilot.golden.GoldenArchive.accuracy(s, p).let { "${it.first} wrong ${it.second}" })
            p.report.filter { it.startsWith("screen") || it.startsWith("spelling") }.forEach { log.appendLine("  $it") }
        }
        File(out, "probe-golden-words.txt").writeText(log.toString())
    }
}
