package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
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
        val location: ConfigKeyExtractor.KeyLocation
    ) {
        val namespaceId: String get() = config.tenantId ?: ""
    }

   data class KeyIndex(
       val cacheIdentity: Int,
       val serverUrl: String?,
       val cacheModificationCount: Long,
       val hitsByKey: Map<String, List<KeyHit>>,
       val dataIds: Set<String> = emptySet()
   )

   @Volatile
   private var cachedIndex: KeyIndex? = null

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
        preferredNamespaceId: String? = null
    ): List<KeyHit> {
        if (key.isBlank()) return emptyList()
        val index = currentIndex(cacheService, activeServerUrl) ?: return emptyList()
        return index.hitsByKey[key]
            .orEmpty()
            .sortedWith(hitComparator(activeNamespaceId, preferredGroup, preferredNamespaceId))
    }

   fun hasKey(
       key: String,
       cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
       activeServerUrl: String? = null
   ): Boolean {
       if (key.isBlank()) return false
       return currentIndex(cacheService, activeServerUrl)?.hitsByKey?.containsKey(key) ?: false
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
       activeServerUrl: String? = null
   ): Boolean {
       if (dataId.isBlank()) return false
       val index = currentIndex(cacheService, activeServerUrl) ?: return true
       if (index.dataIds.isEmpty()) return true
       return dataId in index.dataIds
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

    private val building = AtomicBoolean(false)

    /**
     * Returns the current key index, rebuilding only when safe:
     *  - in unit-test mode the (re)build is performed synchronously so tests
     *    that mutate the cache and query immediately see fresh results;
     *  - otherwise the query never blocks: a stale/missing index is returned
     *    as-is and a background rebuild is scheduled. This keeps the
     *    LineMarkerProvider / reference resolution off the slow path.
     */
    private fun currentIndex(cacheService: CacheService, activeServerUrl: String?): KeyIndex? {
        val normalizedServerUrl = normalizeServerUrl(activeServerUrl)
        val modificationCount = cacheService.getModificationCount()
        val cacheIdentity = System.identityHashCode(cacheService)
        val existing = cachedIndex
        if (existing != null &&
            existing.cacheIdentity == cacheIdentity &&
            existing.serverUrl == normalizedServerUrl &&
            existing.cacheModificationCount == modificationCount
        ) {
            return existing
        }
        return if (isUnitTestMode()) {
            rebuildBlocking(cacheService, normalizedServerUrl)
        } else {
            scheduleAsyncRebuild(cacheService, normalizedServerUrl)
            existing // stale, may be null on first call
        }
    }

    /**
     * Synchronously (re)builds the index. Safe to call off the highlighter /
     * dispatch thread (startup coroutines, background pool, tests). Never call
     * this from [NacosValueLineMarkerProvider] or reference resolution on the
     * hot path.
     */
    fun rebuildBlocking(
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = currentServerUrl()
    ): KeyIndex {
        val normalizedServerUrl = normalizeServerUrl(activeServerUrl)
        val cached = runBlocking { cacheService.getAllCachedConfigurations(normalizedServerUrl) }
       val built = KeyIndex(
           cacheIdentity = System.identityHashCode(cacheService),
           serverUrl = normalizedServerUrl,
           cacheModificationCount = cacheService.getModificationCount(),
           hitsByKey = cached
               .asSequence()
               .flatMap { config ->
                   ConfigKeyExtractor.extract(config).values.asSequence().map { loc ->
                       loc.key to KeyHit(config, loc)
                   }
               }
               .groupBy({ it.first }, { it.second }),
           dataIds = cached.asSequence().map { it.dataId }.filter { it.isNotBlank() }.toSet()
       )
        cachedIndex = built
        return built
    }

    /**
     * Ensures the index is built for [activeServerUrl] without blocking the
     * caller. Intended to be invoked from application / project startup so the
     * index is warm before the first LineMarker pass.
     */
    fun ensureIndexBuilt(
        cacheService: CacheService = ApplicationManager.getApplication().getService(CacheService::class.java),
        activeServerUrl: String? = currentServerUrl()
    ) {
        if (isUnitTestMode()) {
            rebuildBlocking(cacheService, activeServerUrl)
        } else {
            scheduleAsyncRebuild(cacheService, normalizeServerUrl(activeServerUrl))
        }
    }

    private fun scheduleAsyncRebuild(cacheService: CacheService, normalizedServerUrl: String?) {
        if (!building.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                rebuildBlocking(cacheService, normalizedServerUrl)
            } finally {
                building.set(false)
            }
        }
    }

    private fun normalizeServerUrl(serverUrl: String?): String? =
        serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }

    private fun isUnitTestMode(): Boolean =
        try {
            ApplicationManager.getApplication().isUnitTestMode
        } catch (e: Exception) {
            false
        }

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
