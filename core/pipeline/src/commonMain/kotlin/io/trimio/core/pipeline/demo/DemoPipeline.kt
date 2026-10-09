package io.trimio.core.pipeline.demo

import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulated stages with realistic pacing and live content. Drives the build screen, UI tests and
 * design reviews until the real engines land, and doubles as a reference for engine authors.
 *
 * [speed] scales every delay: 1.0 is roughly a 40-second job.
 */
object DemoPipeline {

    val sampleSentence: List<String> =
        "سلام دوستان، امروز بیت‌کوین پنج درصد رشد کرد و سیگنال خرید ما به تارگت اول رسید. BTC الان بالای حمایت مهمه!"
            .split(" ")

    fun stages(speed: Float = 1f, seed: Long = 7): List<PipelineStage> {
        val random = Random(seed)
        fun ms(base: Long) = (base / speed).toLong().coerceAtLeast(1)

        return listOf(
            SimpleStage(StageId.Ingest) { ctx ->
                ctx.emit(LiveSignal.Note("ویدیو ۱۰۸۰×۱۹۲۰، ۳۰ فریم، ۰:۴۲", "1080×1920 video, 30 fps, 0:42"))
                stepped(10, ms(60), ctx)
            },
            SimpleStage(StageId.AudioCleanup) { ctx ->
                repeat(24) { i ->
                    val levels = List(48) { k -> (0.25f + 0.7f * abs(sin((i * 0.4 + k * 0.33) % (2 * PI))).toFloat() * random.nextFloat()).coerceIn(0f, 1f) }
                    ctx.emit(LiveSignal.Waveform(levels))
                    ctx.progress((i + 1) / 24f)
                    delay(ms(80))
                }
            },
            SimpleStage(StageId.Transcription) { ctx ->
                var cursor = 400L
                val words = sampleSentence.mapIndexed { index, text ->
                    val duration = 180L + text.length * 45L
                    val word = Word(
                        text = text,
                        range = TimeRange(cursor, cursor + duration),
                        confidence = 0.82f + random.nextFloat() * 0.18f,
                        language = ScriptDetector.detect(text),
                    )
                    cursor += duration + 60 + random.nextLong(0, 140)
                    ctx.emit(LiveSignal.WordRecognized(index, word))
                    ctx.progress((index + 1f) / sampleSentence.size)
                    delay(ms(420))
                    word
                }
                ctx.artifacts[StandardArtifacts.Transcript] = Transcript(Language.Persian, words)
            },
            SimpleStage(StageId.Alignment) { ctx -> stepped(30, ms(80), ctx) },
            SimpleStage(StageId.Analysis) { ctx ->
                val transcript = ctx.artifacts.require(StandardArtifacts.Transcript)
                val emphasised = transcript.words.withIndex().filter { (_, w) -> w.text.any { ch -> ch.isDigit() } || w.text in KEYWORDS }
                emphasised.forEachIndexed { i, (index, _) ->
                    ctx.emit(LiveSignal.EmphasisFound(index, 0.7f + random.nextFloat() * 0.3f))
                    ctx.progress((i + 1f) / emphasised.size)
                    delay(ms(250))
                }
            },
            SimpleStage(StageId.Direction) { ctx ->
                PLAN.forEachIndexed { i, (fa, en) ->
                    ctx.emit(LiveSignal.PlanStep(fa, en))
                    ctx.progress((i + 1f) / PLAN.size)
                    delay(ms(700))
                }
            },
            SimpleStage(StageId.AssetMatching) { ctx ->
                ASSETS.forEachIndexed { i, (id, label) ->
                    ctx.emit(LiveSignal.AssetChosen(id, label))
                    ctx.progress((i + 1f) / ASSETS.size)
                    delay(ms(350))
                }
            },
            SimpleStage(StageId.Render) { ctx ->
                val total = 42 * 30
                var frame = 0
                while (frame < total) {
                    frame = (frame + 15).coerceAtMost(total)
                    if (frame % 60 == 0 || frame == total) ctx.emit(LiveSignal.FrameRendered(frame, total))
                    ctx.progress(frame.toFloat() / total)
                    delay(ms(110))
                }
            },
            SimpleStage(StageId.Export) { ctx ->
                stepped(20, ms(90), ctx)
                ctx.artifacts[StandardArtifacts.Output] = "demo://output.mp4"
            },
        )
    }

    private suspend fun stepped(steps: Int, stepMs: Long, ctx: StageContext) {
        repeat(steps) {
            delay(stepMs)
            ctx.progress((it + 1f) / steps)
        }
    }

    private val KEYWORDS = setOf("بیت‌کوین", "سیگنال", "تارگت", "BTC", "رشد", "پنج")

    private val PLAN = listOf(
        "قلاب: زوم ضربه‌ای روی «بیت‌کوین» در ثانیهٔ اول" to "Hook: punch-zoom on «Bitcoin» in the first second",
        "شمارندهٔ عدد برای «پنج درصد» با فلش سبز" to "Number counter for «5%» with a green arrow",
        "کارت شیشه‌ای برای «سیگنال خرید»" to "Glass card for «buy signal»",
        "نوار تارگت با پر شدن مایع" to "Target bar with liquid fill",
        "دعوت به اقدام: لوگو + صدای کلیک" to "CTA: logo + click sound",
    )

    private val ASSETS = listOf(
        "icon/bitcoin" to "Bitcoin",
        "lottie/arrow-up-green" to "Arrow up",
        "counter/percent" to "Counter",
        "sfx/whoosh-soft" to "Whoosh",
        "sfx/cash-register" to "Cash register",
        "music/lofi-bed-02" to "Lo-fi bed",
    )
}

private class SimpleStage(override val id: StageId, private val body: suspend (StageContext) -> Unit) : PipelineStage {
    override suspend fun run(context: StageContext) = body(context)
}
