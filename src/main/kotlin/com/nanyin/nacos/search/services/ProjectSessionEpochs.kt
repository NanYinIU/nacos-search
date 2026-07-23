package com.nanyin.nacos.search.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Project-scoped facade over [SessionEpochRegistry]. UI and services bump the
 * epoch whenever profile, namespace, access/profile revision, or resolved
 * generation changes for this project.
 */
@Service(Service.Level.PROJECT)
class ProjectSessionEpochs(private val project: Project) {
    private val registry = SessionEpochRegistry()
    private val fence = OperationFence(registry)

    val projectId: String
        get() = project.locationHash

    fun currentEpoch(): Long = registry.currentEpoch(projectId)

    fun bump(): Long = registry.bump(projectId)

    fun capture(identity: com.nanyin.nacos.search.models.AccessIdentity): OperationTicket =
        registry.capture(projectId, identity)

    fun fence(): OperationFence = fence

    fun registry(): SessionEpochRegistry = registry

    companion object {
        /** Bumps the session epoch for every open project (settings / profile changes). */
        fun bumpAllOpenProjects() {
            try {
                ProjectManager.getInstance().openProjects.forEach { openProject ->
                    if (!openProject.isDisposed) {
                        openProject.getService(ProjectSessionEpochs::class.java)?.bump()
                    }
                }
            } catch (_: Exception) {
                // Outside a live IDE (unit tests) ProjectManager may be unavailable.
            }
        }
    }
}
