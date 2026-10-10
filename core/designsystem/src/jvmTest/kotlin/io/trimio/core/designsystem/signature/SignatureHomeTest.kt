package io.trimio.core.designsystem.signature

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import io.trimio.core.designsystem.component.GlassScene
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.filmGrain
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Stand-in for real footage in review renders: key light, cool fill, bokeh and a rim-lit subject. */
@Composable
internal fun CinematicPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF0D0C0B)).drawBehind {
            val w = size.width
            val h = size.height
            drawRect(Brush.radialGradient(listOf(Color(0xA6FF965A), Color.Transparent), center = Offset(w * 0.74f, h * 0.26f), radius = w * 0.5f))
            drawRect(Brush.radialGradient(listOf(Color(0x593C5AA0), Color.Transparent), center = Offset(w * 0.12f, h * 0.78f), radius = w * 0.55f))
            val bokeh = Offset(w * 0.3f, h * 0.26f)
            drawCircle(Brush.radialGradient(listOf(Color(0x8CFFD2A0), Color.Transparent), center = bokeh, radius = w * 0.14f), radius = w * 0.14f, center = bokeh)
            // Subject: shoulders and head, rim-lit from the key side.
            val rim = Brush.horizontalGradient(listOf(Color(0xFF0B0908), Color(0xFF1C1512), Color(0xFF6B3A22)), startX = w * 0.22f, endX = w * 0.74f)
            drawRoundRect(
                rim, topLeft = Offset(w * 0.22f, h * 0.4f),
                size = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.7f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.24f),
            )
            drawOval(rim, topLeft = Offset(w * 0.36f, h * 0.18f), size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.3f))
        },
    )
}

@Composable
private fun StatusBar() {
    Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 32.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("100", style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = Sig.colors.ink)
        Text("9:41", style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = Sig.colors.ink)
    }
}

@Composable
internal fun SignatureHome() {
    val c = Sig.colors
    val t = Sig.type
    GlassScene(
        Modifier.fillMaxSize().background(c.canvas),
        background = {},
    ) {
        Column(Modifier.fillMaxSize()) {
            StatusBar()
            Row(Modifier.fillMaxWidth().gutter().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SigChip("آفلاین · آماده", leading = { DirectorDot(size = 10.dp) })
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF4B3A2E), Color(0xFF1B1613)))).border(1.dp, c.lineStrong, CircleShape))
                }
            }
            Column(Modifier.gutter().padding(top = 22.dp)) {
                Text("سه‌شنبه، ۱۷ مهر", style = t.meta, color = c.muted)
                Spacer(Modifier.height(8.dp))
                DisplayText(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("امروز چه\nبسازیم")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = c.action)); append("؟"); pop()
                    },
                    style = t.hero,
                )
            }
            Column(Modifier.padding(top = 20.dp)) {
                Hairline(strong = true)
                Row(Modifier.height(IntrinsicSize.Min).gutter()) {
                    EntryTile("ویدیو", "موشن روی تصویر خودت", "A", TrimioIcons.VideoFrame, Modifier.weight(1f).padding(end = 14.dp, top = 14.dp, bottom = 14.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.lineStrong))
                    EntryTile("فقط صدا", "تصویر از صفر ساخته می‌شود", "B", TrimioIcons.Mic, Modifier.weight(1f).padding(start = 14.dp, top = 14.dp, bottom = 14.dp))
                }
                Hairline(strong = true)
            }
            Row(Modifier.fillMaxWidth().gutter().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ادامه بده", style = t.bodyStrong, color = c.ink)
                MicroLabel("LATEST")
            }
            Box(Modifier.fillMaxWidth().height(218.dp).padding(top = 12.dp)) {
                CinematicPlaceholder(
                    Modifier.size(150.dp, 190.dp).align(Alignment.TopEnd).offset(x = (-4).dp, y = 10.dp)
                        .graphicsLayer { rotationY = 32f; scaleX = 0.88f; scaleY = 0.88f; alpha = if (c.isDark) 0.5f else 0.85f; cameraDistance = 12f * density }
                        .clip(RoundedCornerShape(18.dp)),
                )
                Box(
                    Modifier.padding(start = 22.dp, end = 58.dp).fillMaxWidth().height(200.dp)
                        .graphicsLayer { shadowElevation = 30.dp.toPx(); shape = RoundedCornerShape(20.dp); clip = true },
                ) {
                    CinematicPlaceholder(Modifier.fillMaxSize())
                    Box(Modifier.fillMaxWidth().height(14.dp).background(Color.Black))
                    Box(Modifier.fillMaxWidth().height(14.dp).align(Alignment.BottomCenter).background(Color.Black))
                    Column(Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 28.dp)) {
                        MicroLabel("BTC · +5.2%", color = c.emphasis)
                        DisplayText("سیگنال", style = t.hero.copy(fontSize = 40.sp, lineHeight = 40.sp), modifier = Modifier.padding(top = 6.dp), color = c.onMedia)
                        Box(Modifier.padding(top = 8.dp).size(58.dp, 3.dp).background(c.action))
                    }
                    SigGlass(
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 22.dp).height(30.dp),
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        overMedia = true,
                    ) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrimioIcon(TrimioIcons.Play, null, tint = c.onMedia, size = 12.dp)
                            Text("۰۰:۴۲", style = t.meta.copy(fontWeight = FontWeight(600), fontSize = 11.sp), color = c.onMedia)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().gutter().padding(top = 8.dp).height(54.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⁦02⁩", style = t.index, color = c.muted, modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text("پادکست قسمت ۱۲", style = t.bodyStrong, color = c.ink)
                    Text("فقط صدا · کینتیک", style = t.meta.copy(fontSize = 11.sp), color = c.muted)
                }
                SigChip("● ۶۸٪", container = c.emphasisSoft, content = c.emphasisSoftContent)
            }
            Hairline(Modifier.gutter())
        }
        SigGlass(
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 28.dp).fillMaxWidth().height(62.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 8.dp),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DirectorDot()
                    Text("به کارگردان بگو چه می‌خواهی…", style = t.body.copy(fontWeight = FontWeight(400), fontSize = 14.5.sp), color = c.muted)
                }
                SigActionSquare(TrimioIcons.Send, "ارسال", {})
            }
        }
    }
}

/** "trimio" with the ember dot (drawn, so it never depends on bidi placement of a period). */
@Composable
internal fun BrandMark() {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("trimio")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Sig.colors.action)); append("."); pop()
            },
            style = Sig.type.figure.copy(fontSize = 17.sp), color = Sig.colors.ink,
        )
    }
}

@Composable
private fun EntryTile(title: String, hint: String, letter: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    val c = Sig.colors
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TrimioIcon(icon, null, tint = c.ink)
            MicroLabel(letter)
        }
        Text(title, style = Sig.type.title, color = c.ink, modifier = Modifier.padding(top = 16.dp))
        Text(hint, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted)
    }
}

class SignatureHomeTest {
    @Test
    fun renderHome() {
        for (paper in listOf(false, true)) {
            val density = 2f
            ImageComposeScene((390 * density).toInt(), (844 * density).toInt(), Density(density)) {
                SignatureTheme(paper = paper) { Box(Modifier.fillMaxSize().filmGrain(if (paper) 0f else 0.03f)) { SignatureHome() } }
            }.use { scene ->
                for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
                val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File("build/signature").mkdirs()
                File("build/signature/home-${if (paper) "paper" else "noir"}.png").writeBytes(png)
                assertTrue(png.size > 40_000)
            }
        }
        assertTrue(Trimio.toString().isNotEmpty())
    }
}
