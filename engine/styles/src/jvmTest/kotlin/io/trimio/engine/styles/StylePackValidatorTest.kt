package io.trimio.engine.styles

import io.trimio.core.model.style.ColorRoles
import io.trimio.core.model.style.DesignTokens
import io.trimio.core.model.style.FilmSpec
import io.trimio.core.model.style.Palette
import io.trimio.core.model.style.RenderCost
import io.trimio.core.model.style.StyleFamily
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.style.TypeScale
import kotlin.test.Test
import kotlin.test.assertTrue

/** The schema v2 rules: film texture/LUT ids, finishing presets and the design-token sheet. */
class StylePackValidatorTest {

    @Test
    fun aPackWithDefaultTokensAndNoFilmPasses() {
        assertTrue(StylePackValidator.validate(pack()).isEmpty(), "a default sheet must never block a pack")
    }

    @Test
    fun filmTextureLutAndPresetAreHeldToThePacks() {
        val bad = pack().let {
            it.copy(spec = it.spec.copy(film = FilmSpec(texture = "Not An Id", lut = "bad id!", preset = "vintage")))
        }
        val fields = StylePackValidator.validate(bad).map { it.field }
        assertTrue("film.texture" in fields, "a texture id must be kebab-case: $fields")
        assertTrue("film.lut" in fields, "a LUT id must be kebab-case: $fields")
        assertTrue("film.preset" in fields, "only clean, film and dream are finishing presets: $fields")
    }

    @Test
    fun filmTextureOpacityAndGrainStayInBand() {
        val bad = pack().let {
            it.copy(spec = it.spec.copy(film = FilmSpec(texture = "grain-35mm", textureOpacity = 1.5f, grain = 0.5f, halation = 2f)))
        }
        val fields = StylePackValidator.validate(bad).map { it.field }
        assertTrue("film.textureOpacity" in fields, "$fields")
        assertTrue("film.grain" in fields, "$fields")
        assertTrue("film.halation" in fields, "$fields")
    }

    @Test
    fun tokenNumbersOutsideTheBandsAreRejected() {
        val bad = pack().let {
            it.copy(
                spec = it.spec.copy(
                    tokens = DesignTokens(
                        type = TypeScale(ratio = 2.5f, display = 0, title = 1, caption = 2, label = 3),
                        spacing = io.trimio.core.model.style.Spacing(unit = 0.5f),
                        radius = io.trimio.core.model.style.Radius(unit = -0.1f),
                    ),
                ),
            )
        }
        val fields = StylePackValidator.validate(bad).map { it.field }
        assertTrue("tokens.type.ratio" in fields, "$fields")
        assertTrue("tokens.type" in fields, "steps must descend")
        assertTrue("tokens.spacing.unit" in fields, "$fields")
        assertTrue("tokens.radius.unit" in fields, "$fields")
    }

    @Test
    fun semanticColourRolesAndAccentsMustParse() {
        val bad = pack().let {
            it.copy(
                spec = it.spec.copy(
                    tokens = DesignTokens(
                        colors = ColorRoles(primary = "gold", danger = "#12345"),
                        accents = listOf("#GGG"),
                    ),
                ),
            )
        }
        val fields = StylePackValidator.validate(bad).map { it.field }
        assertTrue("tokens.colors.primary" in fields, "$fields")
        assertTrue("tokens.colors.danger" in fields, "$fields")
        assertTrue("tokens.accents[0]" in fields, "$fields")
    }

    private fun pack() = StylePack(
        id = "test-style",
        version = "1.0.0",
        nameFa = "آزمایشی",
        nameEn = "Test",
        family = StyleFamily.Vector2D,
        cost = RenderCost.Light,
        spec = StyleSpec(
            id = "test-style",
            family = StyleFamily.Vector2D,
            palette = Palette(
                background = listOf("#0B0A09"),
                text = "#F2EDE4",
                accent = "#D7FF3A",
                accent2 = "#FF5B2E",
            ),
        ),
    )
}
