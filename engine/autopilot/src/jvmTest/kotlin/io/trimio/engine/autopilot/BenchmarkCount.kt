package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.motion.score.CutPoints

/**
 * What the real benchmark is accepted on, counted on a delivered production the way a viewer meets
 * it: the judges' lowest and mean score; every Persian word on screen (graphics, captions, the cold
 * open's, the end card) that is not a word, a term or a name ([ScreenWords]); every title that
 * does not read on its own ([Fragments]); every cut (the cold open's included) whose fade sounds
 * inside a word ([JumpCuts.inWord], on the levelled voice); and the list of graphic texts.
 */
internal object BenchmarkCount {

    fun lines(p: Autopilot.Production, voice: PcmAudio, lexicon: PersianSpelling): List<String> {
        val heard = p.bench?.transcript?.words.orEmpty().map { it.text }
        val given = p.understanding.entities.map { it.name } + p.bench?.prompt.orEmpty().split(' ')
        val strict = ScreenWords(lexicon, given + ScreenWords.namesIn(heard, repeated = false) { ScreenWords(lexicon).shows(it) }, strict = true)
        val screen = ScreenWords(lexicon, given + ScreenWords.namesIn(heard) { ScreenWords(lexicon).shows(it) })
        val beats = p.compiled.beats
        val graphics = beats.filter { it.recipe != "pop-captions" }
        val texts = graphics.map { it.text } + p.score.scenes.flatMap { s -> s.beats.flatMap { listOfNotNull(it.label) + it.items.map { i -> i.removePrefix(">") } } }
        val captions = beats.filter { it.recipe == "pop-captions" }.map { it.text }
        val unknown = texts.flatMap { strict.unknown(it) } + captions.flatMap { screen.unknown(it) }
        val fragments = graphics.filter { it.recipe in Fragments.TITLES }.mapNotNull { g -> Fragments.fault(g.text)?.let { "«${g.text}» ($it)" } }
        val cuts = CutPoints(VoiceLevel.levelled(voice))
        val played = p.edit.played
        val inside = played.indices.flatMap { i ->
            listOfNotNull(
                played[i].sourceStart.takeIf { i > 0 && JumpCuts.inWord(cuts, it, entering = true) },
                played[i].sourceEnd.takeIf { i < played.lastIndex && JumpCuts.inWord(cuts, it, entering = false) },
            )
        }
        val panel = p.panel
        return listOf(
            "benchmark: judges min ${panel?.min?.let(Jury::fmt) ?: "-"} mean ${panel?.mean?.let(Jury::fmt) ?: "-"} | " +
                "unknown on-screen words ${unknown.size} | fragments ${fragments.size} | cuts inside words ${inside.size} of ${played.size - 1} cuts | " +
                "${graphics.size} graphics, ${graphics.groupingBy { family(it.recipe) }.eachCount().size} families",
            "benchmark unknown words: ${unknown.joinToString(" ") { "«$it»" }}",
            "benchmark fragments: ${fragments.joinToString(" ")}",
            "benchmark cuts inside words (source s): ${inside.joinToString(" ") { "%.2f".format(it) }}",
            "benchmark graphic texts: " + graphics.joinToString(" | ") { "${it.recipe}: ${it.text.ifBlank { "(figure/card)" }}" },
        )
    }

    private fun family(recipe: String) = if (recipe in setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")) "titles" else recipe
}
