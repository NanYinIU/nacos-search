package com.nanyin.nacos.search.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.Edt
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings

internal data class RefreshSelection(
    val profileId: String,
    val namespaceId: String
)

internal fun refreshSelection(project: Project?): RefreshSelection? {
    project ?: return null
    val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    val session = project.getService(NacosProjectSession::class.java) ?: return null
    session.ensureInitialized(settings.migrationDefaults())
    val profileId = session.sessionState.selectedProfileId.takeIf { it.isNotBlank() } ?: return null
    val namespaceId = session.sessionState.namespaceId.takeIf { it.isNotBlank() } ?: return null
    return RefreshSelection(profileId, namespaceId)
}

/**
 * Action to refresh the Nacos configuration cache
 */
class RefreshCacheAction : AnAction(
    "Refresh Nacos Cache",
    "Refresh configuration cache from Nacos server",
    AllIcons.Actions.Refresh
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refreshing Nacos Cache", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to Nacos server..."
                indicator.isIndeterminate = true
                
                try {
                    val plugin = ApplicationManager.getApplication().getService(com.nanyin.nacos.search.NacosSearchPlugin::class.java)
                    val selection = refreshSelection(project) ?: return
                    val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
                    val result = settings.captureOperationContext(selection.profileId).fold(
                        onSuccess = { context ->
                            runBlocking {
                                plugin.refreshCache(selection.namespaceId, context, project)
                            }
                        },
                        onFailure = { Result.failure(it) }
                    )
                    indicator.checkCanceled()

                    Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
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
                    Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
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
        e.presentation.isEnabled = refreshSelection(e.project) != null
    }
}
