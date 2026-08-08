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
 * (blue↔gray flicker). The same entry is used when a marker input changes
 * without a cache mutation — selected Namespace, environment, or Find Usages
 * navigation into code — so those paths share the coalescing rather than
 * restarting the analyzer synchronously (issue #193).
 */
@Service(Service.Level.APP)
class NavigationIndexRefreshService internal constructor(
    private val scheduleOnEdt: (() -> Unit) -> Unit,
    private val restartDaemon: (Project) -> Unit,
    private val openProjects: () -> Array<Project>
) {
    constructor() : this(
        scheduleOnEdt = { action ->
            invokeOnEdt(ModalityState.defaultModalityState(), action)
        },
        restartDaemon = { target -> DaemonCodeAnalyzer.getInstance(target).restart() },
        openProjects = { ProjectManager.getInstance().openProjects }
    )

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
     * until the next manual edit — and when a non-cache marker input changes
     * (Namespace / environment / Find Usages navigation) so open files re-rank
     * against the new inputs without a remote read (issue #193).
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

        scheduleOnEdt {
            val batch: List<Project?>
            synchronized(scheduleLock) {
                batch = pendingProjects.toList()
                pendingProjects.clear()
                restartQueued = false
            }
            val targets = linkedSetOf<Project>()
            for (requested in batch) {
                if (requested == null) {
                    openProjects().forEach { targets.add(it) }
                } else {
                    targets.add(requested)
                }
            }
            targets.forEach { target ->
                if (!target.isDefault && !target.isDisposed) {
                    restartDaemon(target)
                }
            }
        }
    }
}
