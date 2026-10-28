package io.trimio.engine.motion.style

import androidx.compose.ui.graphics.Color
import io.trimio.core.model.style.ColorRoles
import io.trimio.core.model.style.DesignTokens
import io.trimio.core.model.style.FilmSpec
import io.trimio.core.model.style.Palette
import io.trimio.core.model.style.StyleFamily
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.style.TypeScale
import io.trimio.engine.motion.Finishing
import io.trimio.engine.motion.recipe.FilmTextures
import io.trimio.engine.motion.recipe.Look
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The spec → language bridge: film textures and LUTs consumed by the look, tokens by the language. */
    class StyleBridgeTest {

    @Test
    fun aFilmTextureIsWornByTheLookWithThePacksOwnBlend() {
        val look = StyleBridge.lookOf(spec(film = FilmSpec(texture = "grain-16mm", textureOpacity = 0.4f, lut = "teal-orange")))
        assertEquals("grain-16mm", look.texture?.id, "the look wears the pack's grain texture")
        assertEquals("overlay", look.texture?.blend, "grain lays over, as the pack draws it")
        assertEquals("teal-orange", look.lut, "the LUT grades the footage")
        assertTrue(abs((look.texture?.opacity ?: 0f) - 0.4f) < EPS, "opacity ${look.texture?.opacity} does not follow the spec")
    }

    @Test
    fun aGrainTextureRaisesTheFilmLooksGrain() {
        val base = Finishing.filmPresets.getValue("film").grainAmount
        val bare = StyleBridge.lookOf(spec(film = FilmSpec())).film?.grainAmount ?: 0f
        val worn = StyleBridge.lookOf(spec(film = FilmSpec(texture = "grain-16mm"))).film?.grainAmount ?: 0f
        assertTrue(abs(bare - base) < EPS, "without a texture the preset's own grain runs: $bare != $base")
        assertTrue(worn >= base + FilmTextures.grainOf.getValue("grain-16mm") - EPS, "grain-16mm folds its grain in: $worn")
        assertTrue(worn > bare, "a grain texture reads heavier than the bare preset")
    }

    @Test
    fun grainOverridesWinOverTheTextureAndPreset() {
        val look = StyleBridge.lookOf(spec(film = FilmSpec(texture = "grain-16mm", preset = "film", grain = 0.11f, halation = 0.6f)))
        assertTrue(abs((look.film?.grainAmount ?: 0f) - 0.11f) < EPS, "the style's own grain wins")
        assertTrue(abs((look.film?.halationStrength ?: 0f) - 0.6f) < EPS, "the style's own halation wins")
    }

    @Test
    fun theThreeSignatureLooksWearRealTexturesFromThePacks() {
        for (look in listOf(Look.Noir, Look.Paper, Look.Lumen)) {
            val texture = look.texture ?: error("${look.name} wears no film texture")
            assertTrue(texture.id in FilmTextures.overlays, "${look.name}: ${texture.id} is not in overlay.film-textures")
            assertEquals(FilmTextures.blendOf(texture.id), texture.blend, "${look.name}: blend matches the pack")
            val lut = look.lut ?: error("${look.name} wears no LUT")
            assertTrue(lut in FilmTextures.luts, "${look.name}: $lut is not in look.cinematic-luts")
            assertTrue(look.film != null, "${look.name}: its finishing film look is resolved")
        }
    }

    @Test
    fun theTokenSheetReachesTheLanguageAndScalesTheType() {
        val tokens = DesignTokens(
            type = TypeScale(ratio = 1.2f),
            accents = listOf("#FF6B3D", "#3AE0FF"),
            colors = ColorRoles(primary = "#D7FF3A", muted = "#8F887D"),
        )
        val language = StyleBridge.languageOf(spec(tokens = tokens), StyleBook.of("noir")!!)
        assertEquals(tokens, language.tokens, "the sheet reaches the language")
        assertEquals(listOf("#D7FF3A", "#FF6B3D", "#3AE0FF"), language.accents, "multi-accent leads with the primary role")
        assertTrue(abs(language.tokens.type.titleSize / language.tokens.type.captionSize - 1.2f) < 1e-3f, "a title sits one ratio step above its captions")
        assertTrue(language.tokens.type.displaySize > language.tokens.type.titleSize, "display leads the scale")
        assertEquals(Color(0xFF8F887D), language.look.muted, "the muted role lands on the look")
        assertEquals(Color(0xFFD7FF3A), language.look.accent, "the primary role is the accent")
    }

    @Test
    fun whatStaysDualIsNamedRatherThanPretendedAway() {
        val gaps = StyleBridge.gaps()
        assertTrue(gaps.any { "motion" in it }, "the motion grammar is named as dual")
        assertTrue(gaps.any { "sound" in it }, "the sound tag is named as dual")
        assertTrue(gaps.size >= 5, "the dual list is honest, not a one-liner")
    }

    @Test
    fun unknownPackIdsAreFaultsNotSilentDefaults() {
        val faults = StyleBridge.faults(spec(film = FilmSpec(texture = "no-such-texture", lut = "no-such-lut", preset = "vintage")))
        assertEquals(3, faults.size, faults.joinToString("\n"))
        assertTrue(faults.any { "film.texture" in it }, "an unknown texture is reported")
        assertTrue(faults.any { "film.lut" in it }, "an unknown LUT is reported")
        assertTrue(faults.any { "film.preset" in it }, "an unknown preset is reported")
    }

    @Test
    fun everyStyleStillClearsTheCraftFloors() {
        val problems = Styles.all.flatMap(StyleBook::check)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun spec(
        film: FilmSpec = FilmSpec(),
        tokens: DesignTokens = DesignTokens(),
    ) = StyleSpec(
        id = "test-style",
        family = StyleFamily.Vector2D,
        palette = Palette(
            background = listOf("#0B0A09", "#2E140A"),
            text = "#F2EDE4",
            accent = "#D7FF3A",
            accent2 = "#FF5B2E",
        ),
        film = film,
        tokens = tokens,
    )

    private companion object {
        const val EPS = 1e-4f
    }
}
