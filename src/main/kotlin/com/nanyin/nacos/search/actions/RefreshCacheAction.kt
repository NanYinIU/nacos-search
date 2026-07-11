package com.nanyin.nacos.search.actions

import com.nanyin.nacos.search.NacosSearchPlugin
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking

/**
 * Action to refresh the Nacos configuration cache
 */
class RefreshCacheAction : AnAction(
    "Refresh Nacos Cache",
    "Refresh configuration cache from Nacos server",
    AllIcons.Actions.Refresh
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refreshing Nacos Cache", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to Nacos server..."
                indicator.isIndeterminate = true
                
                try {
                    val plugin = ApplicationManager.getApplication().getService(com.nanyin.nacos.search.NacosSearchPlugin::class.java)
                    val result = runBlocking { plugin.refreshCache() }
                    indicator.checkCanceled()

                    ApplicationManager.getApplication().invokeLater {
                        if (result.isSuccess) {
                            Messages.showInfoMessage(
                                project,
                                "Successfully refreshed metadata for ${result.getOrThrow()} configurations. Content loads on demand.",
                                "Cache Refreshed"
                            )
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Failed to refresh cache: ${result.exceptionOrNull()?.message ?: "Unknown error"}",
                                "Refresh Failed"
                            )
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Error refreshing cache: ${e.message}",
                            "Refresh Error"
                        )
                    }
                }
            }
        })
    }
    
    override fun update(e: AnActionEvent) {
        // Action is always enabled
        e.presentation.isEnabled = true
    }
}
