package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * One intended change to the cache's contents, carrying its scope coordinate.
 * The observation sequence that produced it travels beside it into
 * [CacheService.apply], which is the module's only write entry point.
 *
 * The set is closed on purpose (ADR-0045): the cache-entry gate and the
 * profile-deletion tombstone check both live at that one entry point, so a new
 * mutation kind cannot be added without passing both. Six named write methods
 * each calling a private gate is not an acceptable substitute — that
 * arrangement has already failed twice inside this module.
 *
 * Reading, and the cache reconstituting an entry from its own persistent store,
 * are **not** mutations: the second is an observation of the cache's own store
 * rather than of the server, and it stays private behind the read path.
 */
sealed interface CacheMutation {
    /**
     * The access identity whose data this mutation touches, or null for the
     * user's explicit [Clear], which is not profile-scoped. A non-null identity
     * is checked against the profile-deletion tombstone before anything else.
     */
    val identity: AccessIdentity?

    /** Writes one configuration detail at its coordinate. */
    data class WriteDetail(
        override val identity: AccessIdentity,
        val namespaceId: String?,
        val configuration: NacosConfiguration,
        val ttlMillis: Long,
        val source: CacheService.CacheSource = CacheService.CacheSource.REMOTE
    ) : CacheMutation

    /** Writes one list page for a namespace and request key. */
    data class WriteListPage(
        override val identity: AccessIdentity,
        val namespaceId: String?,
        val requestKey: String,
        val response: ConfigListResponse,
        val ttlMillis: Long,
        val source: CacheService.CacheSource = CacheService.CacheSource.REMOTE
    ) : CacheMutation

    /**
     * Replaces a namespace index with a complete set of summaries and marks it
     * authoritative for absence. Details this index no longer lists are
     * reclaimed, but only where no newer detail observation exists.
     */
    data class ReplaceNamespaceIndex(
        override val identity: AccessIdentity,
        val namespaceId: String?,
        val summaries: List<NacosConfiguration>,
        val ttlMillis: Long,
        val source: CacheService.CacheSource = CacheService.CacheSource.REMOTE
    ) : CacheMutation

    /**
     * Records that the namespace index can no longer prove a configuration
     * absent. A partial or failed index never deletes anything.
     */
    data class MarkNamespaceIndexNonAuthoritative(
        override val identity: AccessIdentity,
        val namespaceId: String?
    ) : CacheMutation

    /** Deletes one configuration detail after an authoritative not-found. */
    data class DeleteDetailNotFound(
        override val identity: AccessIdentity,
        val namespaceId: String?,
        val dataId: String,
        val group: String
    ) : CacheMutation

    /** Drops every detail, list page, and index for one namespace. */
    data class InvalidateNamespace(
        override val identity: AccessIdentity,
        val namespaceId: String?
    ) : CacheMutation

    /**
     * The user's explicit cache clear. It takes an observation sequence like
     * every other mutation, so a write that started before it loses and an
     * operation started afterwards lands normally — clear-then-reload behaves
     * as the obvious gesture.
     */
    data object Clear : CacheMutation {
        override val identity: AccessIdentity? get() = null
    }
}
