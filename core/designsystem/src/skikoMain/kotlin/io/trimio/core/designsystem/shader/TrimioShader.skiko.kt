package io.trimio.core.designsystem.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** SkSL implementation for iOS, desktop and web (all render through Skia). */
actual class TrimioShader actual constructor(source: String) {
    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))

    actual fun uniform(name: String, value: Float) = builder.uniform(name, value)

    actual fun uniform(name: String, x: Float, y: Float) = builder.uniform(name, x, y)

    actual fun uniform(name: String, x: Float, y: Float, z: Float) = builder.uniform(name, x, y, z)

    // Skia shaders are immutable snapshots of the uniforms, so build one per draw.
    actual fun brush(): Brush = ShaderBrush(builder.makeShader().asComposeShader())
}
