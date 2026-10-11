package io.trimio.engine.production

import kotlinx.serialization.Serializable

/**
 * The creator's own changes to a finished edit (the editor), on top of the director's cut. Every
 * field is null or empty while the director's choice stands; [Retouch] applies them to the
 * director's production deterministically (the same overrides always give the same edit), so undo
 * is just going back to an earlier value ([EditHistory]).
 *
 * Words are addressed by their index in the edit's transcript (source clock); the transcript never
 * gains or loses words, so indices stay valid through every change. Elements are addressed by key:
 * `b<scene>.<beat>` for the director's beats, `a<n>` for the creator's additions ([added]) and
 * `d<n>` for the director's further suggestions ([EditFacts.elements]).
 */
@Serializable
data class EditOverrides(
    /** Word → emphasised (true) or plain (false). */
    val emphasis: Map<Int, Boolean> = emptyMap(),
    /** The creator's own text for spans of words, the latest last. */
    val texts: List<Retext> = emptyList(),
    /** Words with an impact sound on them. */
    val hits: Set<Int> = emptySet(),
    /** Words cut out of the edit (sound, picture and captions). */
    val deleted: Set<Int> = emptySet(),
    /** A style id ([ProductionStyles.ids]). */
    val style: String? = null,
    /** How hard everything moves, 0 calm … 1 explosive. */
    val energy: Float? = null,
    val captions: CaptionDensity? = null,
    /** A music mood id, or [NO_MUSIC]. */
    val music: String? = null,
    /** The music bed under the voice, dB relative to it. */
    val musicDb: Float? = null,
    /** The sound effects on the edit's accents (the creator's own hits always play). */
    val effects: Boolean? = null,
    /** Element key → shown or not. */
    val elements: Map<String, Boolean> = emptyMap(),
    /** Elements the creator added. */
    val added: List<AddedElement> = emptyList(),
    /** Pauses cut (the director's default). */
    val silences: Boolean? = null,
    /** Hesitations and verbal tics («اِم»، «خب»، «یعنی») cut. */
    val fillers: Boolean? = null,
    /** Cuts moved onto the music's beats (up to [Retouch.BEAT_SNAP] seconds). */
    val beatCuts: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == EditOverrides()

    /** This state with [change] applied. */
    fun with(change: EditChange): EditOverrides = change.applyTo(this)

    companion object {
        const val NO_MUSIC = "none"
    }
}

/** [text] in place of words [from]..[to]: the first word carries it, the others are merged into it. */
@Serializable
data class Retext(val from: Int, val to: Int, val text: String)

/** How many words a caption shows at once. */
@Serializable
enum class CaptionDensity(val maxWords: Int) {
    Word(2), Phrase(4), Sentence(7);

    companion object {
        fun of(maxWords: Int): CaptionDensity = when {
            maxWords <= Word.maxWords + 1 -> Word
            maxWords <= Phrase.maxWords + 1 -> Phrase
            else -> Sentence
        }
    }
}

/** A graphic the creator adds on words [from]..[to]. */
@Serializable
data class AddedElement(val kind: ElementKind, val from: Int, val to: Int, val text: String)

/** What the creator can add: a headline, a slammed word, or a counter for a spoken number. */
@Serializable
enum class ElementKind(val recipe: String) { Headline("mask-rise"), Impact("slam"), Counter("counter") }

/**
 * One change made in the editor, typed. Each one is a pure step from one [EditOverrides] to the
 * next ([EditOverrides.with]); a null value hands the choice back to the director.
 */
sealed interface EditChange {
    fun applyTo(o: EditOverrides): EditOverrides

    /** Emphasise words [from]..[to] (caption highlight and the graphics that say them), or take it off. */
    data class Emphasise(val from: Int, val to: Int, val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(emphasis = o.emphasis + (from..to).associateWith { on })
    }

    /** The creator's own text for words [from]..[to] (a blank text restores what was said). */
    data class Edit(val from: Int, val to: Int, val text: String) : EditChange {
        override fun applyTo(o: EditOverrides): EditOverrides {
            val kept = o.texts.filter { it.to < from || it.from > to }
            val clean = text.trim().replace(Regex("\\s+"), " ")
            return o.copy(texts = if (clean.isEmpty()) kept else kept + Retext(from, to, clean))
        }
    }

    /** An impact sound on [word], or none. */
    data class Hit(val word: Int, val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(hits = if (on) o.hits + word else o.hits - word)
    }

    /** Cut words [from]..[to] out of the edit, or put them back. */
    data class Delete(val from: Int, val to: Int, val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(deleted = if (on) o.deleted + (from..to) else o.deleted - (from..to).toSet())
    }

    data class Style(val id: String?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(style = id)
    }

    data class Energy(val value: Float?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(energy = value?.coerceIn(0f, 1f))
    }

    data class Captions(val density: CaptionDensity?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(captions = density)
    }

    /** A music mood id, [EditOverrides.NO_MUSIC], or null for the director's. */
    data class Music(val mood: String?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(music = mood)
    }

    data class MusicLevel(val db: Float?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(musicDb = db?.coerceIn(Retouch.MIN_MUSIC_DB, Retouch.MAX_MUSIC_DB))
    }

    data class Effects(val on: Boolean?) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(effects = on)
    }

    /** The director's own mix back: music, its level and the effects. */
    data object AutoMix : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(music = null, musicDb = null, effects = null)
    }

    /** Show or hide an element by key. */
    data class Element(val key: String, val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(elements = o.elements + (key to on))
    }

    data class Add(val element: AddedElement) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(added = o.added + element)
    }

    data class Silences(val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(silences = on)
    }

    data class Fillers(val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(fillers = on)
    }

    data class BeatCuts(val on: Boolean) : EditChange {
        override fun applyTo(o: EditOverrides) = o.copy(beatCuts = on)
    }
}

/**
 * Undo and redo over [EditOverrides] states. A change after an undo drops the redo branch, as
 * every editor does; [merge] folds a run of changes to one control (a slider being dragged) into
 * one step.
 */
class EditHistory(initial: EditOverrides = EditOverrides(), private val limit: Int = LIMIT) {
    private val past = ArrayDeque<EditOverrides>()
    private val future = ArrayDeque<EditOverrides>()
    private var lastGroup: String? = null

    var current: EditOverrides = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Applies [change]; changes with the same non-null [merge] key in a row are one undo step. */
    fun apply(change: EditChange, merge: String? = null): EditOverrides {
        val next = current.with(change)
        if (next == current) return current
        if (merge == null || merge != lastGroup) {
            past.addLast(current)
            if (past.size > limit) past.removeFirst()
        }
        future.clear()
        lastGroup = merge
        current = next
        return current
    }

    /** Ends a run of merged changes (the finger lifted): the next change is a step of its own. */
    fun seal() {
        lastGroup = null
    }

    fun undo(): EditOverrides {
        val previous = past.removeLastOrNull() ?: return current
        future.addLast(current)
        current = previous
        lastGroup = null
        return current
    }

    fun redo(): EditOverrides {
        val next = future.removeLastOrNull() ?: return current
        past.addLast(current)
        current = next
        lastGroup = null
        return current
    }

    private companion object {
        const val LIMIT = 100
    }
}
