package io.trimio.feature.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigHeroTitle
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.production.StyleSamples
import io.trimio.feature.create.StyleCards
import io.trimio.feature.create.StyleSwatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The gallery's choices: the filter, and the cards the user paused. */
data class GalleryUi(val filter: GalleryFilter = GalleryFilter.All, val paused: Set<String> = emptySet())

/** What the gallery can do. */
data class GalleryActions(
    val onBack: () -> Unit = {},
    val onFilter: (GalleryFilter) -> Unit = {},
    val onTogglePlay: (String) -> Unit = {},
    /** Make something in this style: pick the media, then the create screen. */
    val onUse: (String) -> Unit = {},
)

/** What draws the live samples: the motion engine's fonts, the compiled samples and stand-in footage. */
class SampleEngine(val fonts: MotionFonts, val shelf: SampleShelf, val media: MediaSource)

class GalleryViewModel : ViewModel() {
    private val _ui = MutableStateFlow(GalleryUi())
    val ui: StateFlow<GalleryUi> = _ui.asStateFlow()

    fun filter(f: GalleryFilter) = _ui.update { it.copy(filter = f) }
    fun togglePlay(id: String) = _ui.update { s -> s.copy(paused = if (id in s.paused) s.paused - id else s.paused + id) }
}

@Composable
fun StyleGalleryRoute(viewModel: GalleryViewModel, onBack: () -> Unit, onUse: (String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    StyleGalleryScreen(ui, GalleryActions(onBack, viewModel::filter, viewModel::togglePlay, onUse), rememberSampleEngine())
}

/** Loads the motion engine's fonts and marks once, then compiles samples as cards ask for them. */
@Composable
fun rememberSampleEngine(): SampleEngine? {
    val resolver = LocalFontFamilyResolver.current
    val engine by produceState<SampleEngine?>(null) {
        value = withContext(Dispatchers.Default) {
            val fonts = MotionFonts.load()
            val samples = StyleSamples(TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr), fonts, BrandLibrary.load())
            SampleEngine(fonts, SampleShelf { id -> samples.sample(id, sampleWord(id)) }, standInFootage())
        }
    }
    return engine
}

/**
 * Board 4, second screen: «۲۸ سبک.», the filters, and every style as a live card, playing the
 * sample the director makes in it (the same planner, compiler and renderer as a real edit). A card
 * starts something new in its style; its glass button pauses it. Wide windows show more columns.
 */
@Composable
fun StyleGalleryScreen(ui: GalleryUi, actions: GalleryActions, engine: SampleEngine?) {
    val styles = remember(ui.filter) { GalleryStyles.filter(ui.filter) }
    val count = StyleBook.all.size
    SigScreen {
        SigAdaptive { width ->
            val gutter = if (width.wide) 32.dp else 22.dp
            LazyVerticalGrid(
                columns = if (width.wide) GridCells.Adaptive(190.dp) else GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(if (width.wide) 14.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (width.wide) 14.dp else 10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "head") { Head(count, ui.filter, actions) }
                items(styles, key = { it.id }) { style ->
                    val card = if (width.wide) Modifier.fillMaxWidth().aspectRatio(CARD_RATIO) else Modifier.fillMaxWidth().height(244.dp)
                    // Reduced motion holds every card still; the play glass starts one.
                    val playing = if (Trimio.preferences.reduceMotion) style.id in ui.paused else style.id !in ui.paused
                    LiveCard(style, engine, playing, actions, card)
                }
            }
        }
    }
}

@Composable
private fun Head(count: Int, filter: GalleryFilter, actions: GalleryActions) {
    val c = Sig.colors
    Column {
        // The boards' top row, inside the grid's own gutter.
        Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SigRoundButton(TrimioIcons.Back, tr("بازگشت", "Back"), actions.onBack, overMedia = false)
            MicroLabel("GALLERY · $count")
        }
        SigHeroTitle(localizedNumber(tr("$count سبک", "$count styles")), Modifier.padding(top = 12.dp), size = 44)
        Text(
            tr("هر کارت زنده پخش می‌شود؛ با صدای نمونه یا ویدیوی خودت.", "Every card plays live; with the sample voice or your own video."),
            Modifier.padding(top = 6.dp), style = Sig.type.meta, color = c.muted,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryFilter.entries.forEach { f ->
                val on = f == filter
                Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Tab) { actions.onFilter(f) }) {
                    SigChip(tr(f.fa, f.en), container = if (on) c.ink else c.ink.copy(alpha = 0.08f), content = if (on) c.canvas else c.ink)
                }
            }
        }
    }
}

/**
 * One style: its live sample (its still until the sample is compiled), its name and a few words
 * over a scrim on dark styles, and the play/pause glass at the reading end.
 */
@Composable
private fun LiveCard(style: GalleryStyle, engine: SampleEngine?, playing: Boolean, actions: GalleryActions, modifier: Modifier) {
    val sample by produceState(engine?.shelf?.cached(style.id), engine, style.id) {
        if (value == null && engine != null) value = withContext(Dispatchers.Default) { engine.shelf.of(style.id) }
    }
    val ready = sample != null && engine != null
    val fade by animateFloatAsState(if (ready) 1f else 0f, Trimio.motion.effects(), label = "sample")
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.clip(shape).background(style.canvas).clickable(role = Role.Button) { actions.onUse(style.id) }) {
        if (fade < 1f) StyleSwatch(remember(style.id) { StyleCards.card(StyleBook.all.first { it.id == style.id }) }, Modifier.fillMaxSize())
        val s = sample
        if (s != null && engine != null) {
            LiveSample(s, engine.fonts, engine.media, playing, Modifier.fillMaxSize().graphicsLayer { alpha = fade }, still = StyleSamples.still(s))
        }
        Caption(style, Modifier.align(Alignment.BottomStart))
        // Paused (reduced motion, or stopped): the play glass at the reading end starts it.
        if (!playing) {
            SigGlass(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(28.dp).clickable(role = Role.Button) { actions.onTogglePlay(style.id) },
                shape = CircleShape, overMedia = true,
            ) { TrimioIcon(TrimioIcons.Play, tr("پخش", "Play"), tint = SigNoir.ink, size = 13.dp, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun Caption(style: GalleryStyle, modifier: Modifier) {
    val name = if (style.dark) SigNoir.ink else Color(0xFF111111)
    val hint = if (style.dark) SigNoir.ink.copy(alpha = 0.65f) else Color(0xFF6E6B64)
    Column(
        modifier.fillMaxWidth()
            .background(if (style.dark) Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
            .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
    ) {
        Text(tr(style.nameFa, style.nameEn), style = Sig.type.bodyStrong.copy(fontSize = 13.sp, fontWeight = FontWeight(800)), color = name, maxLines = 1)
        if (style.hintFa.isNotEmpty()) Text(tr(style.hintFa, style.hintEn), style = Sig.type.meta.copy(fontSize = 10.5.sp), color = hint, maxLines = 1)
    }
}

/** The board's card: 168 × 244. */
private const val CARD_RATIO = 0.69f
