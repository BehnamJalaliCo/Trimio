package io.trimio.feature.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.trimio.core.designsystem.component.GlassChip
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.component.TrimioTopBar
import io.trimio.core.designsystem.component.pressable
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.style.RenderCost
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.render.PreviewClock
import io.trimio.engine.render.SampleTimelines
import io.trimio.engine.render.TimelinePreview
import io.trimio.engine.styles.StylePack
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(private val styles: StylePackRepository) : ViewModel() {
    private val _packs = MutableStateFlow<List<StylePack>>(emptyList())
    val packs: StateFlow<List<StylePack>> = _packs

    init {
        viewModelScope.launch { _packs.value = styles.all() }
    }
}

@Composable
fun StyleGalleryRoute(viewModel: GalleryViewModel, onBack: () -> Unit, onUse: (String) -> Unit) {
    val packs by viewModel.packs.collectAsStateWithLifecycle()
    StyleGalleryScreen(packs, SampleTimelines.cryptoSignal(), onBack, onUse)
}

/**
 * Every style, playing live with the real renderer on a sample edit. Selecting one lifts it and
 * reveals what it is for; the rest of the 28 arrive as packs without an app update.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StyleGalleryScreen(packs: List<StylePack>, sample: Timeline, onBack: () -> Unit, onUse: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val upcoming = DesignStyle.entries.count { s -> packs.none { it.id == s.id } }
    TrimioScreen(dimAurora = 0.55f) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (maxWidth > 700.dp) 4 else 2
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(horizontal = TrimioSpacing.screenGutter, vertical = TrimioSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.md),
                verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TrimioTopBar(tr("گالری سبک\u200Cها", "Style gallery"), subtitle = tr("هر سبک زنده پخش می\u200Cشود", "Every style plays live"), onBack = onBack, backLabel = tr("بازگشت", "Back"))
                }
                items(packs, key = { it.id }) { pack ->
                    StyleCard(pack, sample, selected == pack.id, onClick = { selected = if (selected == pack.id) null else pack.id }, onUse = { onUse(pack.id) })
                }
                if (upcoming > 0) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(vertical = TrimioSpacing.lg), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                        Text(tr("به\u200Cزودی", "Coming as packs"), style = Trimio.type.title, color = Trimio.colors.textPrimary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(TrimioSpacing.sm), verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
                            DesignStyle.entries.filter { s -> packs.none { it.id == s.id } }.forEach { GlassChip(tr(it.nameFa, it.nameEn)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleCard(pack: StylePack, sample: Timeline, selected: Boolean, onClick: () -> Unit, onUse: () -> Unit) {
    val colors = Trimio.colors
    val lift by animateFloatAsState(if (selected) 1.03f else 1f, Trimio.motion.spatial(), label = "lift")
    val shape = RoundedCornerShape(TrimioRadius.lg)
    Column(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }, verticalArrangement = Arrangement.spacedBy(TrimioSpacing.xs)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f).pressable(onClick = onClick).clip(shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accentCyan else colors.glassStroke, shape),
        ) {
            val clock = remember(pack.id) { PreviewClock(1_800) }
            TimelinePreview(sample.copy(styleId = pack.id), pack.spec, Modifier.fillMaxSize(), clock)
        }
        Text(tr(pack.nameFa, pack.nameEn), style = Trimio.type.title, color = colors.textPrimary)
        Text(
            tr(pack.family.nameFa, pack.family.nameEn) + " · " + when (pack.cost) {
                RenderCost.Light -> tr("سبک", "Light")
                RenderCost.Medium -> tr("متوسط", "Medium")
                RenderCost.Heavy -> tr("سنگین", "Heavy")
            },
            style = Trimio.type.caption,
            color = colors.textSecondary,
        )
        if (selected) {
            Text(tr(pack.descriptionFa, pack.descriptionEn), style = Trimio.type.label, color = colors.textSecondary)
            TrimioButton(tr("ساخت با این سبک", "Use this style"), onUse, Modifier.fillMaxWidth())
        }
    }
}
