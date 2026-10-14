package io.trimio.core.designsystem.screen

import androidx.compose.runtime.Composable

/** Keeps the phone's screen on while [on] (a measurement that must keep the app in front); no effect on the desktop. */
@Composable
expect fun KeepScreenOn(on: Boolean)
