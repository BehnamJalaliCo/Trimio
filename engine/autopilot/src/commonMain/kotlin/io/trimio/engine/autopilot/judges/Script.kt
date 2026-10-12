package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.BriefNames
import io.trimio.engine.autopilot.Glossary
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.NumberWords

/**
 * The script a Persian piece is written in. Every word a graphic draws — its title, label, items
 * and whatever the device writes on its own (a node of a map, a line of a terminal, a button) — is
 * Persian, unless it is a name given in Latin letters: one the brief or its glossary writes
 * (Nobitex, nobitex.ir, RSI), a brand the library has a mark for, or a word the speaker said in
 * Latin («Claude Code»). "src", "api", "install", "done" on a Persian video are English text.
 * Read from the drawn layers themselves ([Compiler.Output.layers]), not only from the score.
 */
internal class Script(private val c: Jury.Context) {
    private val rtl = c.words.count { Bench.persian(it.text) } * 2 > c.words.size

    private val allowed: Set<String> by lazy {
        val brief = c.prompt.split(SPLIT).map(::core).filter(::latin)
        val glossary = Glossary.read(c.prompt).let { g -> g.sites + g.terms.keys + g.handles.map { it.removePrefix("@") } }
        val said = (c.prelude + c.words).map { core(it.text) }.filter(::latin)
        val coins = (c.prelude + c.words).mapNotNull { Quantities.unitOf(NumberWords.normalize(it.text))?.brand }
        val named = c.understanding.entities.map { it.name }.filter { n -> said.any { s -> n.contains(s, ignoreCase = true) } }
        (brief + glossary + said + coins + named).flatMap { listOf(it) + it.split(' ', '.') }.map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Latin words drawn by the graphics of a Persian piece that nobody gave in Latin, with the beat drawing them. */
    fun foreign(): List<Pair<Compiler.Placed, String>> {
        if (!rtl) return emptyList()
        val beats = c.compiled.beats
        // Code is code: a terminal or an editor types its commands as they are (whether it belongs there is relevance's call).
        return beats.indices.filter { beats[it].recipe != Bench.CAPTIONS && beats[it].recipe !in CODE }.flatMap { i ->
            texts(c.compiled.layers.getOrNull(i).orEmpty()).flatMap { s -> s.split(' ', '\n') }.map(::core).filter { !ok(it) }.distinct().map { beats[i] to it }
        }
    }

    /** Whether Latin word [w] may be on screen (anything not Latin always may). */
    fun ok(w: String): Boolean {
        // A lone letter is a monogram (a brand's initial on its tile), not a word.
        if (!latin(w) || w.count { it.isLetter() } <= 1) return true
        val low = w.lowercase()
        if (low in allowed || c.brand(w) || TIMES.matches(low)) return true
        // A site or a dotted name made of allowed parts (nobitex.ir).
        return BriefNames.DOMAIN.matches(w) && low.split('.').all { it in allowed || it in TLDS }
    }

    /** [b] with its English words taken out, in a plain Persian form, or null when nothing Persian is left of it. */
    fun persian(b: BeatScore): BeatScore? {
        fun clean(s: String?) = s?.split(' ')?.filter { ok(core(it)) }?.joinToString(" ")?.trim()?.ifBlank { null }
        val cleaned = b.copy(text = clean(b.text), label = clean(b.label), items = b.items.filter { ok(core(it.removePrefix(">"))) })
        if (cleaned != b && (cleaned.text != null || cleaned.items.isNotEmpty() || cleaned.value != null)) return cleaned
        return Bench.Ladder.plain(cleaned)?.takeIf { p -> listOfNotNull(p.text, p.label).all { t -> t.split(' ').all { ok(core(it)) } } }
    }

    private fun texts(nodes: List<Node>): List<String> = nodes.flatMap { n ->
        when (n) {
            is Group -> texts(n.children)
            is TextNode -> listOf(n.text)
            is CounterNode -> listOf(n.prefix, n.suffix)
            else -> emptyList()
        }
    }

    private companion object {
        val SPLIT = Regex("[\\s,،!?؟:;()\\[\\]{}«»\"“”/]+")
        /** «۲x», "10x", "x2": a multiplier, not a word. */
        val TIMES = Regex("^\\d*x\\d*$")
        val CODE = setOf("terminal", "ui-code")
        val TLDS = setOf("com", "ir", "io", "net", "org", "co", "app", "ai", "dev", "me", "info")

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != '.' }.trim('.')
        fun latin(w: String) = w.any { it in 'A'..'Z' || it in 'a'..'z' }
    }
}
