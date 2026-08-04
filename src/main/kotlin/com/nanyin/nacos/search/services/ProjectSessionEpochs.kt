package com.nanyin.nacos.search.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.operations.GenerationSessionCapture
import com.nanyin.nacos.search.services.operations.SessionGenerationState

/**
 * Project-scoped facade over [SessionEpochRegistry] and the in-memory resolved
 * API generation for this project session.
 *
 * The resolved generation lives only here — it is never written to
 * [com.nanyin.nacos.search.settings.NacosProjectSession] persistent state.
 * Assigning a *different* generation advances the session epoch (ADR-0010).
 * The separately persisted last-known generation (ADR-0034) is unrelated.
 */
@Service(Service.Level.PROJECT)
class ProjectSessionEpochs(private val project: Project) {
    private val registry = SessionEpochRegistry()
    private val fence = OperationFence(registry)

    private val tombstoneCheck: (String) -> Boolean = { profileId ->
        try {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ProfileTombstoneRegistry::class.java)
                ?.isEntombed(
                    com.nanyin.nacos.search.models.AccessIdentity.ofProfile(
                        profileId = profileId,
                        accessRevision = 0,
                        canonicalEndpoint = "<any>",
                        resolvedGeneration = NacosApiGeneration.UNKNOWN,
                        authMode = com.nanyin.nacos.search.settings.AuthMode.ANONYMOUS,
                        principal = "<anonymous>"
                    )
                ) == true
        } catch (_: Exception) {
            false
        }
    }

    private val generationState = SessionGenerationState(
        currentEpoch = { registry.currentEpoch(projectId) },
        isEntombed = tombstoneCheck,
        onGenerationChanged = { registry.bump(projectId); Unit }
    )

    val projectId: String
        get() = project.locationHash

    fun currentEpoch(): Long = registry.currentEpoch(projectId)

    fun bump(): Long = registry.bump(projectId)

    fun capture(identity: com.nanyin.nacos.search.models.AccessIdentity): OperationTicket =
        registry.capture(projectId, identity)

    fun fence(): OperationFence = fence

    fun registry(): SessionEpochRegistry = registry

    /** In-memory session generation owner for this project. Never persisted. */
    fun generationState(): SessionGenerationState = generationState

    fun resolvedGenerationIfCompatible(
        profileId: String,
        profileRevision: Long,
        accessRevision: Long
    ): NacosApiGeneration? =
        generationState.resolvedIfCompatible(profileId, profileRevision, accessRevision)

    fun tryCommitResolvedGeneration(
        generation: NacosApiGeneration,
        capture: GenerationSessionCapture,
        live: SessionGenerationState.LiveIdentityKeys
    ): Boolean = generationState.tryCommit(generation, capture, live)

    fun clearResolvedGeneration() {
        generationState.clear()
    }

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

        /**
         * Locates a project session whose selected profile matches [profileId].
         * Used by the app-level read module so formal AUTO detection can store
         * the result at project-session scope without threading Project through
         * every call site.
         *
         * Returns null when no open project has selected [profileId] — never
         * falls back to an arbitrary project (ADR-0004: sessions are per-project).
         */
        fun findForProfile(profileId: String): ProjectSessionEpochs? {
            if (profileId.isBlank()) return null
            return try {
                ProjectManager.getInstance().openProjects
                    .asSequence()
                    .filter { !it.isDisposed }
                    .mapNotNull { project ->
                        val session = project.getService(
                            com.nanyin.nacos.search.settings.NacosProjectSession::class.java
                        ) ?: return@mapNotNull null
                        val epochs = project.getService(ProjectSessionEpochs::class.java)
                            ?: return@mapNotNull null
                        if (session.sessionState.selectedProfileId == profileId) epochs else null
                    }
                    .firstOrNull()
            } catch (_: Exception) {
                null
            }
        }
    }
}
