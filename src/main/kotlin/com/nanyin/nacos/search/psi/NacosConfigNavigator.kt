package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.ui.NacosSearchToolWindowFactory
import com.nanyin.nacos.search.ui.NacosSearchWindow

/**
 * Opens the Nacos Search tool window, selects a configuration and positions
 * the caret on a specific line. The single entry point used by both the
 * @NacosValue navigation and the config-side gutter line marker.
 */
object NacosConfigNavigator {

    fun navigate(project: Project, config: NacosConfiguration, lineIndex: Int = -1) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(NacosSearchToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeLater
            toolWindow.activate {
                val window = NacosSearchToolWindowFactory.getSearchWindow(toolWindow) ?: return@activate
                window.navigateToConfig(config, lineIndex)
            }
        }
    }
}
