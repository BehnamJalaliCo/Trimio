package io.trimio.engine.director

import io.trimio.core.model.asset.IconCatalog
import io.trimio.engine.assets.SemanticIndex

/** Maps free text ("rocket", "موشکی", "به ماه رفت") to the closest icon the renderer can draw. */
object IconMatcher {
    private val exact: Map<String, String> = IconCatalog.all
        .flatMap { icon -> (icon.keywords + icon.id + icon.nameEn + icon.nameFa).map { Lexicon.norm(it) to icon.id } }
        .toMap()

    private val index = SemanticIndex(IconCatalog.all.map { it.id to (it.keywords + it.id + it.nameEn + it.nameFa) })

    /** Icon for a single transcript word only when it is one of the icon's own keywords. */
    fun forWord(word: String): String? = exact[Lexicon.norm(word)]

    /** Best icon for free text, or null when nothing is reasonably close. */
    fun match(text: String): String? =
        text.split(' ').firstNotNullOfOrNull { forWord(it) } ?: index.best(text, minScore = 0.3f)?.first
}
