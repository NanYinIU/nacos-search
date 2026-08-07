package com.nanyin.nacos.search.services

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.nanyin.nacos.search.invokeOnEdt
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.psi.NacosKeyIndexService

/**
 * Publishes cache changes to code navigation and requests a fresh gutter pass.
 *
 * Daemon restarts are coalesced: detail confirm, namespace-index completion and
 * prefetch completion often finish within the same second, and each
 * [DaemonCodeAnalyzer.restart] clears then redraws `@NacosValue` gutters
 * (blue↔gray flicker).
 */
@Service(Service.Level.APP)
class NavigationIndexRefreshService {
    private val scheduleLock = Any()
    private val pendingProjects = linkedSetOf<Project?>()
    @Volatile
    private var restartQueued = false

    fun refresh(identity: AccessIdentity, project: Project?) {
        val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        ApplicationManager.getApplication()
            .getService(NacosKeyIndexService::class.java)
            .refreshIndex(cacheService.snapshot(identity))

        requestGutterPass(project)
    }

    /**
     * Coalesced [DaemonCodeAnalyzer.restart] without rebuilding the key index.
     * Used when an async index rebuild has already published — gutters that
     * analyzed against the previous empty/stale index would otherwise stay gray
     * until the next manual edit.
     */
    fun requestGutterPass(project: Project? = null) {
        queueDaemonRestart(project)
    }

    private fun queueDaemonRestart(project: Project?) {
        val shouldSchedule: Boolean
        synchronized(scheduleLock) {
            pendingProjects.add(project)
            shouldSchedule = !restartQueued
            if (shouldSchedule) restartQueued = true
        }
        if (!shouldSchedule) return

        invokeOnEdt(ModalityState.defaultModalityState()) {
            val batch: List<Project?>
            synchronized(scheduleLock) {
                batch = pendingProjects.toList()
                pendingProjects.clear()
                restartQueued = false
            }
            val targets = linkedSetOf<Project>()
            for (requested in batch) {
                if (requested == null) {
                    ProjectManager.getInstance().openProjects.forEach { targets.add(it) }
                } else {
                    targets.add(requested)
                }
            }
            targets.forEach { target ->
                if (!target.isDefault && !target.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(target).restart()
                }
            }
        }
    }
}
