package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Exercises every engine feature in one frame sequence and writes a contact sheet for review. */
class EngineSmokeTest {
    private val ink = Color(0xFFF2EDE4)
    private val lime = Color(0xFFD7FF3A)
    private val ember = Color(0xFFFF5B2E)

    private fun scene(): Composition {
        val headline = TextNode(
            text = "امروز بیت‌کوین پنج درصد رشد کرد",
            type = TypeSpec(BrandFonts.Role.Display, 1000, 132f, lineHeight = 1.05f),
            fill = ink.fill(),
            maxWidth = 900f,
            animators = listOf(TextAnimator(TextUnit.Word, UnitState(dy = 1.1f), at = 0.2f, duration = 0.7f, stagger = 0.08f, clipToLine = true)),
            decorations = listOf(Decoration.Block(2..3, at = 1.3f, color = lime, textColor = Color(0xFF0B0A09))),
            transform = Transform(x = 540f.anim, y = 760f.anim),
        )
        val letters = TextNode(
            text = "سیگنال خرید",
            type = TypeSpec(BrandFonts.Role.Expressive, 900, 96f),
            fill = Fill.Linear(listOf(ember, Color(0xFFFFB36B)), angle = 0f.anim),
            animators = listOf(TextAnimator(TextUnit.Char, UnitState(dy = -0.6f, scale = 0.3f, opacity = 0f, blur = 0.25f), at = 1.0f, duration = 0.5f, stagger = 0.035f, ease = Easing.Land)),
            transform = Transform(x = 540f.anim, y = 1080f.anim),
        )
        val counter = CounterNode(
            value = Anim.tween(0f, 5.2f, 0.6f, 1.8f, Easing.ExpoOut),
            decimals = 1, prefix = "+", suffix = "٪",
            type = TypeSpec(BrandFonts.Role.Accent, 900, 150f),
            fill = lime.fill(),
            transform = Transform(x = 540f.anim, y = 420f.anim, rotationX = Anim.tween(-80f, 0f, 0.6f, 1.2f, Easing.Land), opacity = Anim.tween(0f, 1f, 0.6f, 0.8f)),
        )
        val chart = ShapeNode(
            ShapeSpec.Polyline(listOf(0f to 300f, 150f to 240f, 300f to 270f, 450f to 160f, 600f to 190f, 760f to 40f)),
            stroke = Stroke(lime.fill(), 10f),
            trimEnd = Anim.tween(0f, 1f, 0.4f, 1.6f, Easing.Move),
            transform = Transform(x = 540f.anim, y = 1450f.anim),
        )
        val card = ShapeNode(
            ShapeSpec.Rect(860f.anim, 360f.anim, 36f.anim),
            fill = Color(0x22FFF6EB).fill(),
            stroke = Stroke(Color(0x40FFFFFF).fill(), 2f),
            transform = Transform(x = 540f.anim, y = 1450f.anim, scale = Anim.tween(0.8f, 1f, 0.2f, 0.9f, Easing.Land), opacity = Anim.tween(0f, 1f, 0.2f, 0.5f)),
        )
        val root = Group(
            listOf(
                EffectNode(Effect.Aurora(listOf(Color(0xFF0B0A09), Color(0xFF3A1A0E), Color(0xFF1B2A10), Color(0xFF2A1630)))),
                card, chart, counter, headline, letters,
                EffectNode(Effect.LightLeak(Color(0xFFFF8A3D), Anim.tween(0f, 1f, 1.6f, 2.6f, Easing.SineInOut), strength = anim(0f, 1.6f) { by(0.6f, 0.4f); by(0f, 0.6f) })),
                EffectNode(Effect.Vignette()),
                EffectNode(Effect.Grain(0.05f)),
            ),
        )
        return Composition(1080, 1920, 30, 3f, Color(0xFF0B0A09), root, camera = Camera(zoom = Anim.tween(1f, 1.04f, 0f, 3f, Easing.SineInOut)))
    }

    @Test
    fun rendersEveryFeature() {
        val kit = MotionTestKit(scene())
        val out = File("build/motion")
        kit.contactSheet(listOf(0.3f, 0.6f, 0.9f, 1.2f, 1.5f, 1.8f, 2.1f, 2.9f), out.resolve("smoke.png"))
        kit.png(2.9f, out.resolve("smoke-final.png"))
        if (MotionTestKit.ffmpeg()) kit.mp4(out.resolve("smoke.mp4"))
        assertTrue(out.resolve("smoke.png").length() > 0)
    }
}
