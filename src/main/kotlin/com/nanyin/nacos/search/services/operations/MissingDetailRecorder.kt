package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheWriteAccess

/**
 * Records that a read proved a configuration detail gone.
 *
 * An authoritative not-found deletes the cached detail under the observation
 * sequence of the read that proved it, not under a fresh one: a read that
 * started afterwards must still be able to restore a configuration that was
 * recreated in the meantime, and a not-found that started before a newer read
 * must not delete what that read confirmed (ADR-0020 / ADR-0047).
 *
 * The delete is a cache mutation, so it lives here rather than at the panel
 * that noticed the empty result — the cache's write entry point is not
 * reachable from a Swing panel (issue #65). The panel still decides *when* to
 * report the absence; moving that decision out of the UI is #44.
 */
class MissingDetailRecorder(private val cacheService: CacheService) {
    /** True when the delete landed; false when a newer observation outranked it. */
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
