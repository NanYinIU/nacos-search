package com.nanyin.nacos.search.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.nanyin.nacos.search.psi.NacosKeyIndexService
import com.nanyin.nacos.search.models.AccessIdentity
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.invokeOnEdt
/** Publishes cache changes to code navigation and requests a fresh gutter pass. */
@Service(Service.Level.APP)
class NavigationIndexRefreshService {
    fun refresh(identity: AccessIdentity, project: Project?) {
        val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        ApplicationManager.getApplication()
            .getService(NacosKeyIndexService::class.java)
            .refreshIndex(cacheService.snapshot(identity))

        invokeOnEdt(ModalityState.defaultModalityState()) {
            val projects = if (project == null) {
                com.intellij.openapi.project.ProjectManager.getInstance().openProjects.asList()
            } else {
                listOf(project)
            }
            projects.forEach { target ->
                if (!target.isDefault && !target.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(target).restart()
                }
            }
        }
    }
}
