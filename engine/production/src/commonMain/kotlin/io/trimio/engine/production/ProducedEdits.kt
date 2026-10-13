package io.trimio.engine.production

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What opening a finished edit gives: the edit, or why it cannot be opened. */
sealed interface Opened {
    data class Ready(val edit: Edit) : Opened

    /** The creator's video or voice recording (or an attachment) can no longer be read on this device. */
    data object SourceMissing : Opened

    /** Nothing was kept to make it again from: made by an earlier version of the app, or its saved state is gone. */
    data object NotKept : Opened

    /** This platform has no engine to open edits with (web previews). */
    data object Unsupported : Opened

    /** An explainer film (made from a voice alone): it plays and exports, the editor does not change it yet. */
    data object Explainer : Opened
}

/**
 * Finds a finished edit by the id a screen was opened with (export, editor): a production's run
 * key, or a project id that a store maps to one. [find] answers at once from memory; [open] also
 * makes an edit again from what its run kept ([AutoEditor.reopen]) after the app restarted.
 */
interface ProducedEdits {
    /** The edit [id] names when it is held in memory now; null otherwise. */
    fun find(id: String): Edit?

    /** The edit [id] names: held, or made again from what was kept (seconds: the voice is read and the sound mastered again). */
    suspend fun open(id: String): Opened = find(id)?.let(Opened::Ready) ?: Opened.NotKept

    /** Whether [open] can give the edit, without opening it (no media is read). */
    suspend fun openable(id: String): Boolean = find(id) != null

    /** Holds [edit] under [id] for screens opened next (the editor's unsaved state, for the export screen). */
    fun hold(id: String, edit: Edit) = Unit
}

/**
 * The edits this process finished, newest first: the runner keeps only its current run, so the
 * previous versions (before a variation or a revision) stay reachable here by their run key.
 * [alias] maps another id (a project's) to a run key; [hold] keeps an edit under an id of its own.
 */
class RecentEdits(
    runner: ProductionRunner,
    scope: CoroutineScope,
    private val alias: (String) -> String? = { null },
    private val capacity: Int = CAPACITY,
) : ProducedEdits {
    private class Held(val id: String, val edit: Edit)

    private val edits = MutableStateFlow<List<Held>>(emptyList())

    init {
        scope.launch { runner.active.filterNotNull().collect { s -> s.edit?.let(::remember) } }
    }

    /** Keeps [edit] under its run key (newest first); a later edit of the same run replaces it. */
    fun remember(edit: Edit) = hold(edit.request.key, edit)

    override fun hold(id: String, edit: Edit) = edits.update { held -> (listOf(Held(id, edit)) + held.filter { it.id != id }).take(capacity) }

    override fun find(id: String): Edit? {
        val held = edits.value
        return held.firstOrNull { it.id == id }?.edit
            ?: alias(id)?.let { key -> held.firstOrNull { it.id == key }?.edit }
            ?: held.firstOrNull { it.edit.request.id == id }?.edit
    }

    private companion object {
        /** Each edit holds its mastered sound and compiled layout: a few versions, not a library. */
        const val CAPACITY = 4
    }
}
