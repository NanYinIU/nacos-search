package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.nanyin.nacos.search.services.LanguageService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import javax.swing.SwingUtilities
import java.awt.Component

/**
 * Utility class for refreshing UI components when language changes
 */
object LanguageRefreshUtil {
    
    /**
     * Refresh all language-aware components in the application
     */
    fun refreshAllComponents(newLanguage: LanguageService.SupportedLanguage) {
        SwingUtilities.invokeLater {
            // Refresh all open tool windows
            refreshToolWindows()
            
            // Refresh other components as needed
            refreshApplicationComponents()
        }
    }
    
    /**
     * Refresh all open tool windows
     */
    private fun refreshToolWindows() {
        val projectManager = ApplicationManager.getApplication().getService(com.intellij.openapi.project.ProjectManager::class.java)
        projectManager.openProjects.forEach { project ->
            val toolWindowManager = ToolWindowManager.getInstance(project)
            val toolWindow = toolWindowManager.getToolWindow("Nacos Search")
            
            toolWindow?.let {
                refreshToolWindowContent(it, project)
            }
        }
    }
    
    /**
     * Refresh content of a specific tool window
     */
    private fun refreshToolWindowContent(toolWindow: ToolWindow, project: Project) {
        toolWindow.contentManager.contents.forEach { content ->
            val component = content.component
            if (component is LanguageAwareComponent) {
                component.onLanguageChanged(component.getLanguageService().getCurrentLanguage())
            }
        }
    }
    
    /**
     * Refresh application-level components
     */
    private fun refreshApplicationComponents() {
        // Refresh settings if they're open
        refreshSettingsDialog()
        
        // Refresh any other application-level components
        refreshNamespaceService()
    }
    
    /**
     * Refresh settings dialog if open
     */
    private fun refreshSettingsDialog() {
        // This would typically be handled by the configurable itself
        // For now, we'll rely on the settings being reloaded when reopened
    }
    
    /**
     * Refresh namespace service labels
     */
    private fun refreshNamespaceService() {
        val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
        // The namespace service already uses the bundle for messages
        // so it will automatically use the new language when next called
    }
    
    /**
     * Force refresh of all bundle messages
     */
    fun refreshBundleMessages() {
        // This is a bit tricky with IntelliJ's bundle system
        // The bundle automatically uses the appropriate locale based on the system
        // We need to restart the plugin or force reinitialization
        
        // For now, we'll rely on components being refreshed and using the bundle again
        // which will pick up the new language setting
    }
    
    /**
     * Show a notification about language change
     */
    fun showLanguageChangeNotification(newLanguage: LanguageService.SupportedLanguage) {
        val message = NacosSearchBundle.message(
            "language.changed.notification", 
            newLanguage.displayName
        )
        
        ApplicationManager.getApplication().invokeLater {
            // Show a simple message dialog for now
            // In a real implementation, you might use IntelliJ's notification system
            javax.swing.JOptionPane.showMessageDialog(
                null,
                message,
                NacosSearchBundle.message("language.changed.title"),
                javax.swing.JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
}