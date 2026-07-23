package com.nanyin.nacos.search.services.operations

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.ErrorDiffRequest
import com.intellij.diff.requests.LoadingDiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.NoDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project

/**
 * Shows an IntelliJ Diff between two read-only history sides.
 *
 * Both history-to-history and history-to-current comparisons are supported.
 * No mutation, restore, or republish is derived from a diff — this presenter
 * constructs a presentational [SimpleDiffRequest] and displays it, nothing more.
 * Writable sides would show IDEA's default gutter apply/merge chevrons; both
 * documents and the request are forced read-only so only highlighting remains.
 */
object HistoryDiffPresenter {

    /** Builds a [DiffRequest] without opening a window (for embedded panels). */
    fun toDiffRequest(request: HistoryDiffRequest): DiffRequest {
        val leftContent = createContent(request.left)
        val rightContent = createContent(request.right)
        return SimpleDiffRequest(
            request.left.title + " ↔ " + request.right.title,
            leftContent,
            rightContent,
            request.left.title,
            request.right.title
        ).also { it.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true) }
    }

    /** Applies [request] to an embedded [DiffRequestPanel]. */
    fun showIn(panel: DiffRequestPanel, request: HistoryDiffRequest) {
        panel.setRequest(toDiffRequest(request))
    }

    fun showLoading(panel: DiffRequestPanel, message: String) {
        panel.setRequest(LoadingDiffRequest(message))
    }

    fun showEmpty(panel: DiffRequestPanel, message: String) {
        panel.setRequest(MessageDiffRequest(message))
    }

    fun showError(panel: DiffRequestPanel, message: String) {
        panel.setRequest(ErrorDiffRequest(message))
    }

    fun showNone(panel: DiffRequestPanel) {
        panel.setRequest(NoDiffRequest.INSTANCE)
    }

    /**
     * Builds and shows the diff dialog for [request] within [project].
     */
    fun show(project: Project?, request: HistoryDiffRequest) {
        DiffManager.getInstance().showDiff(project, toDiffRequest(request))
    }

    private fun createContent(side: HistoryDiffSide): DocumentContent {
        val fileType = resolveFileType(side.contentType)
        val document = DocumentImpl(side.content).apply { setReadOnly(true) }
        return com.intellij.diff.contents.DocumentContentImpl(null, document, fileType)
    }

    private fun resolveFileType(contentType: String?): FileType {
        if (contentType.isNullOrBlank()) return FileTypeManager.getInstance().getFileTypeByExtension("txt")
        return FileTypeManager.getInstance().getFileTypeByExtension(contentType.trim())
    }

    /** Builds a [HistoryDiffRequest] for comparing two historical versions. */
    fun historyToHistory(
        left: HistoryDetail,
        right: HistoryDetail
    ): HistoryDiffRequest = HistoryDiffRequest(
        left = HistoryDiffSide(
            title = sideTitle(left),
            content = left.content,
            contentType = left.type
        ),
        right = HistoryDiffSide(
            title = sideTitle(right),
            content = right.content,
            contentType = right.type
        )
    )

    /** Builds a [HistoryDiffRequest] for comparing a historical version with the current detail. */
    fun historyToCurrent(
        history: HistoryDetail,
        currentContent: String,
        currentType: String?
    ): HistoryDiffRequest = HistoryDiffRequest(
        left = HistoryDiffSide(
            title = sideTitle(history),
            content = history.content,
            contentType = history.type
        ),
        right = HistoryDiffSide(
            title = "Current",
            content = currentContent,
            contentType = currentType
        )
    )

    private fun sideTitle(detail: HistoryDetail): String =
        "History ${detail.id} (${HistoryTimestamps.formatForDisplay(detail.lastModified)})"
}
