package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheSnapshot

/**
 * Derives the `@NacosValue` key index from a [CacheSnapshot] and resolves a
 * placeholder key against it.
 *
 * Pure: every function here takes the snapshot and index it works from, holds
 * no state, and starts no work. [NacosKeyIndexService] owns the cached index
 * and the scope its rebuilds run in.
 *
 * Key extraction stays here, in the code-navigation layer. Reading keys out of
 * configuration content is configuration-format knowledge, and moving the
 * derivation into the cache would make the cache module depend on this one.
 *
 * Results are ordered so the most relevant definition (active namespace >
 * public > others) comes first, matching how a developer would expect
 * "go to declaration" to behave when the same key exists in several places.
 */
object NacosKeyResolver {

    /**
     * A key definition as the index holds it: timeless. Its freshness
     * boundaries are absolute instants, so an index built once stays usable as
     * the clock moves and nothing in it can report a freshness of its own.
     */
    data class KeyDefinition(
        val config: NacosConfiguration,
        val location: ConfigKeyExtractor.KeyLocation,
        val freshUntilMillis: Long = Long.MAX_VALUE,
        val deepStaleAtMillis: Long = Long.MAX_VALUE
    ) {
        val namespaceId: String get() = config.tenantId ?: ""

        fun judgedAt(asOfMillis: Long): KeyHit = KeyHit(
            config,
            location,
            when {
                asOfMillis <= freshUntilMillis -> CacheService.DetailFreshness.FRESH
                asOfMillis <= deepStaleAtMillis -> CacheService.DetailFreshness.STALE
                else -> CacheService.DetailFreshness.DEEP_STALE
            }
        )
    }

    /**
     * A key definition judged against one as-of instant. A hit cannot be built
     * without one, which is what keeps every hit in a single decision from
     * straddling a freshness boundary.
     */
    data class KeyHit(
        val config: NacosConfiguration,
        val location: ConfigKeyExtractor.KeyLocation,
        val freshness: CacheService.DetailFreshness
    ) {
        val namespaceId: String get() = config.tenantId ?: ""
    }

    /**
     * The derived index, stamped with the snapshot version it was built from.
     * That version — not a modification counter, a clock, or an object identity
     * — is the whole staleness rule.
     */
    data class KeyIndex(
        val accessIdentity: AccessIdentity,
        val version: Long,
        val definitionsByKey: Map<String, List<KeyDefinition>>,
        val dataIdsByNamespace: Map<String, Set<String>> = emptyMap()
    )

