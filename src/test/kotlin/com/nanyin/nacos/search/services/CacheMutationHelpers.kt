package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.ObservationSequence

/**
 * Seeding helpers for tests whose subject is not the ordering machinery.
 *
 * They still go through the one gated write entry point — they only spare each
 * call site from spelling out a mutation and an observation sequence it does
 * not care about. Sequences default to the process-wide counter, so successive
 * calls are ordered exactly as they are written. Tests that *are* about
 * ordering pass their own sequences to [CacheService.apply] directly.
 */
internal const val TEST_CACHE_TTL = 300_000L

internal suspend fun CacheService.writeDetail(
    identity: AccessIdentity,
    namespaceId: String?,
    configuration: NacosConfiguration,
    ttl: Long = TEST_CACHE_TTL,
    source: CacheService.CacheSource = CacheService.CacheSource.REMOTE,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(CacheMutation.WriteDetail(identity, namespaceId, configuration, ttl, source), observation)

internal suspend fun CacheService.writeListPage(
    identity: AccessIdentity,
    namespaceId: String?,
    requestKey: String,
    response: ConfigListResponse,
    ttl: Long = TEST_CACHE_TTL,
    source: CacheService.CacheSource = CacheService.CacheSource.REMOTE,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(
    CacheMutation.WriteListPage(identity, namespaceId, requestKey, response, ttl, source),
    observation
)

internal suspend fun CacheService.replaceNamespaceIndex(
    identity: AccessIdentity,
    namespaceId: String?,
    summaries: List<NacosConfiguration>,
    ttl: Long = TEST_CACHE_TTL,
    source: CacheService.CacheSource = CacheService.CacheSource.REMOTE,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(
    CacheMutation.ReplaceNamespaceIndex(identity, namespaceId, summaries, ttl, source),
    observation
)

internal suspend fun CacheService.markNamespaceIndexNonAuthoritative(
    identity: AccessIdentity,
    namespaceId: String?,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(CacheMutation.MarkNamespaceIndexNonAuthoritative(identity, namespaceId), observation)

internal suspend fun CacheService.deleteDetailNotFound(
    identity: AccessIdentity,
    namespaceId: String?,
    dataId: String,
    group: String,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(CacheMutation.DeleteDetailNotFound(identity, namespaceId, dataId, group), observation)

internal suspend fun CacheService.invalidateNamespace(
    identity: AccessIdentity,
    namespaceId: String?,
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(CacheMutation.InvalidateNamespace(identity, namespaceId), observation)

internal suspend fun CacheService.clearAll(
    observation: Long = ObservationSequence.process.next()
): Boolean = apply(CacheMutation.Clear, observation)
