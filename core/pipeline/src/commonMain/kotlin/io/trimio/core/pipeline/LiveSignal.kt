package io.trimio.core.pipeline

import io.trimio.core.model.transcript.Word

/** Content streamed to the build screen while a stage works, so the user watches the edit being made. */
sealed interface LiveSignal {
    /** Normalised loudness bars, 0..1, for the waveform strip. */
    data class Waveform(val levels: List<Float>) : LiveSignal

    data class WordRecognized(val index: Int, val word: Word) : LiveSignal

    data class EmphasisFound(val wordIndex: Int, val strength: Float) : LiveSignal

    /** One human-readable decision of the Director, e.g. "Hook: zoom-punch on «سود»". */
    data class PlanStep(val textFa: String, val textEn: String) : LiveSignal

    data class AssetChosen(val assetId: String, val label: String) : LiveSignal

    /** [previewRef] is a platform image handle/path; null while previews are disabled. */
    data class FrameRendered(val frameIndex: Int, val totalFrames: Int, val previewRef: String? = null) : LiveSignal

    data class Note(val textFa: String, val textEn: String) : LiveSignal
}
