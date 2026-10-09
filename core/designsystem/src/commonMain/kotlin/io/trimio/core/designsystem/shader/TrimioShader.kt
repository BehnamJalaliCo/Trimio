package io.trimio.core.designsystem.shader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A GPU runtime shader with named uniforms.
 *
 * One source string runs everywhere: AGSL on Android 13+ (`android.graphics.RuntimeShader`) and
 * SkSL through Skia on iOS, desktop and web. Both dialects share syntax for what we use:
 * `uniform float/float2/float3`, `half4 main(float2 fragCoord)`, and output must be premultiplied.
 */
expect class TrimioShader(source: String) {
    fun uniform(name: String, value: Float)
    fun uniform(name: String, x: Float, y: Float)
    fun uniform(name: String, x: Float, y: Float, z: Float)

    /** A brush reflecting the current uniforms. Call after setting uniforms, inside the draw phase. */
    fun brush(): Brush
}

fun TrimioShader.uniform(name: String, color: Color) = uniform(name, color.red, color.green, color.blue)

/**
 * Compiles [source] once. Returns null if the device's shader compiler rejects it, so callers
 * can fall back to a plain gradient instead of crashing on an odd GPU driver.
 */
@Composable
fun rememberShader(source: String): TrimioShader? = remember(source) {
    runCatching { TrimioShader(source) }.getOrNull()
}

/** Seconds since first composition, advancing every frame. Read it in the draw phase only. */
@Composable
fun rememberShaderTime(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) {
        withFrameNanos { now -> value = (now - start) / 1_000_000_000f }
    }
}
