package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.services.NacosSearchService
import org.junit.Rule
import org.junit.Test
import com.intellij.util.ui.UIUtil

class PaginationPanelTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `updatePagination is safe off the EDT`() {
        // Search publication may arrive off the EDT; rendering must marshal
        // onto the EDT rather than touching components directly.
        lateinit var panel: PaginationPanel
        UIUtil.invokeAndWaitIfNeeded {
            panel = PaginationPanel()
        }
        val state = NacosSearchService.PaginationState(
            currentPage = 2,
            pageSize = 10,
            totalCount = 25,
            totalPages = 3
        )

        // Called from a non-EDT thread, mirroring search-state publication.
        assert(!ApplicationManager.getApplication().isDispatchThread)
        panel.updatePagination(state) // must not throw and must not mutate off-EDT

        Disposer.dispose(panel)
    }
}
