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

/** Bridges non-blocking gutter observations to the Namespace single-flight coordinator. */
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
     */
    fun requestIfNeeded(identity: AccessIdentity, namespaceId: String, project: Project?) {
        val state = cacheService.namespaceIndexState(identity, namespaceId)
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
