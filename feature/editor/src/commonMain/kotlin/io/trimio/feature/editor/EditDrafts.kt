package io.trimio.feature.editor

import io.trimio.engine.production.EditOverrides

/**
 * Where the editor keeps the creator's changes that are not yet a version (a project's draft), so
 * leaving the app or the phone killing it loses nothing; the next time the editor opens, they are
 * back, one undo away from the version as it was made.
 */
interface EditDrafts {
    /** The unsaved changes of the edit [id] names; null when there are none. */
    fun draft(id: String): EditOverrides?

    /** Keeps [overrides] as [id]'s unsaved changes; null forgets them (nothing left unsaved). */
    fun keep(id: String, overrides: EditOverrides?)
}
