package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosSearchService
import javax.swing.SwingUtilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PaginationPanelTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `dispose unregisters pagination panel from namespace service`() {
       val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
        lateinit var panel: PaginationPanel
        ApplicationManager.getApplication().invokeAndWait {
            panel = PaginationPanel()
        }
        assertTrue("panel should be registered as a listener", namespaceService.isRegisteredListener(panel))

        Disposer.dispose(panel)

        assertFalse(
            "panel must be removed from the app-level NamespaceService on dispose",
            namespaceService.isRegisteredListener(panel)
        )
    }

    @Test
    fun `updatePagination is safe off the EDT`() {
        // NamespaceService dispatches change notifications on Dispatchers.IO. Any Swing
        // mutation driven from there must marshal onto the EDT rather than touching
        // components directly.
        lateinit var panel: PaginationPanel
        ApplicationManager.getApplication().invokeAndWait {
            panel = PaginationPanel()
        }
        val state = NacosSearchService.PaginationState(
            currentPage = 2,
            pageSize = 10,
            totalCount = 25,
            totalPages = 3
        )

        // Called from a non-EDT thread, mirroring how onNamespaceChanged reaches it.
        assert(!SwingUtilities.isEventDispatchThread())
        panel.updatePagination(state) // must not throw and must not mutate off-EDT

        Disposer.dispose(panel)
    }
}
