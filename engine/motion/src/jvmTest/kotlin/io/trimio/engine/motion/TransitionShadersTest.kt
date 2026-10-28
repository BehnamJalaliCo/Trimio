package io.trimio.engine.motion

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two-buffer engine: every program compiles, every declared uniform is fed, endpoints draw
 * exactly one frame, parameters tween, and the crossfade fallback keeps the same endpoints.
 */
class TransitionShadersTest {

    @Test
    fun everyProgramCompiles() {
        for ((id, program) in TransitionShaders.programs) {
            assertTrue(runCatching { RuntimeFx(program.source) }.isSuccess, "$id compiles as SkSL/AGSL")
        }
    }

    @Test
    fun everyDeclaredUniformIsProvidedByTheParams() {
        val provided = TransitionParams().uniforms(0f).keys
        for ((id, program) in TransitionShaders.programs) {
            val missing = program.uniforms - provided
            assertTrue(missing.isEmpty(), "$id declares $missing the params never set")
        }
    }

    @Test
    fun endpointsShowOnlyOneFrame() {
        for ((id, program) in TransitionShaders.programs) {
            for (point in samplePoints) {
                val start = sample(program, 0f, point)
                near(start.red, 1f, "$id at t=0 draws only the outgoing frame")
                near(start.blue, 0f, "$id at t=0 draws only the outgoing frame")
                val end = sample(program, 1f, point)
                near(end.red, 0f, "$id at t=1 draws only the incoming frame")
                near(end.blue, 1f, "$id at t=1 draws only the incoming frame")
            }
        }
    }

    @Test
    fun fadeBlendsHalfway() {
        val mid = sample(TransitionShaders.programs.getValue("fade"), 0.5f)
        near(mid.red, 0.5f, "the fade is a linear crossfade")
        near(mid.blue, 0.5f, "the fade is a linear crossfade")
    }

    @Test
    fun crossfadeFallbackKeepsEndpointsExact() {
        TransitionShaders.enabled = false
        try {
            val program = TransitionShaders.programs.getValue("circleopen")
            val start = sample(program, 0f)
            near(start.red, 1f, "the fallback shows only the outgoing frame at t=0")
            val end = sample(program, 1f)
            near(end.blue, 1f, "the fallback shows only the incoming frame at t=1")
        } finally {
            TransitionShaders.enabled = true
        }
    }

    @Test
    fun parametersTweenThroughTheCut() {
        val params = TransitionParams(
            colorPhase = Anim.tween(0f, 1f, 0f, 1f, Easing.Linear),
            blur = Anim.tween(0f, 24f, 0f, 1f, Easing.Linear),
        )
        val mid = params.uniforms(0.5f)
        near(mid.getValue("uColorPhase")[0], 0.5f, "colorPhase tweens linearly")
        near(mid.getValue("uBlur")[0], 12f, "the blur knob tweens linearly")
    }

    @Test
    fun whipAndZoomMovesCarryMotionBlur() {
        val move = TransitionShaders.move("whip", "zoom", s = 1f, e = 5f, w = 1080f, settle = Easing.Linear)
        near(move.x.at(1f), 1080f * 1.1f, "the whip enters from a frame-width away")
        near(move.x.at(1.24f), 0f, "the whip lands in its 0.24 s window")
        assertTrue(move.blur.at(1f) > 0f, "the whip enters smeared, not strobing")
        near(move.blur.at(1.24f), 0f, "the smear clears as the slide lands")
        near(move.scale.at(5f), 1.4f, "the zoom exit rushes past the viewer")
        near(move.opacity.at(5f), 0f, "the zoom exit fades out on the cut")
        assertTrue(move.blur.at(5f) > 0f, "the zoom exit is smeared along its move")
    }

    @Test
    fun portsKeepTheirUpstreamLicenseHeaders() {
        for (id in listOf("fade", "fadecolor", "directionalwarp", "crosswarp", "lumawipe", "pixelize", "circleopen", "noisedissolve")) {
            val source = TransitionShaders.programs.getValue(id).source
            assertTrue("License: MIT" in source, "$id keeps its MIT header")
            assertTrue("Author:" in source, "$id keeps its author")
        }
    }

    private fun sample(program: TransitionProgram, progress: Float, at: Pair<Int, Int> = 16 to 16): Color {
        val target = ImageBitmap(SIZE, SIZE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(SIZE.toFloat(), SIZE.toFloat())) {
            drawTransition(solid(outgoing), solid(incoming), program, progress)
        }
        return target.toPixelMap()[at.first, at.second]
    }

    private fun solid(color: Color): ImageBitmap {
        val image = ImageBitmap(SIZE, SIZE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(SIZE.toFloat(), SIZE.toFloat())) {
            drawRect(color)
        }
        return image
    }

    private fun near(a: Float, b: Float, message: String) = assertTrue(abs(a - b) < EPSILON, "$message ($a != $b)")

    private val samplePoints = listOf(4 to 4, 16 to 16, 28 to 28, 16 to 4, 4 to 28)
    private val outgoing = Color(0xFFFF0000)
    private val incoming = Color(0xFF0000FF)
    private const val SIZE = 32
    private const val EPSILON = 0.02f
}
