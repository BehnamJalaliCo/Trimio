package io.trimio.feature.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import io.trimio.engine.motion.score.Compiler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

/**
 * A compiled sample playing live, drawn by the same renderer as an export and cropped to fill the
 * card. It advances at 30 frames a second (the edit's own rate, and half the cost on 60 Hz
 * screens); paused it holds [still] seconds in.
 */
@Composable
fun LiveSample(output: Compiler.Output, fonts: MotionFonts, media: MediaSource, playing: Boolean, modifier: Modifier = Modifier, still: Float = STILL) {
    val resolver = LocalFontFamilyResolver.current
    val renderer = remember(output) { MotionRenderer(output.composition, TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr), fonts, media) }
    val c = output.composition
    var t by remember(output) { mutableFloatStateOf(still.coerceAtMost(c.duration)) }
    LaunchedEffect(output, playing) {
        if (!playing) return@LaunchedEffect
        val from = t
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = from + (now - start) / NANOS
                t = (floor(seconds * FPS) / FPS) % c.duration.coerceAtLeast(MIN_LOOP)
            }
        }
    }
    // The motion engine lays out left to right in frame pixels, whatever the screen's direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.clipToBounds()) {
            val k = maxOf(size.width / c.width, size.height / c.height)
            translate((size.width - c.width * k) / 2f, (size.height - c.height * k) / 2f) {
                scale(k, k, pivot = Offset.Zero) { renderer.render(this, t) }
            }
        }
    }
}

/**
 * Compiles each style's sample once ([io.trimio.engine.production.StyleSamples]), one at a time
 * (the compiler's text engine is shared), and keeps them for the session.
 */
class SampleShelf(private val compile: (String) -> Compiler.Output) {
    private val lock = Mutex()
    private val done = HashMap<String, Compiler.Output>()

    fun cached(id: String): Compiler.Output? = done[id]

    suspend fun of(id: String): Compiler.Output = lock.withLock { done[id] ?: compile(id).also { done[id] = it } }
}

/** The word a style's headline says in the picker and the gallery («نوآر», «سود!», «1987»). */
fun sampleWord(id: String): String = io.trimio.feature.create.StyleCards.all.firstOrNull { it.id == id }?.sample ?: id

/**
 * Stand-in footage for the samples: warm key light, cool fill, a rim-lit speaker (as the boards
 * draw footage), so styles that speak over the picture show how they sit on one.
 */
fun standInFootage(width: Int = STAND_W, height: Int = STAND_H): MediaSource {
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0D0C0B))
        drawRect(Brush.radialGradient(listOf(Color(0xA6FF965A), Color.Transparent), center = Offset(w * 0.74f, h * 0.26f), radius = w * 0.5f))
        drawRect(Brush.radialGradient(listOf(Color(0x593C5AA0), Color.Transparent), center = Offset(w * 0.12f, h * 0.78f), radius = w * 0.55f))
        val rim = Brush.horizontalGradient(listOf(Color(0xFF0B0908), Color(0xFF1C1512), Color(0xFF6B3A22)), startX = w * 0.22f, endX = w * 0.74f)
        drawRoundRect(rim, topLeft = Offset(w * 0.22f, h * 0.5f), size = Size(w * 0.56f, h * 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.24f))
        drawOval(rim, topLeft = Offset(w * 0.36f, h * 0.27f), size = Size(w * 0.28f, h * 0.26f))
    }
    return MediaSource { _, _ -> image }
}

private const val FPS = 30f
private const val NANOS = 1_000_000_000f
private const val MIN_LOOP = 0.5f
/** The moment a paused card shows: the sample's first headline is on screen. */
private const val STILL = 1.6f
private const val STAND_W = 540
private const val STAND_H = 960
