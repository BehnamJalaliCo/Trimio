package io.trimio.shared.navigation

import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.ProductionRecord
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Edit
import io.trimio.engine.production.EditOverrides
import io.trimio.engine.production.Opened
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.RecentEdits
import kotlinx.coroutines.CancellationException

/**
 * Finished edits by project id (or run key, or the editor's draft id), for the editor and the
 * export: a project's current version is the edit its run delivered with the version's own
 * changes ([ProductionRecord.overrides]). Held in memory while this process made or opened it;
 * after the app restarts it is made again from what the run kept ([AutoEditor.reopen]): no
 * recognition and no director, the same edit as it was delivered.
 */
class ProjectEdits(
    private val recent: RecentEdits,
    private val projects: ProjectRepository,
    private val editor: AutoEditor,
) : ProducedEdits {

    /** The held edit [id] names; for a project, only when it is the project's current version. */
    override fun find(id: String): Edit? {
        val record = projects.get(id)?.production ?: return recent.find(id)
        return recent.find(record.key)?.takeIf { it.overrides == overridesOf(record) }
    }

    override suspend fun open(id: String): Opened {
        find(id)?.let { return Opened.Ready(it) }
        val project = projects.get(id) ?: return Opened.NotKept
        val record = project.production?.takeIf { finished(project) } ?: return Opened.NotKept
        val request = ProductionProjects.decode(record.request) ?: return Opened.NotKept
        val overrides = overridesOf(record)
        val opened = runCatching {
            // The run's edit is still held (another version of it is): only the creator's changes are applied again.
            recent.find(record.key)?.let { held -> Opened.Ready(editor.touch(held, overrides)) } ?: editor.reopen(request, overrides)
        }.onFailure { if (it is CancellationException) throw it }
            // An engine that cannot apply changes, or a saved state it cannot read: nothing to open.
            .getOrDefault(Opened.NotKept)
        if (opened is Opened.Ready) recent.remember(opened.edit)
        return opened
    }

    override suspend fun openable(id: String): Boolean {
        if (find(id) != null) return true
        val record = projects.get(id)?.takeIf(::finished)?.production ?: return false
        return recent.find(record.key) != null || ProductionProjects.decode(record.request)?.let { editor.kept(it) } == true
    }

    override fun hold(id: String, edit: Edit) = recent.hold(id, edit)

    /** A project whose current version is finished (while a next one is made, its run is not done yet). */
    private fun finished(p: Project) = p.outputUri != null && p.status == ProjectStatus.Ready

    private fun overridesOf(record: ProductionRecord) = EditOverrides.decode(record.overrides) ?: EditOverrides()
}
