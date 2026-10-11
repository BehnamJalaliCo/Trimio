package io.trimio.feature.editor

import io.trimio.engine.production.CaptionDensity
import io.trimio.engine.production.EditFacts
import io.trimio.engine.production.FactElement

/** The editor's five tools, in the rail's order. */
enum class EditorTool { Text, Style, Sound, Elements, Cut }

/**
 * Everything the editor draws, as plain data: built from the edit ([EditorUi.of]) and the
 * session (tool, selection, playhead), so the screen is a pure function of it and renders the
 * same in tests, on phones and on the desktop.
 */
data class EditorUi(
    val tool: EditorTool = EditorTool.Text,
    /** Seconds on the edit's clock. */
    val position: Float = 0f,
    val duration: Float = 0f,
    val playing: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** The picture is being made from the latest change. */
    val working: Boolean = false,
    /** The sound is being mixed from the latest change (the picture is already shown). */
    val mixing: Boolean = false,
    /** The tool panel is folded away (the whole frame shows). */
    val folded: Boolean = false,
    val text: TextTool = TextTool(),
    val style: StyleTool = StyleTool(),
    val sound: SoundTool = SoundTool(),
    val elements: ElementsTool = ElementsTool(),
    val cut: CutTool = CutTool(),
    /** A line from the gates about the creator's own changes (spelling kept as written). */
    val warning: String? = null,
) {
    companion object {
        const val BARS = 66

        /** The editor's view of [facts] at [position], with [selection] (word indices) in the text tool. */
        fun of(facts: EditFacts, position: Float, selection: IntRange?, comparing: Boolean = false, suggestionsShown: Int = SUGGESTIONS): EditorUi {
            val duration = facts.duration
            return EditorUi(
                position = position.coerceIn(0f, duration), duration = duration,
                text = text(facts, position, selection),
                style = StyleTool(
                    styles = facts.styles, selected = facts.style, director = facts.directorStyle,
                    energy = (facts.energy * PERCENT).toInt().coerceIn(0, PERCENT), density = facts.captions, comparing = comparing,
                ),
                sound = SoundTool(
                    moods = facts.moods.map { MoodRow(it.id, it.nameFa, it.nameEn, it.bpm) }, selected = facts.music, director = facts.directorMusic,
                    musicDb = facts.musicDb, effects = facts.effects, effectCount = if (facts.effects) facts.effectCount else facts.accentEffects,
                    lufs = facts.lufs.toFloat(), peaks = facts.mixPeaks(BARS).toList(), cues = facts.cues.map { it / duration.coerceAtLeast(MIN_DURATION) },
                ),
                elements = elements(facts, suggestionsShown),
                cut = cut(facts),
                warning = facts.warnings.firstOrNull(),
            )
        }

        private fun text(facts: EditFacts, position: Float, selection: IntRange?): TextTool {
            val words = facts.words
            if (words.isEmpty()) return TextTool()
            val anchor = selection?.first ?: facts.wordAt(position) ?: 0
            val line = facts.lines.firstOrNull { anchor in it } ?: facts.lines.getOrNull(facts.lineAt(position)) ?: words.indices
            val shown = line.map { words[it] }.filter { it.text.isNotEmpty() || it.cut }
            val span = selection ?: anchor..anchor
            val chips = chips(shown.map { w -> WordChip(w.index, w.text.ifEmpty { w.said }, w.cut, w.emphasised, w.edited, w.hit) }, selection)
            val picked = span.mapNotNull { words.getOrNull(it) }
            val from = words[line.first].start
            val to = maxOf(words[line.last].end, from + MIN_WINDOW)
            return TextTool(
                chips = chips, selection = selection,
                quote = picked.filter { it.text.isNotEmpty() }.joinToString(" ") { it.text },
                spanStart = picked.firstOrNull()?.start ?: from, spanEnd = picked.lastOrNull()?.end ?: from,
                windowStart = from, windowEnd = to,
                segments = shown.filter { !it.cut }.map { Segment(it.start, it.end, it.index in span) },
                peaks = facts.mixPeaks(BARS, from, to).toList(),
                emphasised = picked.isNotEmpty() && picked.all { it.emphasised },
                hit = picked.any { it.hit },
                cut = picked.isNotEmpty() && picked.all { it.cut },
                hasNumber = picked.any { w -> w.said.any { it.isDigit() } || NUMBER_WORDS.any { n -> w.said.startsWith(n) } },
            )
        }

        /** The picked words read as one chip, and so does a run of cut words («و خب»). */
        internal fun chips(words: List<WordChip>, selection: IntRange?): List<WordChip> {
            val out = mutableListOf<WordChip>()
            for (w in words) {
                val prev = out.lastOrNull()
                val picked = selection?.let { w.index in it } == true
                val together = prev != null && prev.last + 1 == w.index && (
                    (picked && selection?.let { prev.index in it } == true) || (w.cut && prev.cut)
                    )
                out += if (together && prev != null) {
                    out.removeAt(out.lastIndex)
                    prev.copy(text = prev.text + " " + w.text, last = w.index, emphasised = prev.emphasised && w.emphasised, edited = prev.edited || w.edited, hit = prev.hit || w.hit)
                } else {
                    w
                }
            }
            return out
        }

        private fun elements(facts: EditFacts, shown: Int): ElementsTool {
            val all = facts.elements
            val suggested = all.filter { it.origin == FactElement.Origin.Suggested }
            val rows = all.filter { it.origin != FactElement.Origin.Suggested } + suggested.filter { it.on } + suggested.filter { !it.on }.take(shown)
            return ElementsTool(
                rows = rows.map { e -> ElementRow(e.key, e.kind, e.title, e.at, e.word, e.on, e.origin == FactElement.Origin.Suggested) },
                suggestions = suggested.count { !it.on },
                more = suggested.count { !it.on } > shown,
            )
        }

        private fun cut(facts: EditFacts): CutTool {
            val source = facts.sourceDuration.coerceAtLeast(MIN_DURATION)
            val (pauses, seconds) = facts.pauses
            val said = facts.fillers.mapNotNull { facts.words.getOrNull(it)?.said?.trim('.', '،', ',') }
            return CutTool(
                sourceDuration = facts.sourceDuration, duration = facts.duration,
                peaks = facts.voicePeaks(BARS).toList(),
                removed = facts.unplayed.map { (it.start / source)..(it.endInclusive / source) },
                silences = facts.silences, pauses = pauses, pauseSeconds = seconds,
                fillers = facts.fillersCut, fillerCount = said.size, fillerWords = said.distinct().take(FILLER_EXAMPLES),
                beatCuts = facts.beatCuts, beatCutsAvailable = facts.beatCutsAvailable,
            )
        }

        private const val PERCENT = 100
        private const val SUGGESTIONS = 2
        private const val FILLER_EXAMPLES = 3
        private const val MIN_WINDOW = 0.5f
        private const val MIN_DURATION = 0.01f
        private val NUMBER_WORDS = listOf("یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده", "صد", "هزار", "میلیون")
    }
}

