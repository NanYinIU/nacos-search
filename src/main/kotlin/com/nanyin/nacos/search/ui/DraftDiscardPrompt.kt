package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditBinding
import com.nanyin.nacos.search.services.operations.EditSessionService

/**
 * Asks before an action that would throw a draft away, offering exactly the two
 * choices ADR-0027 requires: cancel the action, or explicitly discard the draft.
 * There is no third answer and no silent discard.
 *
 * Named object so IDEA's JPS compiler indexes the helpers. A file of only
 * top-level functions is dropped from that compile.
 */
internal object DraftDiscardPrompt {
    /**
     * Returns true when the user chose to discard. The caller then calls
     * [EditSessionService.discardDraft] — asking does not itself change anything.
     */
    fun confirm(
        project: Project,
        draft: EditBinding,
        messageKey: String
    ): Boolean = Messages.showYesNoDialog(
        project,
        NacosSearchBundle.message(messageKey, draft.dataId, draft.group, draft.namespaceId),
        NacosSearchBundle.message("config.detail.draft.discard.title"),
        NacosSearchBundle.message("config.detail.draft.discard.confirm"),
        NacosSearchBundle.message("config.detail.draft.discard.cancel"),
        Messages.getWarningIcon()
    ) == Messages.YES

    /** Explains that a publish is in flight and the action cannot proceed. */
    fun explainPublishInFlight(project: Project) {
        Messages.showWarningDialog(
            project,
            NacosSearchBundle.message("config.detail.publish.in.flight"),
            NacosSearchBundle.message("config.detail.draft.discard.title")
        )
    }

    /**
     * Warns that the server may already have changed, then abandons on confirm.
     * Returns true when the draft was abandoned.
     */
    fun confirmWarnedAbandon(
        project: Project,
        sessions: EditSessionService,
        draft: EditBinding
    ): Boolean {
        val confirmed = Messages.showYesNoDialog(
            project,
            NacosSearchBundle.message(
                "config.detail.publish.abandon.warn",
                draft.dataId,
                draft.group,
                draft.namespaceId
            ),
            NacosSearchBundle.message("config.detail.publish.abandon.title"),
            NacosSearchBundle.message("config.detail.publish.abandon.confirm"),
            NacosSearchBundle.message("config.detail.draft.discard.cancel"),
            Messages.getWarningIcon()
        ) == Messages.YES
        return confirmed && sessions.abandonPublish()
    }

    /**
     * Shared admit for ADR-0027 guards, including mid-write refuse and
     * server-state-unknown warned abandon. [onKeepDraft] runs when the action is
     * cancelled while a ConfirmDiscard prompt is showing (e.g. restore selection).
     */
    fun admit(
        project: Project,
        sessions: EditSessionService,
        guard: DraftGuard,
        discardMessageKey: String,
        onKeepDraft: (EditBinding) -> Unit = {}
    ): Boolean = when (guard) {
        DraftGuard.Proceed, DraftGuard.AlreadyEditing -> true
        is DraftGuard.ConfirmDiscard -> {
            if (confirm(project, guard.draft, discardMessageKey)) {
                sessions.discardDraft()
                true
            } else {
                onKeepDraft(guard.draft)
                false
            }
        }
        DraftGuard.RefuseInFlight -> {
            explainPublishInFlight(project)
            false
        }
        is DraftGuard.RequireWarnedAbandon -> confirmWarnedAbandon(project, sessions, guard.draft)
    }
}
