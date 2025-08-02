package com.github.nacos.search.actions

import com.github.nacos.search.NacosSearchPlugin
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.*

/**
 * Action to clear the Nacos configuration cache
 */
class ClearCacheAction : AnAction(
    "Clear Nacos Cache",
    "Clear all cached Nacos configurations",
    AllIcons.Actions.GC
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to clear all cached Nacos configurations?\n\nThis action cannot be undone.",
            "Clear Cache",
            "Clear",
            "Cancel",
            Messages.getQuestionIcon()
        )
        
        if (result == Messages.YES) {
            val plugin = NacosSearchPlugin.getInstance()
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            
            scope.launch {
                try {
                    plugin.clearCache()
                    
                    withContext(Dispatchers.EDT) {
                        Messages.showInfoMessage(
                            project,
                            "Cache has been cleared successfully.",
                            "Cache Cleared"
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.EDT) {
                        Messages.showErrorDialog(
                            project,
                            "Error clearing cache: ${e.message}",
                            "Clear Cache Error"
                        )
                    }
                }
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        // Action is always enabled
        e.presentation.isEnabled = true
    }
}