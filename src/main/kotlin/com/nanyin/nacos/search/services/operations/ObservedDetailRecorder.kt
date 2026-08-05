package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheWriteAccess

/**
 * Turns what a completed read observed about one configuration detail into a
 * cache mutation, under the observation sequence of the read that observed it.
 *
 * Callers hand over a finished observation — today the only one they can make
 * that the read itself did not already act on: this configuration is gone. A
 * detail that *is* there needs nothing recorded, because every production read
 * of one goes through the gateway, which caches what it returned. The one that
 * deliberately does not is the publish preflight, whose baseline must not
 * become the cached truth before the write happens; recording it would be a
 * defect, not a use for this type.
 *
 * There was a second method for detail writes. Its only caller was the gutter
 * marker's lazy load, which wrote a fetched configuration back so its own read
 * would find it — necessary only because an AUTO profile left the code
 * navigation layer reading a different cache key space from the one the gateway
 * wrote into. Issue #72 converged the two, which made that write a plain
 * duplicate, and the method went with it. Do not reintroduce it speculatively:
 * a pre-built write with no caller is the escape hatch ADR-0052 exists to
 * prevent, and the last one was used to paper over a defect.
 *
 * Carrying the read's own sequence rather than a fresh one is what keeps the
 * ordering rule intact: a read that started afterwards must still be able to
 * restore a configuration recreated in the meantime, and a not-found that
 * started before a newer read must not delete what that read confirmed
 * (ADR-0020 / ADR-0047).
 *
 * It exists so that the layer which *notices* this — a Swing panel refreshing a
 * detail — does not have to hold a cache mutation to report it. The cache's
 * write entry point is not reachable from there (ADR-0052). That layer still
 * decides *when* to report; moving that decision out of the panel is #44.
 */
class ObservedDetailRecorder(private val cacheService: CacheService) {
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
