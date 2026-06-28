package com.nanyin.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.ContentFactory

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
        
        // Set tool window properties
        toolWindow.setToHideOnEmptyContent(false)
        toolWindow.setShowStripeButton(true)
        
        // Note: Search window reference stored in content for access if needed
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
