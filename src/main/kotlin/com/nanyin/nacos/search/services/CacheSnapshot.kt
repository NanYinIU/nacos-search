package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CacheAgeCalculator
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.visibility.ConfigurationVisibility

/**
 * An immutable, self-dating view of the cache for one access identity.
 *
 * Callers that cache a derivation of the cache — the `@NacosValue` key index is
 * the one that matters — used to assemble their own staleness rule out of
 * pieces the cache lent them: a modification counter, a clock accessor, and a
 * JVM identity hash standing in for a generation token. Any of those going out
 * of step left the derivation stale or rebuilt for nothing. A snapshot carries
 * both facts a derivation needs and nothing else (issue #64):
 *
 * - [version] advances when the cache's content **or** relevant visibility
 *   state changed. A mutation the gate drops changes nothing, so it does not
 *   advance the content half; a visibility block/clear advances the visibility
 *   half (issue #123).
 * - [asOfMillis] is the single instant every freshness judgement made from this
 *   snapshot is against, so one decision cannot straddle a freshness boundary
 *   and report a list of matches in inconsistent states.
 *
 * Under an identity-wide authentication block, payload views are empty and
 * [visibility] is [ConfigurationVisibility.Blocked] so code navigation can
 * distinguish undecidable from unresolved (#126 consumes this hook). Under a
 * Namespace-scoped configuration-read authorization block only that Namespace's
 * payloads are absent from the views; [visibility] stays [Visible] at the
 * identity level (issue #124).
 *
 * Taking one is O(1): the payload views below are computed lazily, so the PSI
 * hot path can take a snapshot per decision and only pay for a scan when it
 * actually rebuilds.
 */
class CacheSnapshot internal constructor(
    val version: Long,
    val asOfMillis: Long,
    val identity: AccessIdentity,
    private val details: Map<String, CacheService.CacheEntry<NacosConfiguration>>,
    private val namespaceIndexes: Map<String, NamespaceIndexRecord>,
    val visibility: ConfigurationVisibility = ConfigurationVisibility.Visible
) {
    /**
     * True when configuration-read data for the whole [identity] is hidden by an
     * identity-wide authentication block. Namespace-only authorization blocks do
     * not set this; those are already filtered out of payload views (issue #124).
     */
    val isAccessBlocked: Boolean get() = visibility is ConfigurationVisibility.Blocked
    private val detailPrefix: String get() = "${CacheCoordinate.identityPrefix(identity)}|"

    /**
     * Every cached configuration detail for [identity], including stale ones so
     * code navigation can still reach an expired target (ADR-0002). Each is
     * judged at [asOfMillis].
     */
    val configurations: List<CacheService.CachedConfiguration> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val prefix = detailPrefix
        details.asSequence()
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (_, entry) -> entry.cachedConfigurationAt(asOfMillis) }
            .distinctBy { it.configuration.identityWithinNamespace() }
            .toList()
    }

    /** The subset of [configurations] still inside its TTL at [asOfMillis]. */
    val freshConfigurations: List<NacosConfiguration> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val prefix = detailPrefix
        details.asSequence()
            .filter { (key, entry) -> key.startsWith(prefix) && !entry.isExpired(asOfMillis) }
            .map { it.value.data }
            .distinctBy { it.identityWithinNamespace() }
            .toList()
    }

    /** One configuration detail by coordinate, judged at [asOfMillis]. */
    fun detail(namespaceId: String?, dataId: String, group: String): CacheService.CachedConfiguration? =
        details[CacheCoordinate.detailKey(identity, namespaceId, dataId, group)]
            ?.cachedConfigurationAt(asOfMillis)

    /**
     * The namespace index for absence checks, judged at [asOfMillis]. Null when
     * no index has been taken for that namespace under this identity.
     *
     * Authority for absence is memory-only by design: an index restored from
     * the store comes back without it, so a restart can never let a stale index
     * prove a configuration gone.
     */
    fun namespaceIndex(namespaceId: String?): CacheService.NamespaceIndexState? =
        namespaceIndexes[CacheCoordinate.namespaceIndexKey(identity, namespaceId)]
            ?.stateAt(asOfMillis)

    private fun CacheService.CacheEntry<NacosConfiguration>.cachedConfigurationAt(
        now: Long
    ): CacheService.CachedConfiguration = CacheService.CachedConfiguration(
        data,
        freshness(now),
        freshUntilMillis = createdAt + ttlMs,
        deepStaleAtMillis = createdAt + CacheAgeCalculator.DEEP_STALE_THRESHOLD_MILLIS
    )

    private fun NacosConfiguration.identityWithinNamespace(): String =
        "$dataId:$group:${tenantId ?: ""}"
}

/**
 * A namespace index as the published cache state holds it: the entry plus the
 * memory-only authority flag, with the data-id set derived once on demand
 * rather than on every absence check.
 */
internal class NamespaceIndexRecord(
    private val entry: CacheService.CacheEntry<List<NacosConfiguration>>,
    private val authoritativeForAbsence: Boolean
) {
    private val dataIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entry.data.asSequence().map { it.dataId }.filter { it.isNotBlank() }.toSet()
    }

    fun stateAt(now: Long): CacheService.NamespaceIndexState = CacheService.NamespaceIndexState(
        dataIds = dataIds,
        freshness = entry.freshness(now),
        authoritativeForAbsence = authoritativeForAbsence
    )
}
