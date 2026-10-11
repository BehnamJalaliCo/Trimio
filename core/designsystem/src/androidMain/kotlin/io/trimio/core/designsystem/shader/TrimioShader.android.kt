package io.trimio.core.designsystem.shader

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.nativeCanvas

/** AGSL implementation. minSdk is 33, so RuntimeShader is always available. */
actual class TrimioShader actual constructor(source: String) {
    private val shader = RuntimeShader(source)
    private val brush = ShaderBrush(shader)

    actual fun uniform(name: String, value: Float) = shader.setFloatUniform(name, value)

    actual fun uniform(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)

    actual fun uniform(name: String, x: Float, y: Float, z: Float) = shader.setFloatUniform(name, x, y, z)

    // RuntimeShader reads uniforms at draw time, so one brush instance stays valid.
    actual fun brush(): Brush = brush

    actual fun drawsOn(canvas: Canvas): Boolean = canvas.nativeCanvas.isHardwareAccelerated
}
