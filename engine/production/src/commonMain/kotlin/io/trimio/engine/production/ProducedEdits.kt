package io.trimio.engine.production

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Finds a finished edit by the id a screen was opened with (export, editor): a production's run
 * key, or a project id that a store maps to one. Null when the edit is no longer held.
 */
fun interface ProducedEdits {
    fun find(id: String): Edit?
}

/**
 * The edits this process finished, newest first: the runner keeps only its current run, so the
 * previous versions (before a variation or a revision) stay reachable here by their run key.
 * [alias] maps another id (a project's) to a run key.
 */
class RecentEdits(
    runner: ProductionRunner,
    scope: CoroutineScope,
    private val alias: (String) -> String? = { null },
    private val capacity: Int = CAPACITY,
) : ProducedEdits {
    private val edits = MutableStateFlow<List<Edit>>(emptyList())

    init {
        scope.launch { runner.active.filterNotNull().collect { s -> s.edit?.let(::remember) } }
    }

    /** Keeps [edit] (newest first); an edit of the same run replaces the older one. */
    fun remember(edit: Edit) = edits.update { held -> (listOf(edit) + held.filter { it.request.key != edit.request.key }).take(capacity) }

    override fun find(id: String): Edit? {
        val key = alias(id) ?: id
        return edits.value.firstOrNull { it.request.key == key || it.request.id == id }
    }

    private companion object {
        /** Each edit holds its mastered sound and compiled layout: a few versions, not a library. */
        const val CAPACITY = 4
    }
}
