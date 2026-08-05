package com.nanyin.nacos.search.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseHandler
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditSessionService

/**
 * ADR-0027 / ADR-0046: a dirty edit session is held by the project, so closing
 * the project must not drop it silently. [ProjectCloseHandler] is the platform
 * veto point for close; cancel keeps the project open and the draft intact.
 */
class DirtyDraftProjectCloseHandler : ProjectCloseHandler {
    override fun canClose(project: Project): Boolean {
        if (project.isDisposed) return true
        val editSessions = project.service<EditSessionService>()
        return when (val guard = editSessions.guardProjectClose()) {
            DraftGuard.Proceed, DraftGuard.AlreadyEditing -> true
            is DraftGuard.ConfirmDiscard -> {
                if (confirmDraftDiscard(project, guard.draft, "config.detail.draft.discard.project.close")) {
                    editSessions.discardDraft()
                    true
                } else {
                    false
                }
            }
            DraftGuard.RefuseInFlight -> {
                explainPublishInFlight(project)
                false
            }
            is DraftGuard.RequireWarnedAbandon -> confirmWarnedAbandon(project, editSessions, guard.draft)
        }
    }
}
