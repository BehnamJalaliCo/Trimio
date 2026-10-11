package io.trimio.engine.motion

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush

/** AGSL (minSdk 33, so RuntimeShader always exists); it reads its uniforms at draw time. */
internal actual class RuntimeFx actual constructor(source: String) {
    private val shader = RuntimeShader(source)
    private val brush = ShaderBrush(shader)

    actual fun uniform(name: String, vararg values: Float) = when (values.size) {
        1 -> shader.setFloatUniform(name, values[0])
        2 -> shader.setFloatUniform(name, values[0], values[1])
        3 -> shader.setFloatUniform(name, values[0], values[1], values[2])
        else -> shader.setFloatUniform(name, values[0], values[1], values[2], values[3])
    }

    actual fun child(name: String, shader: Shader) = this.shader.setInputShader(name, shader)

    actual fun brush(): Brush = brush
}
