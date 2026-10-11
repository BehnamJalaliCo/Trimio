package io.trimio.feature.editor

import io.trimio.engine.production.CaptionDensity
import io.trimio.engine.production.ElementKind

/** Everything the creator can do in the editor; each one becomes a typed change to the edit. */
data class EditorActions(
    val onBack: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onPlay: () -> Unit = {},
    /** Seconds on the edit's clock. */
    val onSeek: (Float) -> Unit = {},
    val onTool: (EditorTool) -> Unit = {},
    /** A word tapped: picks it, or (with [extend]) stretches the pick to it. */
    val onWord: (index: Int, extend: Boolean) -> Unit = { _, _ -> },
    val onEmphasise: () -> Unit = {},
    val onEditText: (String) -> Unit = {},
    val onHit: () -> Unit = {},
    val onDelete: () -> Unit = {},
    /** The creator's words to the director: a new edit through the director. */
    val onRewrite: (String) -> Unit = {},
    val onStyle: (String) -> Unit = {},
    /** 0..1; [done] when the finger lifts (one undo step per drag). */
    val onEnergy: (value: Float, done: Boolean) -> Unit = { _, _ -> },
    val onDensity: (CaptionDensity) -> Unit = {},
    /** Hold to see the director's edit. */
    val onCompare: (Boolean) -> Unit = {},
    /** A mood id, or null for none. */
    val onMusic: (String?) -> Unit = {},
    val onMusicDb: (db: Float, done: Boolean) -> Unit = { _, _ -> },
    val onEffects: (Boolean) -> Unit = {},
    val onAutoMix: () -> Unit = {},
    val onElement: (key: String, on: Boolean) -> Unit = { _, _ -> },
    val onAdding: (Boolean) -> Unit = {},
    val onAdd: (ElementKind) -> Unit = {},
    val onMore: () -> Unit = {},
    val onSilences: (Boolean) -> Unit = {},
    val onFillers: (Boolean) -> Unit = {},
    val onBeatCuts: (Boolean) -> Unit = {},
    /** The stage's width in pixels: frames are drawn at it. */
    val onStageWidth: (Int) -> Unit = {},
)
