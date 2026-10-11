package io.trimio.feature.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.brand.BrandFonts
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.motion.recipe.Look

/** A still of one style: its canvas and living light, a word in its headline voice and its emphasis mark. */
@Composable
fun StyleSwatch(card: StyleCard, modifier: Modifier = Modifier) {
    val families = Trimio.type.families
    val persian = card.sample.any { it in '؀'..'ۿ' }
    val family = when (card.role) {
        BrandFonts.Role.Accent -> if (persian) families.display else families.accent
        BrandFonts.Role.Display -> families.display
        BrandFonts.Role.Expressive -> families.expressive
        BrandFonts.Role.Ui -> families.ui
    }
    val style = Sig.type.hero.copy(fontFamily = family, fontWeight = FontWeight(card.weight.coerceAtLeast(700)), fontSize = 30.sp, lineHeight = 34.sp)
    // Latin samples (a price, a year) stay left-to-right inside the Persian layout.
    val text = (if (card.caps) card.sample.uppercase() else card.sample).let { if (persian) it else "\u2066$it\u2069" }
    Box(
        modifier.background(card.canvas).drawBehind {
            val w = size.width
            val h = size.height
            card.aurora.drop(1).forEachIndexed { i, c ->
                val center = when (i) { 0 -> Offset(w * 0.85f, h * 0.15f); 1 -> Offset(w * 0.1f, h * 0.9f); else -> Offset(w * 0.5f, h * 0.55f) }
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.9f), Color.Transparent), center = center, radius = w * 0.75f), radius = w * 0.75f, center = center)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        when (card.mark) {
            Look.Mark.Block -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, style = style, color = card.ink, maxLines = 1)
                Box(Modifier.padding(top = 2.dp).size(30.dp, 6.dp).background(card.accent))
            }
            Look.Mark.Ink -> Text(text, style = style, color = card.accent, maxLines = 1)
            Look.Mark.Underline -> Text(
                text, style = style, color = card.ink, maxLines = 1,
                modifier = Modifier.drawBehind { drawLine(card.accent, Offset(0f, size.height * 0.92f), Offset(size.width, size.height * 0.92f), strokeWidth = 3.dp.toPx()) },
            )
            Look.Mark.Circle -> Text(
                text, style = style, color = card.ink, maxLines = 1,
                modifier = Modifier.drawBehind {
                    drawOval(
                        card.accent, topLeft = Offset(-size.width * 0.18f, -size.height * 0.05f),
                        size = size.copy(width = size.width * 1.36f, height = size.height * 1.1f), style = Stroke(2.dp.toPx()),
                    )
                }.padding(horizontal = 4.dp),
            )
        }
    }
}

/** "خودکار": the director chooses; drawn in the director's iridescence, never a style's palette. */
@Composable
fun AutoSwatch(modifier: Modifier = Modifier) {
    val c = SigNoir
    Box(
        modifier.background(Color(0xFF100E0D)).drawBehind {
            c.director.forEachIndexed { i, col ->
                val center = Offset(size.width * (0.2f + 0.2f * i), size.height * (if (i % 2 == 0) 0.25f else 0.8f))
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.45f), Color.Transparent), center = center, radius = size.width * 0.5f), radius = size.width * 0.5f, center = center)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectorDot(size = 22.dp)
            Text(tr("خودکار", "Auto"), style = Sig.type.title.copy(fontSize = 18.sp), color = c.ink)
        }
    }
}

/**
 * A card in the picker and on the create screen: swatch, fit (when ranked), name. The chosen card
 * carries the ember rim (the action colour: it is what the user decided).
 */
@Composable
fun StyleTile(card: StyleCard?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, fit: Int? = null) {
    val c = Sig.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.aspectRatio(0.86f).clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.action else c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        if (card == null) AutoSwatch(Modifier.fillMaxSize()) else StyleSwatch(card, Modifier.fillMaxSize())
        // A scrim keeps the label readable on any swatch, light or dark.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Text(
            card?.let { tr(it.nameFa, it.nameEn) } ?: tr("کارگردان انتخاب می‌کند", "Director picks"),
            style = Sig.type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight(700)), color = SigNoir.ink, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 9.dp, vertical = 7.dp),
        )
        fit?.let { MicroLabel("$it%", color = if (card?.dark != false) SigNoir.ink.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) }
    }
}

