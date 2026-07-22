package com.nanyin.nacos.search.services.operations

import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
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
 */
object HistoryDiffPresenter {

    /**
     * Builds and shows the diff dialog for [request] within [project].
     */
    fun show(project: Project?, request: HistoryDiffRequest) {
        val leftContent = createContent(request.left)
        val rightContent = createContent(request.right)
        val diffRequest = SimpleDiffRequest(
            request.left.title + " ↔ " + request.right.title,
            leftContent,
            rightContent,
            request.left.title,
            request.right.title
        )
        DiffManager.getInstance().showDiff(project, diffRequest)
    }

    private fun createContent(side: HistoryDiffSide): DocumentContent {
        val fileType = resolveFileType(side.contentType)
        val document = DocumentImpl(side.content)
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
            title = "History ${left.id} (${formatTimestamp(left.lastModified)})",
            content = left.content,
            contentType = left.type
        ),
        right = HistoryDiffSide(
            title = "History ${right.id} (${formatTimestamp(right.lastModified)})",
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
            title = "History ${history.id} (${formatTimestamp(history.lastModified)})",
            content = history.content,
            contentType = history.type
        ),
        right = HistoryDiffSide(
            title = "Current",
            content = currentContent,
            contentType = currentType
        )
    )

    private fun formatTimestamp(millis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(millis)
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
