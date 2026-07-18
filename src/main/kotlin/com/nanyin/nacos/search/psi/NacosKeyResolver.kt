package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.settings.NacosSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a configuration placeholder key to the Nacos configurations that
 * define it, by searching the locally cached configurations.
 *
 * Pure with respect to PSI — it only reads [CacheService]. Results are ordered
 * so that the most relevant definition (active namespace > public > others)
 * comes first, matching how a developer would expect "go to declaration" to
 * behave when the same key exists in several places.
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
       val serverUrl: String?,
       val accessIdentity: AccessIdentity? = null,
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
     * Resolves [key] against every cached configuration.
     *
     * @param activeNamespaceId when non-null, hits in this namespace sort first
     */
    fun resolve(
        key: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = null,
        activeNamespaceId: String? = currentNamespaceId(),
        preferredGroup: String? = null,
        preferredNamespaceId: String? = null,
        allowCrossNamespace: Boolean = true,
        activeIdentity: AccessIdentity? = null
    ): List<KeyHit> {
        if (key.isBlank()) return emptyList()
        val index = currentIndex(cacheService, activeServerUrl, activeIdentity) ?: return emptyList()
        val now = cacheService.cacheTimeMillis()
        return index.hitsByKey[key]
            .orEmpty()
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
            .sortedWith(hitComparator(activeNamespaceId, preferredGroup, preferredNamespaceId))
            .map { it.atTime(now) }
    }

    fun resolveCurrentState(
        key: String,
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = null,
        activeNamespaceId: String? = currentNamespaceId(),
        allowCrossNamespace: Boolean = true,
        activeIdentity: AccessIdentity? = null
    ): ConfigResolution {
        if (key.isBlank()) return ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        val index = currentIndex(cacheService, activeServerUrl, activeIdentity)
            ?: return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        val hits = index.hitsByKey[key]
            .orEmpty()
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
            .map { it.atTime(cacheService.cacheTimeMillis()) }
        return resolutionFromHits(hits)
    }

   fun hasKey(
       key: String,
       cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
       activeServerUrl: String? = null,
       allowCrossNamespace: Boolean = true,
       activeNamespaceId: String? = currentNamespaceId(),
       activeIdentity: AccessIdentity? = null
   ): Boolean {
       if (key.isBlank()) return false
       val hits = currentIndex(cacheService, activeServerUrl, activeIdentity)?.hitsByKey?.get(key) ?: return false
       return if (allowCrossNamespace) hits.isNotEmpty()
              else hits.any { sameNamespace(it.namespaceId, activeNamespaceId) }
   }

   /**
    * Returns true when [dataId] is known to exist among the cached
    * configurations for [activeServerUrl].
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
       activeServerUrl: String? = null,
       activeNamespaceId: String? = currentNamespaceId(),
       activeIdentity: AccessIdentity? = null
   ): Boolean {
       if (dataId.isBlank()) return false
       val normalizedNamespace = normalizeNamespaceId(activeNamespaceId)
       val index = currentIndex(cacheService, activeServerUrl, activeIdentity)
       if (dataId in index?.dataIdsByNamespace?.get(normalizedNamespace).orEmpty()) return true

       val namespaceState = activeIdentity?.let { cacheService.namespaceIndexState(it, activeNamespaceId) }
           ?: cacheService.namespaceIndexState(activeServerUrl.orEmpty(), activeNamespaceId)
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
        preferredNamespaceId: String?
    ): Comparator<KeyHit> {
        val active = activeNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        val group = preferredGroup?.takeIf { it.isNotBlank() }
        val namespace = preferredNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        return Comparator { a, b ->
            compareValuesBy(
                a,
                b,
                { namespaceTier(it, active, namespace) },
                { groupTier(it, group) },
                { it.config.dataId },
                { it.config.group }
            )
        }
    }

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
     */
    private fun currentIndex(
        cacheService: CacheService,
        activeServerUrl: String?,
        activeIdentity: AccessIdentity?
    ): KeyIndex? {
        val normalizedServerUrl = normalizeServerUrl(activeIdentity?.serverId ?: activeServerUrl)
        val modificationCount = cacheService.getModificationCount()
        val cacheIdentity = System.identityHashCode(cacheService)
        val existing = cachedIndex
        if (existing != null &&
            existing.cacheIdentity == cacheIdentity &&
            existing.serverUrl == normalizedServerUrl &&
            existing.accessIdentity == activeIdentity &&
            existing.cacheModificationCount == modificationCount
        ) {
            return existing
        }
        scheduleAsyncRebuild(cacheService, normalizedServerUrl, activeIdentity)
        return existing?.takeIf {
            it.cacheIdentity == cacheIdentity &&
                it.serverUrl == normalizedServerUrl &&
                it.accessIdentity == activeIdentity
        }
    }

    /**
     * Explicitly refreshes the derived index from CacheService's immutable,
     * memory-only snapshot. This hook is deterministic for tests and callers
     * that have already moved off the PSI hot path.
     */
    fun refreshIndex(
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = currentServerUrl(),
        activeIdentity: AccessIdentity? = null
    ): KeyIndex {
        val normalizedServerUrl = normalizeServerUrl(activeIdentity?.serverId ?: activeServerUrl)
        val cached = activeIdentity?.let(cacheService::configurationNavigationSnapshot)
            ?: cacheService.configurationNavigationSnapshot(normalizedServerUrl)
       val built = KeyIndex(
           cacheIdentity = System.identityHashCode(cacheService),
           serverUrl = normalizedServerUrl,
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

    fun refreshIndex(cacheService: CacheService, activeIdentity: AccessIdentity): KeyIndex =
        refreshIndex(cacheService, activeIdentity.serverId, activeIdentity)

    /**
     * Ensures the index is built for [activeServerUrl] without blocking the
     * caller. Intended to be invoked from application / project startup so the
     * index is warm before the first LineMarker pass.
     */
    fun ensureIndexBuilt(
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = currentServerUrl(),
        activeIdentity: AccessIdentity? = null
    ) {
        scheduleAsyncRebuild(
            cacheService,
            normalizeServerUrl(activeIdentity?.serverId ?: activeServerUrl),
            activeIdentity
        )
    }

    fun ensureIndexBuilt(cacheService: CacheService, activeIdentity: AccessIdentity) {
        scheduleAsyncRebuild(cacheService, normalizeServerUrl(activeIdentity.serverId), activeIdentity)
    }

    private fun scheduleAsyncRebuild(
        cacheService: CacheService,
        normalizedServerUrl: String?,
        activeIdentity: AccessIdentity?
    ) {
        if (!building.compareAndSet(false, true)) return
        cacheService.launchSnapshotRefresh {
            try {
                refreshIndex(cacheService, normalizedServerUrl, activeIdentity)
            } finally {
                building.set(false)
            }
        }
    }

    private fun normalizeServerUrl(serverUrl: String?): String? =
        serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    @Deprecated("Use refreshIndex; this compatibility alias no longer blocks")
    fun rebuildBlocking(cacheService: CacheService, activeServerUrl: String?): KeyIndex =
        refreshIndex(cacheService, activeServerUrl)

    private fun currentNamespaceId(): String? =
        try {
            ApplicationManager.getApplication()
                ?.getService(NamespaceService::class.java)
                ?.getCurrentNamespace()?.namespaceId
        } catch (e: Exception) {
            null
        }

    fun currentServerUrl(): String? =
        try {
            ApplicationManager.getApplication()
                ?.getService(NacosSettings::class.java)
                ?.serverUrl
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
}
