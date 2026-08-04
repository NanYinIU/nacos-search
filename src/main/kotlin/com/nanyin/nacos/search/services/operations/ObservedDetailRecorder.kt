package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheWriteAccess

/**
 * Turns what a completed read observed about one configuration detail into a
 * cache mutation, under the observation sequence of the read that observed it.
 *
 * Callers hand over a finished observation — this configuration is here with
 * this content, or it is gone — and this type decides nothing beyond how that
 * becomes a mutation. Carrying the read's own sequence rather than a fresh one
 * is what keeps the ordering rule intact: a read that started afterwards must
 * still be able to restore a configuration recreated in the meantime, and a
 * not-found that started before a newer read must not delete what that read
 * confirmed (ADR-0020 / ADR-0047).
 *
 * It exists so that the layers which *notice* these things — a Swing panel
 * refreshing a detail, a gutter renderer navigating to one — do not have to
 * hold a cache mutation to report them. The cache's write entry point is not
 * reachable from either (ADR-0051). Those layers still decide *when* to report;
 * moving that decision out of the panels is #44.
 */
class ObservedDetailRecorder(private val cacheService: CacheService) {
    /**
     * Records a configuration detail a read returned. True when the write
     * landed; false when an observation at or above this one already owns the
     * coordinate — including the gateway's own write of the same read.
     */
    @OptIn(CacheWriteAccess::class)
    suspend fun recordDetail(
        identity: AccessIdentity,
        namespaceId: String?,
        configuration: NacosConfiguration,
        ttlMillis: Long,
        observation: Long
    ): Boolean = cacheService.applyMutation(
        CacheMutation.WriteDetail(identity, namespaceId, configuration, ttlMillis),
        observation
    )

    /**
     * Records an authoritative not-found. True when the delete landed; false
     * when a newer observation outranked it.
     */
    @OptIn(CacheWriteAccess::class)
    suspend fun recordMissing(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        observation: Long
    ): Boolean = cacheService.applyMutation(
        CacheMutation.DeleteDetailNotFound(identity, namespaceId, dataId, group),
        observation
    )
}
