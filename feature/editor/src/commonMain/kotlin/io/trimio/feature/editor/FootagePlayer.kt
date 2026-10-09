package io.trimio.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.render.PreviewClock

/**
 * Plays the source media under the motion-graphics overlay, following the edit's cuts, and drives
 * [clock] so captions stay locked to the picture and the voice. Returns true when footage is drawn
 * by the platform (the overlay must then skip the style background).
 */
@Composable
expect fun FootagePlayer(input: InputSource, timeline: Timeline, clock: PreviewClock, modifier: Modifier = Modifier): Boolean
