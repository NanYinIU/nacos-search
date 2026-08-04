package com.nanyin.nacos.search.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.NacosSettings

/**
 * Bridges non-blocking gutter observations to the Namespace single-flight
 * coordinator. After a successful index load it rebuilds the in-memory key
 * index view. The navigation detail prefetch is triggered independently on
 * the same PSI path (ADR-0043) and is never a continuation of index completion.
 */
@Service(Service.Level.APP)
class NamespaceIndexRefreshService internal constructor(
    private val requester: NamespaceIndexRequester,
    private val cacheService: CacheService,
    private val scope: CoroutineScope,
    private val afterRefresh: (NamespaceIndexRequest, Project?) -> Unit
) : Disposable {
    constructor() : this(
        ApplicationManager.getApplication().getService(NamespaceIndexCoordinator::class.java),
        ApplicationManager.getApplication().getService(CacheService::class.java),
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { request, project ->
            ApplicationManager.getApplication()
                .getService(NavigationIndexRefreshService::class.java)
                .refresh(request.key.identity, project)
        }
    )

    private val logger = thisLogger()

    /**
     * PSI gutter callbacks run on the EDT and must not read PasswordSafe. The
     * caller passes only the credential-free access identity (and namespace),
     * so the synchronous freshness check stays EDT-safe; the full request —
     * which may read the credential — is captured off-EDT inside the launched
     * coroutine (design §11/§19.7).
     *
     * The navigation detail prefetch is always requested independently for
     * [project] with its own freshness gate (ADR-0043), so a newly declared
     * data id is fetched without waiting for the namespace index TTL.
     */
    fun requestIfNeeded(identity: AccessIdentity, namespaceId: String, project: Project?) {
        // Independent flight — not gated on index freshness or completion.
        if (project != null && !project.isDisposed) {
            ApplicationManager.getApplication()
                .getService(NavigationDetailPrefetchService::class.java)
                .requestIfNeeded(project, identity, namespaceId)
        }

        val state = cacheService.snapshot(identity).namespaceIndex(namespaceId)
        if (state?.freshness == CacheService.DetailFreshness.FRESH) return

        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    settings.captureNamespaceIndexRequest(namespaceId)
                }
                when (requester.requestIndex(request, IndexTrigger.PSI)) {
                    is IndexOutcome.Complete, is IndexOutcome.Partial -> afterRefresh(request, project)
                    is IndexOutcome.Failed, is IndexOutcome.Stale -> Unit
                }
            } catch (e: Exception) {
                logger.debug("Background Namespace refresh failed", e)
            }
        }
    }

    private val settings: NacosSettings =
        ApplicationManager.getApplication().getService(NacosSettings::class.java)

    override fun dispose() {
        scope.cancel()
    }
}
