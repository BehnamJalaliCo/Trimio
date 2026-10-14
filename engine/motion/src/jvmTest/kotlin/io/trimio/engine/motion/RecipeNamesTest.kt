package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.SubtitleStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A score names recipes: two with one name would make one unreachable (the YouTube card once hid the reels' «chapter»). */
class RecipeNamesTest {
    @Test
    fun everyRecipeNameIsUnique() {
        val twice = Recipes.all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue(twice.isEmpty(), "names used twice: $twice")
    }

    @Test
    fun everySubtitleStyleHasItsRecipe() {
        for (style in SubtitleStyle.entries) assertEquals(style.recipe, Recipes.named(style.recipe)?.name, style.id)
    }
}
