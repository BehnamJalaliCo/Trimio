package io.trimio.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import io.trimio.core.designsystem.shader.ShaderSources
import io.trimio.core.designsystem.shader.rememberShader
import io.trimio.core.designsystem.shader.rememberShaderTime
import io.trimio.core.designsystem.theme.Trimio

/** Film grain over the content at [amount] (0.02-0.03 is the house default). Static when motion is reduced. */
@Composable
fun Modifier.filmGrain(amount: Float = 0.025f): Modifier {
    val shader = rememberShader(ShaderSources.filmGrain) ?: return this
    val time = rememberShaderTime()
    val animate = !Trimio.motion.isReduced
    return drawWithContent {
        drawContent()
        shader.uniform("iResolution", size.width, size.height)
        shader.uniform("iTime", if (animate) time.value else 0f)
        shader.uniform("iAmount", amount)
        drawRect(shader.brush())
    }
}
