package io.trimio.feature.stream

import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StageProgress
import io.trimio.core.pipeline.StageStatus

data class RecognizedWord(val index: Int, val text: String, val emphasis: Float)

/** A line of text in both UI languages. */
data class PlanLine(val fa: String, val en: String)

/** Everything the build screen shows. Built only by [BuildStreamReducer]. */
data class BuildStreamState(
    val status: JobStatus? = null,
    val overall: Float = 0f,
    val stages: List<StageProgress> = StageId.entries.map { StageProgress(it, StageStatus.Pending, 0f) },
    val current: StageId? = null,
    val etaMs: Long? = null,
    val error: String? = null,
    val waveform: List<Float> = emptyList(),
    val words: List<RecognizedWord> = emptyList(),
    val plan: List<PlanLine> = emptyList(),
    val assets: List<PlanLine> = emptyList(),
    val framesRendered: Int = 0,
    val framesTotal: Int = 0,
    val note: PlanLine? = null,
) {
    val isFinished: Boolean get() = status == JobStatus.Completed || status == JobStatus.Failed

    /** Loudness of the latest waveform frame, drives the aurora's energy. */
    val energy: Float get() = if (waveform.isEmpty()) 0f else waveform.average().toFloat()
}

object BuildStreamReducer {
    /** The screen shows a rolling window; older words scroll out. */
    const val MAX_WORDS = 60

    fun reduce(state: BuildStreamState, event: PipelineEvent): BuildStreamState = when (event) {
        is PipelineEvent.StateChanged -> event.state.let {
            state.copy(
                status = it.status,
                // Dropped intermediate updates can arrive out of order; the bar must never move backwards.
                overall = maxOf(state.overall, it.overall),
                stages = it.stages,
                current = it.current,
                etaMs = it.etaMs,
                error = it.error,
            )
        }
        is PipelineEvent.Live -> reduceSignal(state, event.signal)
    }

    private fun reduceSignal(state: BuildStreamState, signal: LiveSignal): BuildStreamState = when (signal) {
        is LiveSignal.Waveform -> state.copy(waveform = signal.levels)
        is LiveSignal.WordRecognized -> state.copy(
            words = (state.words + RecognizedWord(signal.index, signal.word.text, signal.word.emphasis)).takeLast(MAX_WORDS),
        )
        is LiveSignal.EmphasisFound -> state.copy(
            words = state.words.map { if (it.index == signal.wordIndex) it.copy(emphasis = signal.strength) else it },
        )
        is LiveSignal.PlanStep -> state.copy(plan = state.plan + PlanLine(signal.textFa, signal.textEn))
        is LiveSignal.AssetChosen -> state.copy(assets = state.assets + PlanLine(signal.labelFa, signal.labelEn))
        is LiveSignal.FrameRendered -> state.copy(framesRendered = signal.frameIndex, framesTotal = signal.totalFrames)
        is LiveSignal.Note -> state.copy(note = PlanLine(signal.textFa, signal.textEn))
    }
}
