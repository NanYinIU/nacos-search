package com.github.nacos.search.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory for creating the Nacos tool window
 */
class NacosToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val nacosToolWindow = NacosToolWindow(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(
            nacosToolWindow.createContent(),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
    }
    
    override fun shouldBeAvailable(project: Project): Boolean {
        // Tool window is always available
        return true
    }
}