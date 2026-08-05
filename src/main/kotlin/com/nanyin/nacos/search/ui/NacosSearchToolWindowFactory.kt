package com.nanyin.nacos.search.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.nanyin.nacos.search.services.operations.DraftGuard
import com.nanyin.nacos.search.services.operations.EditSessionService

/**
 * Factory for creating Nacos Search tool window
 */
class NacosSearchToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Create the main search window
        val searchWindow = NacosSearchWindow(project, toolWindow)
        
        // Create content and add to tool window
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(
            searchWindow,
            "", // No tab title needed for single content
            false // Not closeable
        )
        
        // Dispose the window (unregisters listeners, cancels coroutines) when the content is removed.
        Disposer.register(content, searchWindow)

        toolWindow.contentManager.addContent(content)
        guardContentRemoval(project, toolWindow, content)

        // Set tool window properties
        toolWindow.setToHideOnEmptyContent(false)
        toolWindow.setShowStripeButton(true)
        
        // Note: Search window reference stored in content for access if needed
    }
    
    /**
     * ADR-0027: destroying the tool-window content must not discard a dirty
     * draft silently. The removal query is the one point where the platform
     * lets the plugin refuse, so the prompt lives here rather than in the
     * window — a component that is being disposed cannot veto its own disposal.
     */
    private fun guardContentRemoval(project: Project, toolWindow: ToolWindow, content: Content) {
        val contentManager = toolWindow.contentManager
        val listener = object : ContentManagerListener {
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                if (event.content !== content) return
                val sessions = project.service<EditSessionService>()
                when (val guard = sessions.guardDestroy()) {
                    DraftGuard.Proceed, DraftGuard.AlreadyEditing -> return
                    is DraftGuard.ConfirmDiscard -> {
                        if (confirmDraftDiscard(project, guard.draft, "config.detail.draft.discard.close")) {
                            sessions.discardDraft()
                        } else {
                            event.consume()
                        }
                    }
                    DraftGuard.RefuseInFlight -> {
                        explainPublishInFlight(project)
                        event.consume()
                    }
                    is DraftGuard.RequireWarnedAbandon -> {
                        if (!confirmWarnedAbandon(project, sessions, guard.draft)) {
                            event.consume()
                        }
                    }
                }
            }
        }
        contentManager.addContentManagerListener(listener)
        Disposer.register(content) { contentManager.removeContentManagerListener(listener) }
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Tool window should be available for all projects
        return true
    }
    
    companion object {
        const val TOOL_WINDOW_ID = "Nacos Search"
        
        /**
         * Get the search window instance from tool window content
         */
        fun getSearchWindow(toolWindow: ToolWindow): NacosSearchWindow? {
            val content = toolWindow.contentManager.contents.firstOrNull()
            return content?.component as? NacosSearchWindow
        }
    }
}
