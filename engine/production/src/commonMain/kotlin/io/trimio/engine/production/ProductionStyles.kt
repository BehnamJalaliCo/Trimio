package io.trimio.engine.production

import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage

/**
 * The style override's choices: the motion engine's 28 design languages. The picker shows
 * [StyleLanguage.nameFa]/[StyleLanguage.nameEn]; null (auto) lets the director choose by the brief,
 * the domain, the mood and the creator's taste. A chosen style reaches the planner by name in the
 * brief ([ProductionRequest.brief]), the same path a creator writing "noir style" takes.
 */
object ProductionStyles {
    val all: List<StyleLanguage> get() = StyleBook.all
    val ids: List<String> get() = all.map { it.id }

    fun of(id: String?): StyleLanguage? = StyleBook.of(id)
}