/**
 * The 28 styles as a sheet (phones) or a panel (desktop). The director ranks them for the brief;
 * "خودکار" stays first and is the default, so not choosing is always a good choice.
 */
@Composable
fun StylePicker(
    ranked: List<Pair<StyleCard, Int>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    val c = Sig.colors
    var filter by remember { mutableStateOf(StyleFilter.Suggested) }
    val shown = when (filter) {
        StyleFilter.Suggested -> ranked
        StyleFilter.All -> ranked.sortedBy { (card, _) -> StyleCards.all.indexOf(card) }
        StyleFilter.Energetic -> ranked.filter { it.first.energetic }
        StyleFilter.Dark -> ranked.filter { it.first.dark }
        StyleFilter.Light -> ranked.filter { !it.first.dark }
    }
    val chosen = ranked.firstOrNull { it.first.id == selected }?.first
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(c.lineStrong))
        }
        Row(Modifier.fillMaxWidth().gutter().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(localizedNumber(tr("28 سبک", "28 styles")), style = Sig.type.title.copy(fontSize = 26.sp), color = c.ink)
                Text(tr("کارگردان برای این بریف مرتب کرده", "Ranked by the director for this brief"), style = Sig.type.meta, color = c.muted)
            }
            SigRoundButton(TrimioIcons.Close, tr("بستن", "Close"), onClose, overMedia = false)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 14.dp).padding(horizontal = SigSpace.gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StyleFilter.entries.forEach { f ->
                val on = f == filter
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Tab) { filter = f }) {
                    SigChip(
                        filterName(f),
                        container = if (on) c.ink else c.ink.copy(alpha = 0.06f),
                        content = if (on) c.canvas else c.muted,
                        leading = if (f == StyleFilter.Suggested) ({ DirectorDot(size = 9.dp) }) else null,
                    )
                }
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(columns),
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = SigSpace.gutter, end = SigSpace.gutter, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "auto") { StyleTile(null, selected == null, { onSelect(null) }) }
            items(shown, key = { it.first.id }) { (card, fit) ->
                StyleTile(card, card.id == selected, { onSelect(card.id) }, fit = fit.takeIf { filter == StyleFilter.Suggested })
            }
        }
        SigButton(
            chosen?.let { tr("انتخاب ${it.nameFa}", "Use ${it.nameEn}") } ?: tr("کارگردان انتخاب کند", "Let the director choose"),
            onConfirm,
            Modifier.fillMaxWidth().gutter().padding(vertical = 12.dp),
        )
    }
}

/** Places [StylePicker] as a bottom sheet on phones and a centred panel on wide windows. */
@Composable
fun StylePickerOverlay(wide: Boolean, onDismiss: () -> Unit, content: @Composable (Modifier, Int) -> Unit) {
    val c = Sig.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter,
    ) {
        val shape = if (wide) RoundedCornerShape(SigSpace.radiusXl) else RoundedCornerShape(topStart = SigSpace.radiusXl, topEnd = SigSpace.radiusXl)
        val sheet = Modifier
            .then(if (wide) Modifier.widthIn(max = 760.dp).fillMaxWidth(0.8f).fillMaxHeight(0.88f) else Modifier.fillMaxWidth().fillMaxHeight(0.86f))
            .clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        content(sheet, if (wide) 5 else 3)
    }
}

@Composable
private fun filterName(f: StyleFilter): String = when (f) {
    StyleFilter.Suggested -> tr("پیشنهاد کارگردان", "Director's picks")
    StyleFilter.All -> tr("همه", "All")
    StyleFilter.Energetic -> tr("پرانرژی", "Energetic")
    StyleFilter.Dark -> tr("تیره", "Dark")
    StyleFilter.Light -> tr("روشن", "Light")
}
