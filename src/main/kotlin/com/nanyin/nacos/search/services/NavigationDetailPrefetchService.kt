package com.nanyin.nacos.search.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.indexing.FileBasedIndex
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.psi.NacosDeclaredSourceIndex
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.navigationDetailPrefetchEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Independent flight that populates the identity-scoped detail cache for the
 * data ids a project declares through configuration-source annotations
 * (ADR-0041 / ADR-0043).
 *
 * Freshness is keyed by [AccessIdentity] + project location hash, never by the
 * namespace index. Results land in the ordinary configuration detail coordinate
 * so two projects on the same access identity share whatever either has already
 * fetched. Failure lowers only code-reference decidability.
 *
 * The enable toggle is read at trigger time (ADR-0042), so the flight observes
 * cancellation / disable itself rather than capturing the preference into the
 * operation context.
 */
@Service(Service.Level.APP)
class NavigationDetailPrefetchService internal constructor(
    private val apiService: NacosApiService,
    private val cacheService: CacheService,
    private val settings: NacosSettings,
    private val scope: CoroutineScope,
    private val declaredSourceLookup: (Project) -> Set<String>,
    private val onDetailsFetched: (AccessIdentity, Project) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : Disposable {

    constructor() : this(
        ApplicationManager.getApplication().getService(NacosApiService::class.java),
        ApplicationManager.getApplication().getService(CacheService::class.java),
        ApplicationManager.getApplication().getService(NacosSettings::class.java),
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ::lookupDeclaredDataIds,
        { identity, project ->
            ApplicationManager.getApplication()
                .getService(NavigationIndexRefreshService::class.java)
                .refresh(identity, project)
        }
    )

    private val logger = thisLogger()
    private val flightMutex = Mutex()
    private val inFlight = ConcurrentHashMap<PrefetchKey, Deferred<PrefetchOutcome>>()
    private val lastSuccessAt = ConcurrentHashMap<PrefetchKey, Long>()
    private val lastDeclaredFingerprint = ConcurrentHashMap<PrefetchKey, Int>()

    /**
     * Explicit, project-scoped trigger. Always returns a [Deferred] when the
     * toggle is on so callers (and tests) can await coverage; returns null
     * when the preference disables prefetch entirely.
     */
    fun requestPrefetch(
        project: Project,
        identity: AccessIdentity,
        operationContext: NacosOperationContext,
        namespaceId: String?
    ): Deferred<PrefetchOutcome>? {
        // Toggle is read at trigger time — not captured into the operation
        // context — so a mid-flight disable can still stop later detail calls.
        if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) {
            return null
        }
        val key = PrefetchKey(identity, projectLocationHash(project))
        return scope.async {
            val work = flightMutex.withLock {
                val existing = inFlight[key]
                if (existing != null && existing.isActive) {
                    existing
                } else {
                    // Own the flight on the service scope so joiners outlive the
                    // caller's deferred without cancelling the real work.
                    scope.async {
                        try {
                            executePrefetch(key, project, identity, operationContext, namespaceId)
                        } finally {
                            inFlight.remove(key)
                        }
                    }.also { inFlight[key] = it }
                }
            }
            work.await()
        }
    }

    /**
     * Non-blocking convenience used by startup / PSI: only starts work when the
     * freshness gate says the previous success for this identity+project is
     * outside the TTL window, or the project's declared-source set changed so a
     * newly declared data id is not stuck behind the prior success (ADR-0043).
     * Never chained to namespace-index completion.
     */
    fun requestIfNeeded(
        project: Project,
        identity: AccessIdentity,
        namespaceId: String?
    ) {
        if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) return
        val key = PrefetchKey(identity, projectLocationHash(project))
        val declared = declaredSourceLookup(project)
        val fingerprint = declaredFingerprint(declared)
        val last = lastSuccessAt[key]
        val sameDeclared = lastDeclaredFingerprint[key] == fingerprint
        if (last != null &&
            sameDeclared &&
            nowMillis() - last < settings.getCacheTtlMillis()
        ) return

        scope.launch {
            try {
                val context = withContext(Dispatchers.IO) {
                    settings.captureOperationContext(identity.profileId).getOrNull()
                } ?: return@launch
                // Re-check toggle after the off-EDT hop (ADR-0042).
                if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) return@launch
                if (!isActive) return@launch
                requestPrefetch(project, identity, context, namespaceId)
            } catch (e: Exception) {
                logger.debug("Navigation detail prefetch scheduling failed", e)
            }
        }
    }

    private suspend fun executePrefetch(
        key: PrefetchKey,
        project: Project,
        identity: AccessIdentity,
        operationContext: NacosOperationContext,
        namespaceId: String?
    ): PrefetchOutcome {
        if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) {
            return PrefetchOutcome.Disabled
        }
        coroutineContext.ensureActive()

        val declared = withContext(Dispatchers.Default) {
            declaredSourceLookup(project)
        }
        coroutineContext.ensureActive()
        if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) {
            return PrefetchOutcome.Disabled
        }

        val targets: List<PrefetchTarget>
        val usedFallback: Boolean
        if (declared.isNotEmpty()) {
            targets = resolveTargetsFromDeclared(declared, identity, namespaceId)
            usedFallback = false
        } else {
            targets = resolveFallbackTargets(identity, namespaceId, FALLBACK_BUDGET)
            usedFallback = true
        }

        if (targets.isEmpty()) {
            lastSuccessAt[key] = nowMillis()
            lastDeclaredFingerprint[key] = declaredFingerprint(declared)
            return PrefetchOutcome.Complete(
                requested = 0,
                fetched = 0,
                usedFallbackBudget = usedFallback,
                coverage = PrefetchCoverage(fetched = 0, expected = 0, complete = !usedFallback)
            )
        }

        val fetchedCount = AtomicInteger(0)
        try {
            coroutineScope {
                val semaphore = Semaphore(PREFETCH_CONCURRENCY)
                targets.map { target ->
                    async {
                        semaphore.withPermit {
                            // Observe cancel / disable between every detail call.
                            coroutineContext.ensureActive()
                            if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) {
                                return@withPermit null
                            }
                            // Identity-scoped detail cache: skip when already present.
                            // getConfiguration itself persists when cacheEnabled; we
                            // only skip network when a non-empty body is already there.
                            val existing = cacheService.getConfigDetail(
                                identity,
                                target.namespaceId,
                                target.dataId,
                                target.group,
                                allowStale = false
                            )
                            if (existing != null && existing.content.isNotBlank()) {
                                fetchedCount.incrementAndGet()
                                return@withPermit existing
                            }
                            apiService.getConfiguration(
                                dataId = target.dataId,
                                group = target.group,
                                namespaceId = target.namespaceId,
                                useCache = true,
                                operationContext = operationContext
                            ).getOrNull()?.let { observed ->
                                val config = observed.value ?: return@let null
                                // Prefer the write already performed by getConfiguration.
                                // Only fill the gap when the detail is still missing
                                // (cache disabled, or a test double that returns without
                                // persisting), under the read's own observation sequence.
                                val cached = cacheService.getConfigDetail(
                                    identity,
                                    target.namespaceId,
                                    target.dataId,
                                    target.group,
                                    allowStale = true
                                )
                                if (cached == null || cached.content.isBlank()) {
                                    cacheService.apply(
                                        CacheMutation.WriteDetail(
                                            identity,
                                            target.namespaceId,
                                            config,
                                            settings.getCacheTtlMillis()
                                        ),
                                        observed.observation
                                    )
                                }
                                fetchedCount.incrementAndGet()
                                config
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            return PrefetchOutcome.Cancelled(
                attempted = targets.size,
                fetched = fetchedCount.get()
            )
        }

        if (!settings.navigationDetailPrefetchEnabled(identity.profileId)) {
            return PrefetchOutcome.Disabled
        }

        val fetched = fetchedCount.get()
        lastSuccessAt[key] = nowMillis()
        lastDeclaredFingerprint[key] = declaredFingerprint(declared)
        val expected = targets.size
        // Fallback budget never claims full coverage of the namespace; declared
        // sources claim complete when every target was obtained.
        val complete = if (usedFallback) false else fetched >= expected
        if (fetched > 0) {
            onDetailsFetched(identity, project)
        }
        return PrefetchOutcome.Complete(
            requested = targets.size,
            fetched = fetched,
            usedFallbackBudget = usedFallback,
            coverage = PrefetchCoverage(
                fetched = fetched,
                expected = expected,
                complete = complete
            )
        )
    }

    private suspend fun resolveTargetsFromDeclared(
        declared: Set<String>,
        identity: AccessIdentity,
        namespaceId: String?
    ): List<PrefetchTarget> {
        val index = cacheService.getNamespaceIndex(identity, namespaceId, allowStale = true).orEmpty()
        val byDataId = index.groupBy { it.dataId }
        val defaultGroup = settings.cloneServers()
            .firstOrNull { it.id == identity.profileId }
            ?.defaultGroup
            ?.ifBlank { "DEFAULT_GROUP" }
            ?: "DEFAULT_GROUP"
        return declared.map { dataId ->
            val match = byDataId[dataId]?.firstOrNull()
            PrefetchTarget(
                dataId = dataId,
                group = match?.group ?: defaultGroup,
                namespaceId = match?.tenantId ?: namespaceId
            )
        }
    }

    private suspend fun resolveFallbackTargets(
        identity: AccessIdentity,
        namespaceId: String?,
        budget: Int
    ): List<PrefetchTarget> {
        val index = cacheService.getNamespaceIndex(identity, namespaceId, allowStale = true).orEmpty()
        return index.asSequence()
            .filter { it.dataId.isNotBlank() }
            .take(budget)
            .map { PrefetchTarget(it.dataId, it.group, it.tenantId ?: namespaceId) }
            .toList()
    }

    /** Test/inspection helper: clear freshness so the next requestIfNeeded runs. */
    internal fun clearFreshness() {
        lastSuccessAt.clear()
        lastDeclaredFingerprint.clear()
    }

    override fun dispose() {
        scope.cancel()
        inFlight.clear()
        lastSuccessAt.clear()
        lastDeclaredFingerprint.clear()
    }

    companion object {
        /** When a project declares no configuration sources, cap detail traffic. */
        const val FALLBACK_BUDGET: Int = 32
        private const val PREFETCH_CONCURRENCY: Int = 4

        fun projectLocationHash(project: Project): String =
            project.locationHash

        fun lookupDeclaredDataIds(project: Project): Set<String> {
            if (project.isDisposed || project.isDefault) return emptySet()
            return try {
                FileBasedIndex.getInstance()
                    .getAllKeys(NacosDeclaredSourceIndex.INDEX_ID, project)
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }
        }

        private fun declaredFingerprint(declared: Set<String>): Int =
            declared.sorted().hashCode()
    }
}

data class PrefetchKey(
    val identity: AccessIdentity,
    val projectLocationHash: String
)

data class PrefetchTarget(
    val dataId: String,
    val group: String,
    val namespaceId: String?
)

data class PrefetchCoverage(
    val fetched: Int,
    val expected: Int,
    val complete: Boolean
)

sealed interface PrefetchOutcome {
    data object Disabled : PrefetchOutcome
    data class Cancelled(val attempted: Int, val fetched: Int) : PrefetchOutcome
    data class Complete(
        val requested: Int,
        val fetched: Int,
        val usedFallbackBudget: Boolean,
        val coverage: PrefetchCoverage
    ) : PrefetchOutcome
}
