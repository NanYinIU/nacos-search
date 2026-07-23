package com.nanyin.nacos.search.services.operations

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@TestApplication
class HistoryDiffRequestTest {

    @Test
    fun `history-to-history diff builds left and right sides from two historical details`() {
        val left = HistoryDetail("1", "app.yaml", "G", null, "enabled: false", "yaml", "m1", 1000L, "PUBLISH")
        val right = HistoryDetail("2", "app.yaml", "G", null, "enabled: true", "yaml", "m2", 2000L, "PUBLISH")

        val request = HistoryDiffPresenter.historyToHistory(left, right)

        assertTrue(request.left.title.contains("1"))
        assertTrue(request.right.title.contains("2"))
        assertEquals("enabled: false", request.left.content)
        assertEquals("enabled: true", request.right.content)
        assertEquals("yaml", request.left.contentType)
    }

    @Test
    fun `history-to-current diff compares a historical version against the current content`() {
        val history = HistoryDetail("1", "app.yaml", "G", null, "enabled: false", "yaml", "m1", 1000L, "PUBLISH")

        val request = HistoryDiffPresenter.historyToCurrent(history, "enabled: true", "yaml")

        assertEquals("enabled: false", request.left.content)
        assertEquals("enabled: true", request.right.content)
        assertEquals("yaml", request.right.contentType)
    }

    @Test
    fun `diff request carries no publish command or edit session`() {
        val left = HistoryDetail("1", "app.yaml", "G", null, "content-a", "yaml", "m1", 1000L, "PUBLISH")
        val right = HistoryDetail("2", "app.yaml", "G", null, "content-b", "yaml", "m2", 2000L, "PUBLISH")

        val request = HistoryDiffPresenter.historyToHistory(left, right)

        // The diff request is purely presentational data — it has no
        // publish-command field, no edit-session reference, and no mutation API.
        val fields = HistoryDiffRequest::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("publish", ignoreCase = true) || it.contains("command", ignoreCase = true) })
        assertFalse(fields.any { it.contains("edit", ignoreCase = true) || it.contains("session", ignoreCase = true) })
    }

    @Test
    fun `platform diff request forces read-only sides so merge chevrons stay hidden`() {
        val history = HistoryDetail("1", "app.yaml", "G", null, "enabled: false", "yaml", "m1", 1000L, "PUBLISH")
        val request = HistoryDiffPresenter.toDiffRequest(
            HistoryDiffPresenter.historyToCurrent(history, "enabled: true", "yaml")
        ) as SimpleDiffRequest

        assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        val contents = request.contents.map { it as DocumentContent }
        assertTrue(contents.all { it.document.isWritable.not() })
    }
}
