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
 * coordinator, for the one case a gutter pass cannot answer any narrower way:
 * a Namespace this identity has never indexed. After a successful index load it
 * rebuilds the in-memory key index view. The navigation detail prefetch is
 * triggered independently (ADR-0043) and is never a continuation of index
 * completion.
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
     * Capture uses [identity.profileId] so the write lands under the same key
     * space the freshness check inspected. Capturing the app-wide default
     * profile instead would leave this identity never FRESH and every gutter
     * pass would re-page the namespace. [captureNamespaceIndexRequest] also
     * applies the resolved-generation locator (ADR-0053) so an AUTO profile
     * whose session already resolved does not write into the UNKNOWN-
     * generation space while the freshness check looks in the resolved one
     * (issue #144).
     *
     * [afterRefresh] runs only on [IndexOutcome.Complete]: Partial never
     * publishes a FRESH authoritative index, so restarting the daemon after
     * Partial would re-enter this path and page again. Partial / Failed cool
     * down inside the coordinator's shared failure backoff (issue #145).
     *
     * A newly declared data id no longer waits on anything here: the marker that
     * declares it refreshes its own 配置坐标 through
     * [NavigationDetailPrefetchService.requestCoordinate] (ADR-0057), which is
     * why this entry point could become cold-start-only.
     */
    fun requestIfNeeded(identity: AccessIdentity, namespaceId: String, project: Project?) {
        // Cold start only (ADR-0057). A Namespace already indexed still answers
        // "does this data id exist" after its TTL, and re-paging it on expiry —
        // 100 rows a page, and under V1 every row's body rewritten — is what made
        // one gutter icon cost a full re-scan every five minutes. It is the rule
        // the startup preheat has followed since issue #147 (a persisted STALE
        // index counts), applied to the path that kept paying anyway. Re-paging
        // now belongs to the gestures that ask for it: manual refresh, Namespace
        // switch, search.
        //
        // Only a Namespace with no index at all leaves the question
        // unanswerable, and a failed load leaves none (the non-authoritative
        // mark only marks an index that exists), so cold retries still happen
        // under the coordinator's failure backoff.
        //
        // The bounded project-wide body warm sits inside the same gate. It is
        // still its own flight with its own gate (ADR-0043) — startup and manual
        // refresh trigger it directly — but on the PSI path a marker that can
        // name a coordinate now refreshes that coordinate itself, so there is
        // nothing project-wide left to add on a warm cache.
        if (cacheService.snapshot(identity).namespaceIndex(namespaceId) != null) return

        if (project != null && !project.isDisposed) {
            ApplicationManager.getApplication()
                .getService(NavigationDetailPrefetchService::class.java)
                .requestIfNeeded(project, identity, namespaceId)
        }

        scope.launch {
            try {
                val request = withContext(Dispatchers.IO) {
                    // Same profile the caller used for the freshness check —
                    // never resolveDefaultProfileId() here (multi-env mismatch).
                    // Prefer this project's session when the gutter named one,
                    // otherwise the app-level selected-profile locator (ADR-0053).
                    val locator = project?.takeUnless { it.isDisposed }
                        ?.let { ResolvedGenerationLocator.forProject(it) }
                        ?: ResolvedGenerationLocator.forSelectedProfile()
                    val context = settings.captureOperationContext(identity.profileId)
                        .getOrElse { throw it }
                    settings.captureNamespaceIndexRequest(namespaceId, context, locator)
                }
                when (requester.requestIndex(request, IndexTrigger.PSI)) {
                    is IndexOutcome.Complete -> afterRefresh(request, project)
                    is IndexOutcome.Partial,
                    is IndexOutcome.Failed,
                    is IndexOutcome.Stale -> Unit
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
