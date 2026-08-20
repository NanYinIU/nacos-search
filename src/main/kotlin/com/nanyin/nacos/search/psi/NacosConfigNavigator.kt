package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.ui.NacosSearchToolWindowFactory
import com.nanyin.nacos.search.ui.NacosSearchWindow
import com.nanyin.nacos.search.Edt

/**
 * Opens the Nacos Search tool window, selects a configuration and positions
 * the caret on a specific line. The single entry point used by both the
 * @NacosValue navigation and the config-side gutter line marker.
 */
object NacosConfigNavigator {

    /**
     * @param namespaceId coordinate Namespace for tool-window switching (issue
     *   #190). When null, [NacosSearchWindow.navigateToConfig] falls back to
     *   the payload tenant — prefer the coordinate whenever the resolve path
     *   holds one so tenant-less bodies still land in the right Namespace.
     */
    fun navigate(
        project: Project,
        config: NacosConfiguration,
        lineIndex: Int = -1,
        namespaceId: String? = null
    ) {
        Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(NacosSearchToolWindowFactory.TOOL_WINDOW_ID) ?: return@invokeOnEdt
            toolWindow.activate {
                val window = NacosSearchToolWindowFactory.getSearchWindow(toolWindow) ?: return@activate
                window.navigateToConfig(config, lineIndex, namespaceId)
            }
        }
    }
}
