package io.trimio.engine.motion

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Shader

/**
 * One SkSL runtime effect with named float uniforms and child shaders. The same source runs as
 * SkSL through Skia (desktop render, tests, iOS) and as AGSL on Android 13+
 * (`android.graphics.RuntimeShader`), so an effect is written once ([FxSources]). Construction
 * throws when the device's compiler rejects the source; callers then draw the CPU fallback.
 */
internal expect class RuntimeFx(source: String) {
    /** A float, float2, float3 or float4 uniform (1–4 values). */
    fun uniform(name: String, vararg values: Float)

    /** A `uniform shader` input (the content a distortion samples). */
    fun child(name: String, shader: Shader)

    /** A brush with the current uniforms. Call after setting them, in the draw phase. */
    fun brush(): Brush

    /** Whether [canvas] can draw this effect (Android draws runtime shaders only on GPU canvases, not into bitmaps). */
    fun drawsOn(canvas: Canvas): Boolean
}
