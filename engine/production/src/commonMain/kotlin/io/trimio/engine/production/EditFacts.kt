package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Workbench
import io.trimio.engine.autopilot.explainer.FilmBench
import io.trimio.engine.motion.explainer.PaletteBook
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.style.StyleBook
import kotlin.math.abs

/**
 * What the editor shows of an [Edit]: its words on the output clock, the director's picks beside
 * the current ones (style, music), the elements with their switches, the cut's pauses and fillers,
 * the sound's level and effects, and waveforms. Read off the edit, never guessed, so every count
 * on screen is the edit's own.
 */
class EditFacts private constructor(
    val edit: Edit,
    val words: List<FactWord>,
    /** The transcript's lines (thoughts), as word index ranges. */
    val lines: List<IntRange>,
    val elements: List<FactElement>,
    /** Every suggestion the director has beyond the edit (`d<n>` elements), shown or not. */
    val suggestions: Int,
    val fillers: List<Int>,
    private val voice: PcmAudio?,
) {
    private val production: Autopilot.Production get() = edit.production
    private val director: Autopilot.Production get() = edit.base
    private val o: EditOverrides get() = edit.overrides

    /** The edit's length and the source's (seconds). */
    val duration: Float get() = production.compiled.composition.duration
    val sourceDuration: Float get() = voice?.let { it.samples.size / it.sampleRate.toFloat() } ?: director.edit.segments.lastOrNull()?.sourceEnd ?: duration

    val style: String get() = production.score.look
    val directorStyle: String get() = director.score.look

    /** An explainer film: its palette, music and whooshes change; its words are the narration's ([FilmBench]). */
    val film: Boolean get() = director.filmBench != null

    /** A YouTube video: its recording's own sound and whole length stay; its words, look and devices change. */
    val recorded: Boolean get() = director.bench?.recorded == true

    /**
     * Styles to offer: the director's pick first, then the ones that fit the piece's domain and mood best. A film's are
     * its palettes, every one of them.
     */
    val styles: List<String>
        get() {
            if (film) return (listOf(directorStyle, style) + PaletteBook.ids).distinct()
            val u = director.understanding
            val ranked = StyleBook.all.sortedByDescending { it.fitness(u.domain, u.mood) }.map { it.id }
            return (listOf(directorStyle, style) + ranked).distinct().take(MAX_STYLES)
        }

    /** The edit's mean motion energy, 0..1. */
    val energy: Float get() = Retouch.meanEnergy(production.score)
    val captions: CaptionDensity get() = CaptionDensity.of(production.score.captions.maxWords)

    /** The music mood id, or null without music. */
    val music: String? get() = production.plan.music?.id
    val directorMusic: String? get() = director.plan.music?.id

    /** Every mood the composer has: the director's pick first. */
    val moods: List<MusicChoice>
        get() = MusicMood.entries.sortedBy { if (it.id == directorMusic) 0 else 1 }.map { MusicChoice(it.id, it.nameFa, it.nameEn, it.bpm) }

    /** The bed under the voice, dB. */
    val musicDb: Float get() = o.musicDb ?: director.bench?.levels?.musicUnderDb ?: DEFAULT_MUSIC_DB
    val effects: Boolean get() = o.effects ?: true

    /** The effects in the edit now, and the ones the director's accents carry (what the switch turns on and off). */
    val effectCount: Int get() = if (film) (if (effects) filmCues.size else 0) else production.compiled.sfx.size
    val accentEffects: Int get() = if (film) filmCues.size else director.compiled.sfx.size

    /** A film's whooshes, one where each chapter arrives. */
    private val filmCues: List<Float> get() = production.film?.let(FilmBench::cues).orEmpty().map { it.at }
    val lufs: Double get() = production.soundtrack.lufs
    val peakDb: Double get() = production.soundtrack.peakDb

    /** The director's cut pauses: how many and how many seconds. */
    val pauses: Pair<Int, Float> get() = Cuts.pauses(director.edit)
    val silences: Boolean get() = o.silences ?: true
    val fillersCut: Boolean get() = o.fillers ?: false
    val beatCuts: Boolean get() = o.beatCuts ?: false

    /** Cuts can move onto beats only when there is music. */
    val beatCutsAvailable: Boolean get() = production.plan.music != null

    /** Source stretches the edit does not play. */
    val unplayed: List<ClosedFloatingPointRange<Float>> get() = Cuts.unplayed(production.edit, sourceDuration)

    /** Output times of the edit's sound cues. */
    val cues: List<Float> get() = if (film) filmCues.takeIf { effects }.orEmpty() else production.compiled.sfx.map { it.at }

    /** What the spelling gate warned about in the creator's own text. */
    val warnings: List<String> get() = edit.warnings

    /** [n] bars of the voice (source clock) between [from] and [to] seconds, 0..1. */
    fun voicePeaks(n: Int, from: Float = 0f, to: Float = sourceDuration): FloatArray = voice?.let { peaks(it, from, to, n) } ?: FloatArray(n)

    /** [n] bars of the finished mix (output clock) between [from] and [to] seconds, 0..1. */
    fun mixPeaks(n: Int, from: Float = 0f, to: Float = duration): FloatArray = peaks(production.soundtrack.audio, from, to, n)

    /** The line a time on the output clock falls in (the last one started by then). */
    fun lineAt(t: Float): Int {
        val k = lines.indexOfLast { l -> words.getOrNull(l.first)?.let { it.start <= t + LINE_LEAD } == true }
        return k.coerceAtLeast(0)
    }

    /** The word spoken at output time [t] (the last one started by then), skipping cut ones. */
    fun wordAt(t: Float): Int? = words.lastOrNull { !it.cut && it.start <= t }?.index ?: words.firstOrNull { !it.cut }?.index

    companion object {
        const val DEFAULT_MUSIC_DB = -20f
        private const val MAX_STYLES = 8
        private const val LINE_LEAD = 0.05f
        private const val MS = 1000f

        fun of(edit: Edit): EditFacts {
            val bench = edit.base.bench
            val words = words(edit, bench)
            val lines = bench?.lines?.map { it.range } ?: listOfNotNull(words.indices.takeIf { !it.isEmpty() })
            val suggestions = bench?.let { Retouch.suggestions(edit.base, it) }.orEmpty()
            val fillers = bench?.let { Cuts.fillers(it.transcript.words) }.orEmpty()
            return EditFacts(edit, words, lines, elements(edit, bench, words, suggestions), suggestions.size, fillers, bench?.voice)
        }

        private fun words(edit: Edit, bench: Workbench?): List<FactWord> {
            val source = bench?.transcript?.words ?: edit.transcript.words
            val o = edit.overrides
            val plan = edit.production.edit
            val cut = bench?.let { Cuts.removed(it, o) }.orEmpty()
            return source.mapIndexed { i, w ->
                val retext = o.texts.lastOrNull { i in it.from..it.to }
                val start = plan.toOutput(w.range.startMs / MS)
                FactWord(
                    index = i, said = w.text,
                    text = when {
                        retext == null -> w.text
                        retext.from == i -> retext.text
                        else -> ""
                    },
                    start = start, end = maxOf(start, plan.toOutput(w.range.endMs / MS)),
                    cut = i in cut, emphasised = o.emphasis[i] ?: (w.emphasis >= EMPHASIS), hit = i in o.hits, edited = retext != null,
                )
            }
        }

        private fun elements(edit: Edit, bench: Workbench?, words: List<FactWord>, suggestions: List<BeatScore>): List<FactElement> {
            val o = edit.overrides
            val score = edit.base.score
            val plan = edit.production.edit
            fun at(b: BeatScore, scene: Int): Float = timeOf(b, scene, score, words, plan)
            val director = score.scenes.flatMapIndexed { k, s ->
                s.beats.mapIndexed { j, b ->
                    val key = Retouch.key(k, j)
                    FactElement(key, b.recipe, kindOf(b.recipe), titleOf(b), at(b, k), o.elements[key] != false, FactElement.Origin.Director, b.at?.let { words.getOrNull(it)?.text })
                }
            }
            val added = o.added.mapIndexed { i, a ->
                val key = "a$i"
                FactElement(key, a.kind.recipe, kindOf(a.kind.recipe), a.text, words.getOrNull(a.from)?.start ?: 0f, o.elements[key] != false, FactElement.Origin.Added, a.text)
            }
            val suggested = suggestions.mapIndexed { i, b ->
                val key = "d$i"
                val k = bench?.let { Retouch.sceneOf(score, b.at ?: 0, it.transcript.words) } ?: 0
                FactElement(key, b.recipe, kindOf(b.recipe), titleOf(b), at(b, k), o.elements[key] == true, FactElement.Origin.Suggested, b.at?.let { words.getOrNull(it)?.text })
            }
            return (director + added + suggested).filter { it.recipe != CAPTIONS }
        }

        /** Where a beat lands on the output clock: its word, its time, the words it quotes, or its scene's start. */
        private fun timeOf(b: BeatScore, scene: Int, score: Score, words: List<FactWord>, plan: EditPlan): Float {
            b.at?.let { i -> words.getOrNull(i)?.let { return it.start } }
            b.time?.let { return it }
            val from = score.scenes.getOrNull(scene)?.from ?: 0
            val first = b.text?.split(' ')?.firstOrNull()?.let(NumberWords::normalize)
            val found = first?.let { f -> words.drop(from).firstOrNull { NumberWords.normalize(it.said) == f } }
            return found?.start ?: words.getOrNull(from)?.start ?: plan.bodyStart
        }

        private fun titleOf(b: BeatScore): String {
            val number = b.value?.let { v ->
                val shown = if (abs(v - v.toInt()) < NUMBER_EPS) v.toInt().toString() else v.toString()
                (b.prefix.orEmpty() + shown + b.suffix.orEmpty())
            }
            return listOfNotNull(b.text, number, b.label, b.items.takeIf { it.isNotEmpty() }?.joinToString("، "), b.brand, b.visual, b.icon)
                .firstOrNull { it.isNotBlank() }.orEmpty()
        }

        fun kindOf(recipe: String): FactElement.Kind = when (recipe) {
            in TEXTS -> FactElement.Kind.Title
            in setOf("counter", "ticker", "meter", "stats", "progress", "countdown") -> FactElement.Kind.Number
            in setOf("chart", "bars") -> FactElement.Kind.Chart
            in setOf("icon", "object", "objects", "ui-scene") -> FactElement.Kind.Icon
            in setOf("logos", "logo-mark", "chips") -> FactElement.Kind.Brand
            in setOf("comment", "endcard") -> FactElement.Kind.Ask
            in setOf("b-roll", "pip", "screenshot", "product", "media") -> FactElement.Kind.Picture
            in setOf("message", "ui-chat", "ui-feed", "ui-browser", "ui-code", "ui-dashboard", "terminal", "url") -> FactElement.Kind.Screen
            else -> FactElement.Kind.Other
        }

        /** [n] bars of [audio]'s peaks between [from] and [to] seconds, normalised to the loudest bar. */
        fun peaks(audio: PcmAudio, from: Float, to: Float, n: Int): FloatArray {
            val out = FloatArray(n)
            if (n <= 0 || to <= from) return out
            val rate = audio.sampleRate
            val s = audio.samples
            for (k in 0 until n) {
                val a = ((from + (to - from) * k / n) * rate).toInt().coerceIn(0, s.size)
                val b = ((from + (to - from) * (k + 1) / n) * rate).toInt().coerceIn(a, s.size)
                var m = 0f
                // Every fourth sample is enough for a bar's height.
                var i = a
                while (i < b) { m = maxOf(m, abs(s[i])); i += PEAK_STEP }
                out[k] = m
            }
            val top = out.maxOrNull()?.takeIf { it > 0f } ?: return out
            for (k in out.indices) out[k] = out[k] / top
            return out
        }

        private const val CAPTIONS = "pop-captions"
        private const val EMPHASIS = 0.6f
        private const val NUMBER_EPS = 0.001f
        private const val PEAK_STEP = 4
        private val TEXTS = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch", "lower-third", "stamp", "note", "title", "list")
    }
}

/** A word as the editor shows it: what was said, what is shown, when it plays (output clock) and what the creator did to it. */
data class FactWord(
    val index: Int,
    val said: String,
    /** Blank when merged into an earlier word's text. */
    val text: String,
    val start: Float,
    val end: Float,
    val cut: Boolean,
    val emphasised: Boolean,
    val hit: Boolean,
    val edited: Boolean,
)

/** One graphic: its key ([EditOverrides.elements]), what it is, what it says, when it lands and whether it is on. */
data class FactElement(
    val key: String,
    val recipe: String,
    val kind: Kind,
    val title: String,
    val at: Float,
    val on: Boolean,
    val origin: Origin,
    /** The spoken word it lands on, when it is tied to one. */
    val word: String? = null,
) {
    enum class Kind { Title, Number, Chart, Icon, Brand, Ask, Picture, Screen, Other }
    enum class Origin { Director, Added, Suggested }
}

data class MusicChoice(val id: String, val nameFa: String, val nameEn: String, val bpm: Int)
