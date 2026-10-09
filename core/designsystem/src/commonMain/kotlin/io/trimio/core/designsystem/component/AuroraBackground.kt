package io.trimio.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import io.trimio.core.designsystem.shader.ShaderSources
import io.trimio.core.designsystem.shader.rememberShader
import io.trimio.core.designsystem.shader.rememberShaderTime
import io.trimio.core.designsystem.shader.uniform
import io.trimio.core.designsystem.theme.Trimio

/**
 * Full-bleed animated aurora behind a screen. [energy] (0..1) is read every frame,
 * so pass a lambda backed by audio loudness for an audio-reactive background.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    energy: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = Trimio.colors
    val shader = rememberShader(ShaderSources.aurora)
    val time = rememberShaderTime()

    Box(
        modifier = modifier.drawBehind {
            if (shader == null) {
                drawRect(Brush.verticalGradient(listOf(colors.auroraA, colors.auroraBase, colors.auroraBase)))
                return@drawBehind
            }
            shader.uniform("iResolution", size.width, size.height)
            shader.uniform("iTime", time.value)
            shader.uniform("iEnergy", energy().coerceIn(0f, 1f))
            shader.uniform("cBase", colors.auroraBase)
            shader.uniform("cA", colors.auroraA)
            shader.uniform("cB", colors.auroraB)
            shader.uniform("cC", colors.auroraC)
            drawRect(shader.brush())
        },
        content = content,
    )
}
