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
import kotlinx.coroutines.*

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
                    
                    // Use coroutine scope for proper async handling
                    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    scope.launch {
                        try {
                            val result = plugin.refreshCache()
                            
                            ApplicationManager.getApplication().invokeLater {
                                if (result.isSuccess) {
                                    val count = result.getOrThrow()
                                    Messages.showInfoMessage(
                                        project,
                                        "Successfully refreshed cache with $count configurations.",
                                        "Cache Refreshed"
                                    )
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                                    Messages.showErrorDialog(
                                        project,
                                        "Failed to refresh cache: $error",
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
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Error initializing refresh: ${e.message}",
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