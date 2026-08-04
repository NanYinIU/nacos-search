package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NamespaceService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a configuration placeholder key to the Nacos configurations that
 * define it, by searching the locally cached configurations.
 *
 * Pure with respect to PSI — it only reads [CacheService]. Results are ordered
 * so that the most relevant definition (active namespace > public > others)
 * comes first, matching how a developer would expect "go to declaration" to
 * behave when the same key exists in several places.
 *
 * All cache reads use the caller's [AccessIdentity]; there is no server-URL
 * key-space fallback (issue #62).
 */
object NacosKeyResolver {

    data class KeyHit(
        val config: NacosConfiguration,
        val location: ConfigKeyExtractor.KeyLocation,
        val freshness: CacheService.DetailFreshness = CacheService.DetailFreshness.FRESH,
        val freshUntilMillis: Long = Long.MAX_VALUE,
        val deepStaleAtMillis: Long = Long.MAX_VALUE
    ) {
        val namespaceId: String get() = config.tenantId ?: ""

        fun atTime(now: Long): KeyHit = copy(
            freshness = when {
                now <= freshUntilMillis -> CacheService.DetailFreshness.FRESH
                now <= deepStaleAtMillis -> CacheService.DetailFreshness.STALE
                else -> CacheService.DetailFreshness.DEEP_STALE
            }
        )
    }

    data class KeyIndex(
        val cacheIdentity: Int,
        val accessIdentity: AccessIdentity,
        val cacheModificationCount: Long,
        val hitsByKey: Map<String, List<KeyHit>>,
        val dataIdsByNamespace: Map<String, Set<String>> = emptyMap()
    )

    @Volatile
    private var cachedIndex: KeyIndex? = null

    internal fun resolveStatus(key: String, index: KeyIndex?): ConfigResolution {
        if (index == null) return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        return resolutionFromHits(index.hitsByKey[key].orEmpty())
    }

    private fun resolutionFromHits(hits: List<KeyHit>): ConfigResolution {
        return ConfigResolution(
            when {
                hits.isEmpty() -> ConfigReferenceStatus.UNRESOLVED
                hits.any { it.freshness == CacheService.DetailFreshness.FRESH } -> ConfigReferenceStatus.RESOLVED
                else -> ConfigReferenceStatus.STALE
            },
            hits
        )
    }

    /**
     * Resolves [key] against every cached configuration for [activeIdentity].
     *
     * @param activeNamespaceId when non-null, hits in this namespace sort first
     * @param preferredDataId when present and any hit matches, hard-filter to those
     *   hits (typical `@NacosPropertySource`); otherwise soft-rank that dataId first
     */
    fun resolve(
        key: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeNamespaceId: String? = currentNamespaceId(),
        preferredGroup: String? = null,
        preferredNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true,
        activeIdentity: AccessIdentity? = null
    ): List<KeyHit> {
        if (key.isBlank()) return emptyList()
        val index = currentIndex(cacheService, activeIdentity) ?: return emptyList()
        val now = cacheService.cacheTimeMillis()
        val scoped = index.hitsByKey[key]
            .orEmpty()
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
        return preferDataIdHits(scoped, preferredDataId)
            .sortedWith(hitComparator(activeNamespaceId, preferredGroup, preferredNamespaceId, preferredDataId))
            .map { it.atTime(now) }
    }

    fun resolveCurrentState(
        key: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeNamespaceId: String? = currentNamespaceId(),
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true,
        activeIdentity: AccessIdentity? = null
    ): ConfigResolution {
        if (key.isBlank()) return ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        val index = currentIndex(cacheService, activeIdentity)
            ?: return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        val scoped = index.hitsByKey[key]
            .orEmpty()
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
        val hits = preferDataIdHits(scoped, preferredDataId)
            .map { it.atTime(cacheService.cacheTimeMillis()) }
        if (hits.isNotEmpty()) return resolutionFromHits(hits)

        // No detail hit for the key. When a preferred data id is declared, the
        // complete namespace summary index decides absence (UNRESOLVED) vs
        // "exists but body not loaded" (UNDECIDABLE) — issue #52 / ADR-0041.
        val dataId = preferredDataId?.takeIf { it.isNotBlank() }
        if (dataId != null) {
            return dataIdPresenceResolution(
                dataId = dataId,
                cacheService = cacheService,
                activeNamespaceId = activeNamespaceId,
                activeIdentity = activeIdentity
            )
        }
        return resolutionFromHits(hits)
    }

    /**
     * Classifies a preferred data id against the authoritative namespace summary
     * index when no key hit exists in the detail-backed key index.
     */
    internal fun dataIdPresenceResolution(
        dataId: String,
        cacheService: CacheService,
        activeNamespaceId: String?,
        activeIdentity: AccessIdentity?
    ): ConfigResolution {
        val identity = activeIdentity
            ?: return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        val namespaceState = cacheService.namespaceIndexState(identity, activeNamespaceId)
            ?: return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        if (namespaceState.freshness != CacheService.DetailFreshness.FRESH ||
            !namespaceState.authoritativeForAbsence
        ) {
            return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        }
        return if (dataId in namespaceState.dataIds) {
            ConfigResolution(ConfigReferenceStatus.UNDECIDABLE, emptyList())
        } else {
            ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        }
    }

    /**
     * Declared dataId with cache hits → hard constraint; otherwise keep all hits
     * so a stale/wrong PropertySource still degrades to soft discovery.
     */
    internal fun preferDataIdHits(hits: List<KeyHit>, preferredDataId: String?): List<KeyHit> {
        val dataId = preferredDataId?.takeIf { it.isNotBlank() } ?: return hits
        val matched = hits.filter { it.config.dataId == dataId }
        return matched.ifEmpty { hits }
    }

    fun hasKey(
        key: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        allowCrossNamespace: Boolean = true,
        activeNamespaceId: String? = currentNamespaceId(),
        activeIdentity: AccessIdentity? = null
    ): Boolean {
        if (key.isBlank()) return false
        val hits = currentIndex(cacheService, activeIdentity)?.hitsByKey?.get(key) ?: return false
        return if (allowCrossNamespace) hits.isNotEmpty()
        else hits.any { sameNamespace(it.namespaceId, activeNamespaceId) }
    }

    /**
     * Returns true when [dataId] is known to exist among the cached
     * configurations for [activeIdentity].
     *
     * When no index has been built yet, or the cache is empty (cold start /
     * namespace not yet loaded), this returns true optimistically so the
     * unresolved gutter marker can still appear and drive a lazy remote fetch.
     * Once the cache is populated but the dataId is absent, returns false so
     * the LineMarkerProvider hides the dead-end marker.
     */
    fun isDataIdKnown(
        dataId: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeNamespaceId: String? = currentNamespaceId(),
        activeIdentity: AccessIdentity? = null
    ): Boolean {
        if (dataId.isBlank()) return false
        val identity = activeIdentity ?: return true
        val normalizedNamespace = normalizeNamespaceId(activeNamespaceId)
        val index = currentIndex(cacheService, identity)
        if (dataId in index?.dataIdsByNamespace?.get(normalizedNamespace).orEmpty()) return true

        val namespaceState = cacheService.namespaceIndexState(identity, activeNamespaceId)
            ?: return true
        if (namespaceState.freshness != CacheService.DetailFreshness.FRESH ||
            !namespaceState.authoritativeForAbsence
        ) return true
        return dataId in namespaceState.dataIds
    }

    /**
     * Order: active namespace first, then public, then everything else
     * (stable within a tier by dataId/group).
     */
    private fun hitComparator(
        activeNamespaceId: String?,
        preferredGroup: String?,
        preferredNamespaceId: String?,
        preferredDataId: String? = null
    ): Comparator<KeyHit> {
        val active = activeNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        val group = preferredGroup?.takeIf { it.isNotBlank() }
        val namespace = preferredNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        val dataId = preferredDataId?.takeIf { it.isNotBlank() }
        return Comparator { a, b ->
            compareValuesBy(
                a,
                b,
                { namespaceTier(it, active, namespace) },
                { groupTier(it, group) },
                { dataIdTier(it, dataId) },
                { it.config.dataId },
                { it.config.group }
            )
        }
    }

    private fun dataIdTier(hit: KeyHit, preferredDataId: String?): Int =
        if (preferredDataId != null && hit.config.dataId == preferredDataId) 0 else 1

    private fun namespaceTier(hit: KeyHit, activeNamespaceId: String?, preferredNamespaceId: String?): Int = when {
        preferredNamespaceId != null && hit.namespaceId == preferredNamespaceId -> 0
        preferredNamespaceId != null -> 1
        activeNamespaceId != null && hit.namespaceId == activeNamespaceId -> 0
        hit.config.tenantId.isNullOrBlank() || hit.namespaceId == "public" -> 1
        else -> 2
    }

    private fun groupTier(hit: KeyHit, preferredGroup: String?): Int =
        if (preferredGroup != null && hit.config.group == preferredGroup) 0 else 1

    private fun sameNamespace(hitNamespaceId: String?, activeNamespaceId: String?): Boolean =
        normalizeNamespaceId(hitNamespaceId) == normalizeNamespaceId(activeNamespaceId)

    private fun normalizeNamespaceId(namespaceId: String?): String =
        namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: ""

    private val building = AtomicBoolean(false)

    /**
     * Returns only volatile in-memory state. A stale index queues a memory-only
     * refresh in CacheService's lifecycle-owned scope, but PSI never waits for it.
     *
     * Equality is solely on [AccessIdentity] — the dual server-URL check that
     * existed for the legacy factory's sentinel values is gone (issue #62).
     */
    private fun currentIndex(
        cacheService: CacheService,
        activeIdentity: AccessIdentity?
    ): KeyIndex? {
        if (activeIdentity == null) return null
        val modificationCount = cacheService.getModificationCount()
        val cacheIdentity = System.identityHashCode(cacheService)
        val existing = cachedIndex
        if (existing != null &&
            existing.cacheIdentity == cacheIdentity &&
            existing.accessIdentity == activeIdentity &&
            existing.cacheModificationCount == modificationCount
        ) {
            return existing
        }
        scheduleAsyncRebuild(cacheService, activeIdentity)
        return existing?.takeIf {
            it.cacheIdentity == cacheIdentity &&
                it.accessIdentity == activeIdentity
        }
    }

    /**
     * Explicitly refreshes the derived index from CacheService's immutable,
     * memory-only snapshot. This hook is deterministic for tests and callers
     * that have already moved off the PSI hot path.
     */
    fun refreshIndex(
        cacheService: CacheService,
        activeIdentity: AccessIdentity
    ): KeyIndex {
        val cached = cacheService.configurationNavigationSnapshot(activeIdentity)
        val built = KeyIndex(
            cacheIdentity = System.identityHashCode(cacheService),
            accessIdentity = activeIdentity,
            cacheModificationCount = cacheService.getModificationCount(),
            hitsByKey = cached
                .asSequence()
                .flatMap { cachedConfig ->
                    ConfigKeyExtractor.extract(cachedConfig.configuration).values.asSequence().map { loc ->
                        loc.key to KeyHit(
                            cachedConfig.configuration,
                            loc,
                            cachedConfig.freshness,
                            cachedConfig.freshUntilMillis,
                            cachedConfig.deepStaleAtMillis
                        )
                    }
                }
                .groupBy({ it.first }, { it.second }),
            dataIdsByNamespace = cached
                .asSequence()
                .filter { it.configuration.dataId.isNotBlank() }
                .groupBy { normalizeNamespaceId(it.configuration.tenantId) }
                .mapValues { (_, entries) -> entries.mapTo(mutableSetOf()) { it.configuration.dataId } }
        )
        cachedIndex = built
        return built
    }

    /**
     * Ensures the index is built for [activeIdentity] without blocking the
     * caller. Intended to be invoked from application / project startup so the
     * index is warm before the first LineMarker pass.
     */
    fun ensureIndexBuilt(
        cacheService: CacheService,
        activeIdentity: AccessIdentity
    ) {
        scheduleAsyncRebuild(cacheService, activeIdentity)
    }

    private fun scheduleAsyncRebuild(
        cacheService: CacheService,
        activeIdentity: AccessIdentity
    ) {
        if (!building.compareAndSet(false, true)) return
        cacheService.launchSnapshotRefresh {
            try {
                refreshIndex(cacheService, activeIdentity)
            } finally {
                building.set(false)
            }
        }
    }

    private fun currentNamespaceId(): String? =
        try {
            ApplicationManager.getApplication()
                ?.getService(NamespaceService::class.java)
                ?.getCurrentNamespace()?.namespaceId
        } catch (e: Exception) {
            null
        }
}