/** The text tool: the line's words as chips, the picked span and the line's waveform window. */
data class TextTool(
    val chips: List<WordChip> = emptyList(),
    val selection: IntRange? = null,
    val quote: String = "",
    val spanStart: Float = 0f,
    val spanEnd: Float = 0f,
    val windowStart: Float = 0f,
    val windowEnd: Float = 1f,
    val segments: List<Segment> = emptyList(),
    val peaks: List<Float> = emptyList(),
    val emphasised: Boolean = false,
    val hit: Boolean = false,
    val cut: Boolean = false,
    val hasNumber: Boolean = false,
)

/** One or more words ([index]..[last]) as a chip. */
data class WordChip(val index: Int, val text: String, val cut: Boolean, val emphasised: Boolean, val edited: Boolean, val hit: Boolean, val last: Int = index)

/** A word on the line's strip, on the edit's clock. */
data class Segment(val start: Float, val end: Float, val selected: Boolean)

data class StyleTool(
    val styles: List<String> = emptyList(),
    val selected: String = "",
    val director: String = "",
    /** 0..100. */
    val energy: Int = 60,
    val density: CaptionDensity = CaptionDensity.Phrase,
    /** The director's edit is showing (before), for comparison. */
    val comparing: Boolean = false,
)

data class MoodRow(val id: String, val nameFa: String, val nameEn: String, val bpm: Int)

data class SoundTool(
    val moods: List<MoodRow> = emptyList(),
    /** The mood playing, null for no music. */
    val selected: String? = null,
    val director: String? = null,
    val musicDb: Float = EditFacts.DEFAULT_MUSIC_DB,
    val effects: Boolean = true,
    val effectCount: Int = 0,
    val lufs: Float = -14f,
    val peaks: List<Float> = emptyList(),
    /** Cue times as fractions of the edit. */
    val cues: List<Float> = emptyList(),
)

data class ElementRow(
    val key: String,
    val kind: FactElement.Kind,
    val title: String,
    /** Seconds on the edit's clock. */
    val at: Float,
    /** The spoken word it lands on. */
    val word: String?,
    val on: Boolean,
    val suggested: Boolean,
)

data class ElementsTool(
    val rows: List<ElementRow> = emptyList(),
    /** Suggestions not yet in the edit. */
    val suggestions: Int = 0,
    /** More suggestions than the list shows. */
    val more: Boolean = false,
    /** The add menu is open. */
    val adding: Boolean = false,
)

data class CutTool(
    val sourceDuration: Float = 0f,
    val duration: Float = 0f,
    val peaks: List<Float> = emptyList(),
    /** Source stretches not played, as fractions of the source. */
    val removed: List<ClosedFloatingPointRange<Float>> = emptyList(),
    val silences: Boolean = true,
    val pauses: Int = 0,
    val pauseSeconds: Float = 0f,
    val fillers: Boolean = false,
    val fillerCount: Int = 0,
    val fillerWords: List<String> = emptyList(),
    val beatCuts: Boolean = false,
    val beatCutsAvailable: Boolean = true,
)
