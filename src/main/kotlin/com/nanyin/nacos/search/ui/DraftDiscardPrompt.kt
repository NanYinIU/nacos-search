package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.services.operations.EditBinding

/**
 * Asks before an action that would throw a draft away, offering exactly the two
 * choices ADR-0027 requires: cancel the action, or explicitly discard the draft.
 * There is no third answer and no silent discard.
 *
 * Returns true when the user chose to discard. The caller then calls
 * [com.nanyin.nacos.search.services.operations.EditSessionService.discardDraft]
 * — asking does not itself change anything.
 */
internal fun confirmDraftDiscard(
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
