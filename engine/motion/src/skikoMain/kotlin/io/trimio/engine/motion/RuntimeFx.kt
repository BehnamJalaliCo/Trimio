package io.trimio.engine.motion

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.skiaShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** Skia: the builder holds uniforms; a shader is an immutable snapshot, so one is made per draw. */
internal actual class RuntimeFx actual constructor(source: String) {
    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))

    actual fun uniform(name: String, vararg values: Float) = when (values.size) {
        1 -> builder.uniform(name, values[0])
        2 -> builder.uniform(name, values[0], values[1])
        3 -> builder.uniform(name, values[0], values[1], values[2])
        else -> builder.uniform(name, values[0], values[1], values[2], values[3])
    }

    actual fun child(name: String, shader: Shader) = builder.child(name, shader.skiaShader)

    actual fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())

    actual fun drawsOn(canvas: Canvas): Boolean = true
}
