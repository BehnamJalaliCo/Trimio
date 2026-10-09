package io.trimio.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.render.PreviewClock

/** Desktop and web previews show the style's own canvas; footage playback arrives with WebCodecs (phase 11). */
@Composable
actual fun FootagePlayer(input: InputSource, timeline: Timeline, clock: PreviewClock, modifier: Modifier): Boolean = false
