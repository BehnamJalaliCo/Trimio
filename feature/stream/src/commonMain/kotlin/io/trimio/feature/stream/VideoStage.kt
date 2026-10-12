package io.trimio.feature.stream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import kotlinx.coroutines.delay

/**
 * The finished edit playing: [video]'s picture with the poster until the first play, a glass play
 * control, and a transport (time, a scrub bar, full screen) that fades away while it plays and
 * comes back on a tap. Time runs left to right in every language, as on any player. Space plays
 * and pauses, the arrows step five seconds and Escape leaves full screen (desktop).
 */
@Composable
fun VideoStage(
    video: VideoPlayer,
    poster: ImageBitmap?,
    title: String,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Starts with the transport showing (screenshots); otherwise it shows until the first play. */
    initiallyStarted: Boolean = false,
) {
    val s by video.state.collectAsState()
    var started by remember(video) { mutableStateOf(initiallyStarted || s.position > 0f) }
    var controls by remember(video) { mutableStateOf(true) }
    var touched by remember(video) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.playing, controls, touched) {
        if (s.playing && controls) {
            delay(HIDE_MS)
            controls = false
        }
    }
    LaunchedEffect(fullscreen) { if (fullscreen) runCatching { focus.requestFocus() } }
    val toggle = {
        if (s.playing) video.pause() else video.play()
        started = true
        touched++
        Unit
    }
    val shown = !started || controls || !s.playing
    val alpha by animateFloatAsState(if (shown) 1f else 0f)
    Box(
        modifier.background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && key(e.key, s, video, fullscreen, onFullscreen, toggle) }
            .pointerInput(video, fullscreen) {
                detectTapGestures(
                    onTap = { if (!started) toggle() else { controls = !controls; touched += 1 } },
                    onDoubleTap = { onFullscreen(!fullscreen) },
                )
            },
    ) {
        video.Picture(Modifier.fillMaxSize())
        if (!started && poster != null) Image(poster, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (alpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(alpha)) {
                if (fullscreen) {
                    SigRoundButton(TrimioIcons.Close, tr("خروج از تمام‌صفحه", "Exit full screen"), { onFullscreen(false) }, Modifier.align(Alignment.TopEnd).padding(18.dp))
                }
                PlayControl(s.playing, if (fullscreen) 72.dp else 58.dp, toggle, Modifier.align(Alignment.Center))
                Transport(s, fullscreen, Modifier.align(Alignment.BottomCenter), onSeek = { t -> video.seek(t); started = true; touched += 1 }, onFullscreen = { onFullscreen(!fullscreen) })
            }
        }
        s.failed?.let { Failed(Modifier.align(Alignment.TopCenter)) }
    }
}

private fun key(key: Key, s: PlaybackState, video: VideoPlayer, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, toggle: () -> Unit): Boolean = when (key) {
    Key.Spacebar, Key.K -> true.also { toggle() }
    Key.DirectionLeft -> true.also { video.seek((s.position - STEP_S).coerceAtLeast(0f)) }
    Key.DirectionRight -> true.also { video.seek((s.position + STEP_S).coerceAtMost(s.duration)) }
    Key.F -> true.also { onFullscreen(!fullscreen) }
    Key.Escape -> fullscreen.also { if (it) onFullscreen(false) }
    else -> false
}

@Composable
private fun PlayControl(playing: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier) {
    // A dark disc under the glass keeps the control readable over light footage (and outside a glass scene, full screen).
    SigGlass(
        modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = SCRIM)).clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape, overMedia = true,
    ) {
        TrimioIcon(
            if (playing) TrimioIcons.Pause else TrimioIcons.Play, if (playing) tr("مکث", "Pause") else tr("پخش", "Play"),
            tint = SigNoir.ink, size = size * ICON_SHARE, modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Time, the scrub bar and full screen over a soft shade at the foot of the picture; always left to right. */
@Composable
private fun Transport(s: PlaybackState, fullscreen: Boolean, modifier: Modifier, onSeek: (Float) -> Unit, onFullscreen: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))))
                .padding(start = 14.dp, end = 8.dp, top = 28.dp, bottom = if (fullscreen) 22.dp else 6.dp),
        ) {
            Scrubber(s.progress, s.duration, onSeek, Modifier.fillMaxWidth().padding(end = 6.dp).height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    localizedNumber("${clock((s.position * MS).toLong())} / ${clock((s.duration * MS).toLong())}"),
                    style = Sig.type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight(700)), color = SigNoir.ink, maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(38.dp).clickable(role = Role.Button, onClick = onFullscreen),
                    contentAlignment = Alignment.Center,
                ) {
                    TrimioIcon(
                        if (fullscreen) Shrink else Expand, if (fullscreen) tr("خروج از تمام‌صفحه", "Exit full screen") else tr("تمام‌صفحه", "Full screen"),
                        tint = SigNoir.ink, size = 18.dp,
                    )
                }
            }
        }
    }
}

/** A thin ink track with the played part solid and a knob; tap or drag anywhere on it to move. */
@Composable
private fun Scrubber(progress: Float, duration: Float, onSeek: (Float) -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    val shown = dragging ?: progress
    val knob = Sig.colors.emphasis
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    Canvas(
        modifier
            .pointerInput(duration) { detectTapGestures { onSeek(at(it.x) * duration) } }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = at(it.x); onSeek(at(it.x) * duration) },
                    onDragEnd = { dragging?.let { onSeek(it * duration) }; dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    dragging = at(change.position.x)
                    onSeek(at(change.position.x) * duration)
                }
            },
    ) {
        width = size.width.coerceAtLeast(1f)
        val y = size.height / 2
        val h = 3.dp.toPx()
        drawRoundRect(SigNoir.ink.copy(alpha = 0.28f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h))
        drawRoundRect(SigNoir.ink, Offset(0f, y - h / 2), Size(size.width * shown, h), CornerRadius(h))
        drawCircle(knob, radius = (if (dragging != null) 8.dp else 6.dp).toPx(), center = Offset(size.width * shown, y))
    }
}

@Composable
private fun Failed(modifier: Modifier) {
    SigGlass(modifier.padding(16.dp), overMedia = true) {
        Text(
            tr("این ویدیو اینجا پخش نشد؛ «اشتراک» یا «ذخیره» آن را در پخش‌کنندهٔ دستگاه باز می‌کند.", "This video did not play here; Share or Save opens it in your device's player."),
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = Sig.type.meta.copy(fontSize = 11.5.sp), color = SigNoir.ink,
        )
    }
}

private fun stroke(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()

/** Four corners opening out (the icon set's hand: 24 grid, 1.8 stroke, round caps). */
private val Expand = stroke("expand") {
    moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
    moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
    moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
    moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
}

/** Four corners folding in. */
private val Shrink = stroke("shrink") {
    moveTo(9f, 4f); lineTo(9f, 9f); lineTo(4f, 9f)
    moveTo(15f, 4f); lineTo(15f, 9f); lineTo(20f, 9f)
    moveTo(20f, 15f); lineTo(15f, 15f); lineTo(15f, 20f)
    moveTo(4f, 15f); lineTo(9f, 15f); lineTo(9f, 20f)
}

private const val HIDE_MS = 2_600L
private const val STEP_S = 5f
private const val MS = 1000f
private const val ICON_SHARE = 0.38f
private const val SCRIM = 0.42f