    fun buildIndex(snapshot: CacheSnapshot): KeyIndex {
        val cached = snapshot.configurations
        return KeyIndex(
            accessIdentity = snapshot.identity,
            version = snapshot.version,
            definitionsByKey = cached
                .asSequence()
                .flatMap { cachedConfig ->
                    ConfigKeyExtractor.extract(cachedConfig.configuration).values.asSequence().map { loc ->
                        loc.key to KeyDefinition(
                            cachedConfig.configuration,
                            loc,
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
    }

    /**
     * Resolves [key] against [index], judging every hit at [snapshot]'s as-of
     * instant.
     *
     * @param activeNamespaceId when non-null, hits in this namespace sort first
     * @param preferredDataId when present and any hit matches, hard-filter to those
     *   hits (typical `@NacosPropertySource`); otherwise soft-rank that dataId first
     */
    fun resolve(
        key: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null,
        preferredGroup: String? = null,
        preferredNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true
    ): List<KeyHit> {
        if (key.isBlank() || index == null) return emptyList()
        val scoped = scopedDefinitions(index, key, activeNamespaceId, allowCrossNamespace, preferredDataId)
        return scoped
            .sortedWith(definitionComparator(activeNamespaceId, preferredGroup, preferredNamespaceId, preferredDataId))
            .map { it.judgedAt(snapshot.asOfMillis) }
    }

    fun resolveCurrentState(
        key: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true
    ): ConfigResolution {
        if (key.isBlank()) return ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        if (index == null) return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        val hits = scopedDefinitions(index, key, activeNamespaceId, allowCrossNamespace, preferredDataId)
            .map { it.judgedAt(snapshot.asOfMillis) }
        if (hits.isNotEmpty()) return resolutionFromHits(hits)

        // No detail hit for the key. When a preferred data id is declared, the
        // complete namespace summary index decides absence (UNRESOLVED) vs
        // "exists but body not loaded" (UNDECIDABLE) — issue #52 / ADR-0041.
        val dataId = preferredDataId?.takeIf { it.isNotBlank() }
        if (dataId != null) {
            return dataIdPresenceResolution(dataId, snapshot, activeNamespaceId)
        }
        return resolutionFromHits(hits)
    }

    /**
     * Returns true when [dataId] is known to exist among the cached
     * configurations behind [snapshot].
     *
     * When no index has been built yet, or the cache is empty (cold start /
     * namespace not yet loaded), this returns true optimistically so the
     * unresolved gutter marker can still appear and drive a lazy remote fetch.
     * Only a fresh, complete namespace index may prove absence.
     */
    fun isDataIdKnown(
        dataId: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null
    ): Boolean {
        if (dataId.isBlank()) return false
        val normalizedNamespace = normalizeNamespaceId(activeNamespaceId)
        if (dataId in index?.dataIdsByNamespace?.get(normalizedNamespace).orEmpty()) return true

        val namespaceState = snapshot.namespaceIndex(activeNamespaceId) ?: return true
        if (namespaceState.freshness != CacheService.DetailFreshness.FRESH ||
            !namespaceState.authoritativeForAbsence
        ) return true
        return dataId in namespaceState.dataIds
    }

    /**
     * Classifies a preferred data id against the authoritative namespace summary
     * index when no key hit exists in the detail-backed key index.
     */
    internal fun dataIdPresenceResolution(
        dataId: String,
        snapshot: CacheSnapshot,
        activeNamespaceId: String?
    ): ConfigResolution {
        val namespaceState = snapshot.namespaceIndex(activeNamespaceId)
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

    internal fun resolutionFromHits(hits: List<KeyHit>): ConfigResolution {
        return ConfigResolution(
            when {
                hits.isEmpty() -> ConfigReferenceStatus.UNRESOLVED
                hits.any { it.freshness == CacheService.DetailFreshness.FRESH } -> ConfigReferenceStatus.RESOLVED
                else -> ConfigReferenceStatus.STALE
            },
            hits
        )
    }

    private fun scopedDefinitions(
        index: KeyIndex,
        key: String,
        activeNamespaceId: String?,
        allowCrossNamespace: Boolean,
        preferredDataId: String?
    ): List<KeyDefinition> {
        val scoped = index.definitionsByKey[key]
            .orEmpty()
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
        return preferDataIdDefinitions(scoped, preferredDataId)
    }

    /**
     * Declared dataId with cache hits → hard constraint; otherwise keep all hits
     * so a stale/wrong PropertySource still degrades to soft discovery.
     */
    internal fun preferDataIdDefinitions(
        definitions: List<KeyDefinition>,
        preferredDataId: String?
    ): List<KeyDefinition> {
        val dataId = preferredDataId?.takeIf { it.isNotBlank() } ?: return definitions
        val matched = definitions.filter { it.config.dataId == dataId }
        return matched.ifEmpty { definitions }
    }

    /**
     * Order: active namespace first, then public, then everything else
     * (stable within a tier by dataId/group).
     */
    private fun definitionComparator(
        activeNamespaceId: String?,
        preferredGroup: String?,
        preferredNamespaceId: String?,
        preferredDataId: String? = null
    ): Comparator<KeyDefinition> {
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

    private fun dataIdTier(definition: KeyDefinition, preferredDataId: String?): Int =
        if (preferredDataId != null && definition.config.dataId == preferredDataId) 0 else 1

    private fun namespaceTier(
        definition: KeyDefinition,
        activeNamespaceId: String?,
        preferredNamespaceId: String?
    ): Int = when {
        preferredNamespaceId != null && definition.namespaceId == preferredNamespaceId -> 0
        preferredNamespaceId != null -> 1
        activeNamespaceId != null && definition.namespaceId == activeNamespaceId -> 0
        definition.config.tenantId.isNullOrBlank() || definition.namespaceId == "public" -> 1
        else -> 2
    }

    private fun groupTier(definition: KeyDefinition, preferredGroup: String?): Int =
        if (preferredGroup != null && definition.config.group == preferredGroup) 0 else 1

    private fun sameNamespace(hitNamespaceId: String?, activeNamespaceId: String?): Boolean =
        normalizeNamespaceId(hitNamespaceId) == normalizeNamespaceId(activeNamespaceId)

    private fun normalizeNamespaceId(namespaceId: String?): String =
        namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: ""
}
