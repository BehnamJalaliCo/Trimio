package io.trimio.core.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import io.trimio.core.brand.resources.Res
import io.trimio.core.brand.resources.ui
import org.jetbrains.compose.resources.Font
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders a type specimen of the brand families to build/specimen (a review artefact, not shipped). */
class TypeSpecimenTest {
    private val ink = Color(0xFFF4F1EA)
    private val dim = Color(0xFF8E8A82)

    @Composable
    private fun Label(text: String) = Text(text, style = TextStyle(fontFamily = BrandFonts.accent(), fontWeight = FontWeight(500), fontSize = 11.sp, color = dim, letterSpacing = 1.5.sp))

    @Composable
    private fun Specimen() {
        val ui = BrandFonts.ui()
        val expressive = BrandFonts.expressive()
        val display = BrandFonts.display()
        val accent = BrandFonts.accent()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0B0B0D), Color(0xFF141317))))
                    .padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Label("TYPE SYSTEM · ۰۱")
                Text("موشن، با صدای تو.", style = TextStyle(fontFamily = display, fontWeight = FontWeight(1000), fontSize = 64.sp, color = ink, lineHeight = 70.sp))
                Text(
                    "ویدیو یا صدا بده؛ Trimio برایش فکر می‌کند، طراحی می‌کند، می‌سازد.",
                    style = TextStyle(fontFamily = ui, fontWeight = FontWeight(400), fontSize = 17.sp, color = dim, lineHeight = 28.sp),
                )
                Spacer(Modifier.height(10.dp))

                Label("UI · VARIABLE 100 → 1000")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (1..10).forEach { w -> Text("آ", style = TextStyle(fontFamily = ui, fontWeight = FontWeight(w * 100), fontSize = 40.sp, color = ink)) }
                }
                Text("تنظیمات · پروژه‌ها · ساخت ویدیوی جدید · 4K · ۲۴ فریم", style = TextStyle(fontFamily = ui, fontWeight = FontWeight(500), fontSize = 16.sp, color = ink))

                Label("UI · DOTS AXIS 0 → 4")
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    (0..4).forEach { d ->
                        val axes = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("dots", d.toFloat()))
                        val family = FontFamily(Font(Res.font.ui, weight = FontWeight(800), variationSettings = axes))
                        Text("بیت", style = TextStyle(fontFamily = family, fontSize = 40.sp, color = if (d == 0) ink else Color(0xFFE9C46A)))
                    }
                }

                Label("EXPRESSIVE · VARIABLE")
                Text("سیگنال خرید", style = TextStyle(fontFamily = expressive, fontWeight = FontWeight(900), fontSize = 56.sp, color = ink))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(100, 300, 500, 700, 900).forEach { w -> Text("رشد", style = TextStyle(fontFamily = expressive, fontWeight = FontWeight(w), fontSize = 34.sp, color = ink)) }
                }

                Label("DISPLAY · 11 CUTS")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 300, 500, 700, 900, 950, 1000).forEach { w -> Text("هیجان", style = TextStyle(fontFamily = display, fontWeight = FontWeight(w), fontSize = 30.sp, color = ink)) }
                }

                Label("ACCENT · NUMBERS & TAGS")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("\u2066+۵٫۲٪\u2069", style = TextStyle(fontFamily = accent, fontWeight = FontWeight(900), fontSize = 44.sp, color = Color(0xFF7BE0A7)))
                    Text("BTC 98,420", style = TextStyle(fontFamily = accent, fontWeight = FontWeight(700), fontSize = 44.sp, color = ink))
                }
                Text("لحظهٔ تصمیم", style = TextStyle(fontFamily = accent, fontWeight = FontWeight(300), fontSize = 34.sp, color = ink))
                Spacer(Modifier.width(1.dp))
            }
        }
    }

    @Test
    fun renderSpecimen() {
        val density = 2f
        ImageComposeScene((720 * density).toInt(), (1280 * density).toInt(), Density(density)) { Specimen() }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/specimen").mkdirs()
            File("build/specimen/type.png").writeBytes(png)
            assertTrue(png.size > 50_000)
        }
    }
}
