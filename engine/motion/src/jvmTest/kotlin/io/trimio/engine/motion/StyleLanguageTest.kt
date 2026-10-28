package io.trimio.engine.motion

import io.trimio.core.model.style.DesignStyle
import io.trimio.engine.assets.SoundPresets
import io.trimio.engine.motion.recipe.FilmTextures
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.style.StyleBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The 28 design languages: complete, distinct, and every one inside the craft floors. */
class StyleLanguageTest {
    private val all = StyleBook.all

    @Test
    fun twentyEightStylesOnePerPickerEntry() {
        assertEquals(28, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertEquals(DesignStyle.entries.map { it.id }.toSet(), all.map { it.pack }.toSet(), "each picker style asks for exactly one language")
        assertEquals(listOf("noir", "paper", "lumen"), all.take(3).map { it.id }, "the original looks keep their ids")
        for (s in all) {
            assertEquals(s.look, Look.named(s.id), "${s.id}: the compiler finds its look")
            assertTrue(SoundPresets.forTag(s.sound)?.tag == s.sound, "${s.id}: sound «${s.sound}» is an audio preset tag")
            s.background?.let { assertTrue(ShaderFamily.named(it)?.filter == false, "${s.id}: background «$it» is a stage shader") }
            s.lens?.let { assertTrue(ShaderFamily.named(it)?.filter == true, "${s.id}: lens «$it» is a distortion") }
            s.motion.entrances.keys.forEach { assertTrue(Recipes.named(it)?.name == it, "${s.id}: entrance $it is a recipe") }
        }
    }

    @Test
    fun everyStyleKeepsTheContrastFloorAndReadableType() {
        val problems = all.flatMap(StyleBook::check)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun everyStyleWearsMeaningfulFilmMaterial() {
        // A texture from the overlay pack, a LUT from the look pack and a finishing film look: every
        // one of the 28 styles wears its own material, not just the original three.
        for (s in all) {
            val tex = assertNotNull(s.look.texture, "${s.id}: no film texture")
            assertTrue(tex.id in FilmTextures.overlays, "${s.id}: texture «${tex.id}» is not in overlay.film-textures")
            assertTrue(tex.opacity > 0f && tex.opacity <= 1f, "${s.id}: texture opacity ${tex.opacity} is not worn")
            val lut = assertNotNull(s.look.lut, "${s.id}: no colour LUT")
            assertTrue(lut in FilmTextures.luts, "${s.id}: lut «$lut» is not in look.cinematic-luts")
            assertNotNull(s.look.film, "${s.id}: no finishing film look")
        }
    }

    @Test
    fun stylesAreDistinct() {
        // Two styles differ in palette (canvas or accent) and in at least two of: headline or caption voice, emphasis mark, corners,
        // easing, stage shader, caption length, favourite entrance.
        for ((i, a) in all.withIndex()) for (b in all.drop(i + 1)) {
            val palette = a.look.canvas != b.look.canvas || a.look.accent != b.look.accent
            val traits = listOf(
                a.look.headline != b.look.headline, a.look.caption != b.look.caption, a.look.roundness != b.look.roundness, a.look.mark != b.look.mark,
                a.motion.ease != b.motion.ease, a.background != b.background,
                a.captions.words != b.captions.words, a.motion.entrances.maxByOrNull { it.value }?.key != b.motion.entrances.maxByOrNull { it.value }?.key,
            ).count { it }
            assertTrue(palette && traits >= 2, "${a.id} and ${b.id} are too alike (palette $palette, $traits traits)")
        }
    }

    @Test
    fun textOnAnAccentIsWhicheverReadsBetter() {
        // A deep red takes bone text, a bright one near-black; both clear 4.5:1.
        for (hex in listOf("#D0021B", "#FF3B00", "#1F5BFF", "#C6FF00", "#7A5A2E")) {
            val v = Look.Noir.withAccent(hex)
            val r = Contrast.ratio(Contrast.luminance(v.onAccent), Contrast.luminance(v.accent))
            assertTrue(r >= 4.5f, "$hex: $r")
        }
    }

    @Test
    fun briefsReachStylesByWordAndName() {
        assertEquals(listOf("retro-vhs"), StyleBook.asked("یه ریلز رترو با حس نوستالژی").map { it.id })
        assertEquals(listOf("neon-cyber"), StyleBook.asked("Neon cyberpunk vibes please").map { it.id })
        assertEquals(listOf("calm-wellness"), StyleBook.asked("style: biophilic-design").map { it.id }, "the picker's id")
        assertEquals(setOf("beauty-soft", "corporate-clean"), StyleBook.asked("سبک روشن و تمیز").map { it.id }.toSet(), "a shared word keeps every style that has it")
        assertTrue(StyleBook.asked("یه ریلز جذاب بساز").isEmpty(), "no style word, no style asked")
        assertTrue(StyleBook.asked("پاپیون قرمز").isEmpty(), "a word that only starts like a style word is not one")
    }
}
