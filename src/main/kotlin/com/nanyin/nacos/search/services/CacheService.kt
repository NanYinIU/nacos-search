package com.nanyin.nacos.search.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CacheAgeCalculator
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.services.operations.ObservationHighWater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified cache for Nacos list pages, configuration details, and namespace indexes.
 *
 * Every read and write is keyed by [AccessIdentity] (issue #62). There is no
 * server-URL key space and no legacy identity factory with sentinel revisions.
 *
 * Persistence lives entirely behind [CacheStore], which owns the entry payloads
 * and the key list that names them together, so the cache cannot leave a payload
 * behind that no reclamation path can see (issue #61).
 *
 * **Writes go through [applyMutation] and nowhere else** (ADR-0045). It takes a
 * [CacheMutation] together with the observation sequence of the operation that
 * produced it, and both the cache-entry gate and the profile-deletion tombstone
 * check live inside it, so the gate's scope equals the mutation's coordinate by
 * construction. Reads stay named operations returning their own types: the
 * argument for collapsing writes is that the gate must be unbypassable, and
 * reads have no such property.
 *
 * That entry point carries [CacheWriteAccess], so it is reachable only from the
 * operation layer (ADR-0052). Reads carry nothing: the UI and code-navigation
 * layers read this cache freely.
 *
 * Callers that cache a derivation of this cache take a [CacheSnapshot], which
 * carries its own version and as-of instant. The cache lends out no
 * modification counter, no clock, and no coroutine scope, so no caller can
 * assemble an invalidation rule out of its internals (issue #64).
 */
@Service(Service.Level.APP)
@OptIn(CacheWriteAccess::class)
class CacheService internal constructor(
    private val currentTimeMillis: () -> Long,
    private val tombstones: ProfileTombstoneRegistry,
    private val store: CacheStore
) : Disposable {
    constructor() : this(System::currentTimeMillis, defaultTombstones(), FileCacheStore())
    internal constructor(store: CacheStore) : this(System::currentTimeMillis, ProfileTombstoneRegistry(), store)
    internal constructor(currentTimeMillis: () -> Long, store: CacheStore) :
        this(currentTimeMillis, ProfileTombstoneRegistry(), store)

    private val logger = thisLogger()
    private val cacheMutex = Mutex()
    // Entry payloads are loaded in the background so IDE startup never blocks on
    // cache file I/O. Reads await this signal before serving results that depend
    // on the full load.
    private val loadCompleted = CompletableDeferred<Unit>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val detailCache = ConcurrentHashMap<String, CacheEntry<NacosConfiguration>>()
    private val listPageCache = ConcurrentHashMap<String, CacheEntry<ConfigListResponse>>()
    private val namespaceIndexCache = ConcurrentHashMap<String, CacheEntry<List<NacosConfiguration>>>()
    private val namespaceIndexAuthority = ConcurrentHashMap<String, Boolean>()

    /**
     * The immutable state a [CacheSnapshot] is cut from. Version and payload are
     * published together so a reader can never pair a version with content from
     * a different one.
     */
    private class PublishedState(
        val version: Long,
        val details: Map<String, CacheEntry<NacosConfiguration>>,
        val namespaceIndexes: Map<String, NamespaceIndexRecord>
    )

    @Volatile
    private var publishedState = PublishedState(0, emptyMap(), emptyMap())
    private val publishLock = Any()

    /**
     * The cache-entry gate. Scopes are derived from the **complete** access
     * identity plus the mutation's coordinate — truncating to profile id and
     * access revision would let two identities that resolved to different API
     * generations reject each other's writes to distinct coordinates.
     */
    private val highWater = ObservationHighWater()

    /**
     * Advances whenever a mutation discards entries. A single-key load that
     * read an entry out of the store before the discard must not publish it
     * afterwards, which is how a lazy load used to resurrect an entry the user
     * had just cleared.
     */
    private val discardGeneration = AtomicLong(0)

    /**
     * The cache module's only write entry point (ADR-0045).
     *
     * [observation] is the sequence the operation that produced [mutation] took
     * **when it started**, so a later-started mutation wins even if an earlier
     * one completes after it (ADR-0020). Returns false when the mutation was
     * gated away or rejected by a tombstone, in which case nothing was written.
     *
     * It requires [CacheWriteAccess]: only a caller that performed the remote
     * operation holds the sequence that orders the write, so only such a caller
     * has anything to say here (ADR-0052).
     */
    @CacheWriteAccess
    suspend fun applyMutation(mutation: CacheMutation, observation: Long): Boolean {
        // The tombstone is absolute and independent of ordering: no observation
        // sequence, however recent, outranks it (ADR-0025). Fast path outside
        // the lock; rechecked under [cacheMutex] so a concurrent entomb cannot
        // race a late mutation past purge.
        entombedBy(mutation)?.let { if (tombstones.isEntombed(it)) return false }
        return cacheMutex.withLock {
            entombedBy(mutation)?.let { if (tombstones.isEntombed(it)) return@withLock false }
            val scopeChain = scopeChain(mutation)
            if (!highWater.accepts(scopeChain, observation)) return@withLock false
            // Raise only once the mutation has actually landed: a store write
            // that throws must not leave a mark that locks out the retry.
            applyLocked(mutation, observation)
            // Exactly here, and only here for a mutation: an accepted mutation
            // advances the snapshot version, a gated or entombed one returns
            // above without reaching this line.
            publish()
            highWater.raise(scopeChain.last(), observation)
            true
        }
    }

    /**
     * A versioned, self-dating view of this cache for [identity]. Taking one is
     * O(1); its payload views are computed on first use.
     */
    fun snapshot(identity: AccessIdentity): CacheSnapshot {
        val state = publishedState
        return CacheSnapshot(state.version, currentTimeMillis(), identity, state.details, state.namespaceIndexes)
    }

    /**
     * Profile-deletion cleanup (ADR-0025 / issue #105). Discards every
     * identity-scoped entry whose storage key belongs to [profileId]. The
     * tombstone already blocks re-population; this only removes residual data.
     * Idempotent. Not a remote-observation write — it does not take a sequence
     * and does not go through [applyMutation]. Serialized with mutations via
     * [cacheMutex] so a late write cannot re-land under the same lock after
     * the outer tombstone check but before residual removal.
     */
    fun purgeProfile(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        // Storage keys are `v2|{profileId}|…` (see CacheCoordinate.identityPrefix).
        val prefix = "v2|$id|"
        // Deletion cleanup runs off the operation layer; take the write lock
        // the same way mutations do. runBlocking is acceptable here: callers
        // are settings Apply / residual drain, not the EDT paint path, and the
        // lock holder never waits on the EDT.
        val removed = kotlinx.coroutines.runBlocking {
            cacheMutex.withLock {
                val detailKeys = detailCache.keys.filter { it.startsWith(prefix) }
                val listKeys = listPageCache.keys.filter { it.startsWith(prefix) }
                val indexKeys = namespaceIndexCache.keys.filter { it.startsWith(prefix) }
                if (detailKeys.isEmpty() && listKeys.isEmpty() && indexKeys.isEmpty()) {
                    return@withLock emptyList<String>() to emptyList<String>()
                }
                discardGeneration.incrementAndGet()
                detailKeys.forEach { detailCache.remove(it) }
                listKeys.forEach { listPageCache.remove(it) }
                indexKeys.forEach { key ->
                    namespaceIndexCache.remove(key)
                    namespaceIndexAuthority.remove(key)
                }
                publish()
                detailKeys to listKeys
            }
        }
        // Store reclamation outside the write lock so Apply never holds it for I/O.
        removed.first.forEach { key ->
            serviceScope.launch { runCatching { store.removeDetail(key) } }
        }
        removed.second.forEach { key ->
            serviceScope.launch { runCatching { store.removeListPage(key) } }
        }
        logger.info("Purged cache entries for deleted profile $id")
    }

    /**
     * The identity whose profile tombstone blocks this mutation, enumerated
     * exhaustively so a newly added mutation kind cannot silently skip the
     * check — adding one without a branch here is a compile error.
     */
    private fun entombedBy(mutation: CacheMutation): AccessIdentity? = when (mutation) {
        is CacheMutation.WriteDetail -> mutation.identity
        is CacheMutation.WriteListPage -> mutation.identity
        is CacheMutation.ReplaceNamespaceIndex -> mutation.identity
        is CacheMutation.MarkNamespaceIndexNonAuthoritative -> mutation.identity
        is CacheMutation.DeleteDetailNotFound -> mutation.identity
        is CacheMutation.InvalidateNamespace -> mutation.identity
        // The user's clear is not profile-scoped: it discards data rather than
        // resurrecting it, so a tombstone has nothing to protect against.
        CacheMutation.Clear -> null
    }

    /**
     * The scope chain a mutation must outrank, broadest first. Its own scope is
     * last, and that is the only mark an accepted mutation raises — a clear
     * raises the global mark, a namespace invalidation the namespace mark, and
     * either outranks a coordinate write that started earlier.
     */
    private fun scopeChain(mutation: CacheMutation): List<String> = when (mutation) {
        is CacheMutation.WriteDetail -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId),
            detailScope(detailKey(mutation.identity, mutation.namespaceId, mutation.configuration.dataId, mutation.configuration.group))
        )
        is CacheMutation.WriteListPage -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId),
            listPageScope(listPageKey(mutation.identity, mutation.namespaceId, mutation.requestKey))
        )
        is CacheMutation.ReplaceNamespaceIndex -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId),
            indexScope(mutation.identity, mutation.namespaceId)
        )
        is CacheMutation.MarkNamespaceIndexNonAuthoritative -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId),
            indexScope(mutation.identity, mutation.namespaceId)
        )
        is CacheMutation.DeleteDetailNotFound -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId),
            detailScope(detailKey(mutation.identity, mutation.namespaceId, mutation.dataId, mutation.group))
        )
        is CacheMutation.InvalidateNamespace -> listOf(
            GLOBAL_SCOPE,
            namespaceScope(mutation.identity, mutation.namespaceId)
        )
        CacheMutation.Clear -> listOf(GLOBAL_SCOPE)
    }

    private suspend fun applyLocked(mutation: CacheMutation, observation: Long) {
        when (mutation) {
            is CacheMutation.WriteDetail -> {
                val key = detailKey(
                    mutation.identity,
                    mutation.namespaceId,
                    mutation.configuration.dataId,
                    mutation.configuration.group
                )
                val entry = CacheEntry(
                    CacheEntryType.CONFIG_DETAIL,
                    mutation.configuration,
                    currentTimeMillis(),
                    mutation.ttlMillis,
                    mutation.source
                )
                detailCache[key] = entry
                store.putDetail(key, entry)
                cleanupOversizedCaches()
            }
            is CacheMutation.WriteListPage -> {
                val key = listPageKey(mutation.identity, mutation.namespaceId, mutation.requestKey)
                val entry = CacheEntry(
                    CacheEntryType.LIST_PAGE,
                    mutation.response,
                    currentTimeMillis(),
                    mutation.ttlMillis,
                    mutation.source
                )
                listPageCache[key] = entry
                store.putListPage(key, entry)
                cleanupOversizedCaches()
            }
            is CacheMutation.ReplaceNamespaceIndex -> replaceNamespaceIndexLocked(mutation, observation)
            is CacheMutation.MarkNamespaceIndexNonAuthoritative -> {
                val indexKey = namespaceKey(mutation.identity, mutation.namespaceId)
                if (namespaceIndexCache.containsKey(indexKey)) {
                    namespaceIndexAuthority[indexKey] = false
                }
            }
            is CacheMutation.DeleteDetailNotFound -> {
                val key = detailKey(mutation.identity, mutation.namespaceId, mutation.dataId, mutation.group)
                // Raise the discard mark before removing anything, so a lazy
                // load already reading this key from the store cannot publish it.
                discardGeneration.incrementAndGet()
                detailCache.remove(key)
                // Unconditional: the entry may be in the store without having
                // been loaded into memory yet, and it must not survive there.
                store.removeDetail(key)
            }
            is CacheMutation.InvalidateNamespace -> invalidateNamespaceLocked(mutation)
            CacheMutation.Clear -> {
                discardGeneration.incrementAndGet()
                // Every other scope chain runs through the global scope, so once
                // the clear's mark sits there the per-coordinate marks can only
                // repeat what it already says. Dropping them keeps this map from
                // growing for the life of the IDE.
                highWater.collapseTo(GLOBAL_SCOPE, observation)
                detailCache.clear()
                listPageCache.clear()
                namespaceIndexCache.clear()
                namespaceIndexAuthority.clear()
                store.clear()
                logger.info("Cache cleared")
            }
        }
    }

    private suspend fun replaceNamespaceIndexLocked(
        mutation: CacheMutation.ReplaceNamespaceIndex,
        observation: Long
    ) {
        val indexKey = namespaceKey(mutation.identity, mutation.namespaceId)
        // Persist summaries with empty/lightweight content only. Callers may
        // pass rows that still carry content; strip it so the index never
        // pretends to own bodies.
        val summaries = mutation.summaries.map { config ->
            if (config.content.isEmpty()) config else config.copy(content = "")
        }
        namespaceIndexCache[indexKey] = CacheEntry(
            CacheEntryType.NAMESPACE_INDEX,
            summaries,
            currentTimeMillis(),
            mutation.ttlMillis,
            mutation.source
        )
        namespaceIndexAuthority[indexKey] = true
        reclaimDetailsAbsentFromIndexLocked(mutation, summaries, observation)
        cleanupOversizedCaches()
    }

    /**
     * A complete index proves that anything it does not list is gone, so those
     * details are reclaimed — but only where no newer detail observation
     * exists, because a detail read that started after this index did may have
     * confirmed a configuration the index never saw (ADR-0020).
     *
     * The sweep is over what is in memory. A detail still sitting unread in the
     * store outlives one index replacement and is reclaimed by the next one
     * after the background load publishes it, which is the same bound every
     * other in-memory sweep in this class works under.
     */
    private suspend fun reclaimDetailsAbsentFromIndexLocked(
        mutation: CacheMutation.ReplaceNamespaceIndex,
        summaries: List<NacosConfiguration>,
        observation: Long
    ) {
        val listed = summaries
            .map { detailKey(mutation.identity, mutation.namespaceId, it.dataId, it.group) }
            .toSet()
        val namespacePrefix = detailNamespacePrefix(mutation.identity, mutation.namespaceId)
        val reclaimable = detailCache.keys.filter { key ->
            key.startsWith(namespacePrefix) &&
                key !in listed &&
                highWater.accepts(listOf(detailScope(key)), observation)
        }
        if (reclaimable.isEmpty()) return
        // Raise the discard mark before removing anything, so a lazy load
        // already reading one of these keys cannot publish it afterwards.
        discardGeneration.incrementAndGet()
        reclaimable.forEach { key ->
            highWater.acceptAndRaise(listOf(detailScope(key)), observation)
            detailCache.remove(key)
            store.removeDetail(key)
        }
    }

    private suspend fun invalidateNamespaceLocked(mutation: CacheMutation.InvalidateNamespace) {
        val namespacePrefix = detailNamespacePrefix(mutation.identity, mutation.namespaceId)
        val indexKey = namespaceKey(mutation.identity, mutation.namespaceId)
        discardGeneration.incrementAndGet()
        detailCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
            detailCache.remove(key)
            store.removeDetail(key)
        }
        listPageCache.keys.filter { it.startsWith(namespacePrefix) }.forEach { key ->
            listPageCache.remove(key)
            store.removeListPage(key)
        }
        namespaceIndexCache.remove(indexKey)
        namespaceIndexAuthority.remove(indexKey)
    }

    /**
     * Suspends until the background persistent load has finished (successfully
     * or with a logged failure). Prefer this over any side-effect await on a
     * statistics call (issue #62).
     */
    suspend fun awaitLoadCompleted() {
        loadCompleted.await()
    }

    companion object {
        /** The scope every mutation must outrank; only a user's clear raises it. */
        private const val GLOBAL_SCOPE = "*"
        // Single definition lives on CacheAgeCalculator (issue #42).
        private const val DEEP_STALE_AGE_MILLIS = CacheAgeCalculator.DEEP_STALE_THRESHOLD_MILLIS
        private const val MAX_CACHE_SIZE = 1000
        // Trigger the oversized-caches sweep only once the cache grows past the hard
        // cap by this margin, so per-insert writes stay O(1) instead of O(n).
        private const val CLEANUP_BUFFER = 100
    }

    init {
        // Load entry payloads in the background. Construction returns immediately so
        // the app-level service (and anything that touches it on the EDT, e.g. gutter
        // markers) is not blocked on file I/O at startup. Read methods await
        // [loadCompleted] before returning results that depend on the full load.
        serviceScope.launch {
            try {
                loadCacheFromPersistence()
            } catch (e: Exception) {
                logger.warn("Background cache load failed", e)
            } finally {
                loadCompleted.complete(Unit)
            }
        }
    }

    override fun dispose() {
        serviceScope.cancel()
    }

    suspend fun getListPage(
        identity: AccessIdentity,
        namespaceId: String?,
        requestKey: String,
        allowStale: Boolean = false
    ): ConfigListResponse? =
        getListPageEntry(identity, namespaceId, requestKey, allowStale)?.data

    /**
     * List-page read that also returns the entry's creation timestamp so
     * callers can derive [com.nanyin.nacos.search.models.CacheAge] from the
     * cached entry itself (issue #42) rather than from "now".
     */
    suspend fun getListPageEntry(
        identity: AccessIdentity,
        namespaceId: String?,
        requestKey: String,
        allowStale: Boolean = false
    ): CachedListPage? {
        loadCompleted.await()
        val key = listPageKey(identity, namespaceId, requestKey)
        val entry = listPageCache[key] ?: return null
        if (!entry.isExpired(currentTimeMillis()) || allowStale) {
            return CachedListPage(entry.data, entry.createdAt)
        }
        return null
    }

    /** Payload plus the entry metadata needed for age / confidence reporting. */
    data class CachedListPage(
        val data: ConfigListResponse,
        val createdAtMillis: Long
    )

    suspend fun getConfigDetail(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        allowStale: Boolean = false
    ): NacosConfiguration? {
        val key = detailKey(identity, namespaceId, dataId, group)
        return getConfigDetailByKey(key, allowStale)
    }

    private suspend fun getConfigDetailByKey(key: String, allowStale: Boolean): NacosConfiguration? {
        val entry = detailCache[key]
        if (entry != null) {
            if (!entry.isExpired(currentTimeMillis()) || allowStale) {
                return entry.data
            }
            return null
        }
        // Not in memory yet: a single-key lookup (e.g. go-to-declaration) should not wait
        // for the background full load. Try reading just this entry from the store; if the
        // background load is still in flight and the entry is genuinely absent, await it
        // and re-check so the result reflects any entry loaded concurrently.
        reconstituteDetail(key)?.let { reconstituted ->
            if (reconstituted.isExpired(currentTimeMillis()) && !allowStale) return null
            return reconstituted.data
        }
        loadCompleted.await()
        val loadedLate = detailCache[key]
        if (loadedLate != null && (!loadedLate.isExpired(currentTimeMillis()) || allowStale)) {
            return loadedLate.data
        }
        return null
    }

    /**
     * Reads one entry back out of the cache's own persistent store and publishes
     * it into memory. This is **not** a cache mutation: it observes the cache's
     * own store rather than the server, so it carries no observation sequence
     * and cannot raise any gate's mark. It is private behind the read path for
     * the same reason.
     *
     * It does have to respect a discard that happened while it was reading: a
     * lazy load already in flight must not republish an entry the user just
     * cleared. Returns null when the entry was discarded mid-flight, so the
     * caller reports a miss instead of data that no longer exists.
     *
     * It deliberately takes no lock. The background full load holds the write
     * lock for its whole duration, and a go-to-declaration lookup must not wait
     * behind it. Ordering against a concurrent discard comes from
     * [discardGeneration] instead: a discard raises it *before* emptying the
     * maps, so publishing and then re-checking is enough to catch every
     * interleaving.
     */
    private suspend fun reconstituteDetail(key: String): CacheEntry<NacosConfiguration>? {
        val discardsBeforeRead = discardGeneration.get()
        val fromStore = store.loadDetail(key) ?: return null
        if (discardGeneration.get() != discardsBeforeRead) return null
        val alreadyInMemory = detailCache.putIfAbsent(key, fromStore)
        val published = alreadyInMemory ?: fromStore
        if (discardGeneration.get() != discardsBeforeRead) {
            detailCache.remove(key, published)
            return null
        }
        // Publish only when this read actually added an entry. It changed the
        // cache's content without being a mutation, so the version has to move
        // or a derived index would never see the reconstituted entry — but a
        // read that found the entry already in memory changed nothing, and
        // moving the version for it would rebuild that index for no reason.
        if (alreadyInMemory == null && loadCompleted.isCompleted) publish()
        return published
    }

    suspend fun getNamespaceIndex(
        identity: AccessIdentity,
        namespaceId: String?,
        allowStale: Boolean = false
    ): List<NacosConfiguration>? =
        getNamespaceIndexEntry(identity, namespaceId, allowStale)?.data

    /**
     * Namespace-index read that also returns the entry's creation timestamp so
     * callers can derive [com.nanyin.nacos.search.models.CacheAge] from the
     * cached entry itself (issue #42) rather than from "now". The local-index
     * search path reports age from this, exactly as the list-page path does.
     */
    suspend fun getNamespaceIndexEntry(
        identity: AccessIdentity,
        namespaceId: String?,
        allowStale: Boolean = false
    ): CachedNamespaceIndex? {
        loadCompleted.await()
        return getNamespaceIndexByKey(namespaceKey(identity, namespaceId), allowStale)
    }

    /** Payload plus the entry metadata needed for age / confidence reporting. */
    data class CachedNamespaceIndex(
        val data: List<NacosConfiguration>,
        val createdAtMillis: Long
    )

    private fun getNamespaceIndexByKey(key: String, allowStale: Boolean): CachedNamespaceIndex? {
        val entry = namespaceIndexCache[key] ?: return null
        if (!entry.isExpired(currentTimeMillis()) || allowStale) {
            return CachedNamespaceIndex(entry.data, entry.createdAt)
        }
        return null
    }

    suspend fun getAllCachedConfigurations(identity: AccessIdentity): List<NacosConfiguration> {
        loadCompleted.await()
        return snapshot(identity).freshConfigurations
    }

    private suspend fun loadCacheFromPersistence() {
        // Hold the write lock for the whole load so the background load is mutually
        // exclusive with put/clear/invalidate (all of which also hold cacheMutex). This
        // prevents a still-running load from re-injecting stale entries after a clear,
        // or racing with a concurrent write. Reads are lock-free and merely await
        // loadCompleted, so there is no deadlock.
        cacheMutex.withLock {
            loadIdentityScopedDetailsFromStore()
            loadIdentityScopedListPagesFromStore()
            publish()
        }
    }

    /**
     * Records whose key cannot establish a profile owner are discarded rather
     * than guessed at (ADR-0018); the store reclaims their payload with them.
     */
    private suspend fun loadIdentityScopedDetailsFromStore() {
        store.loadDetails().forEach { (key, entry) ->
            if (isIdentityScopedKey(key)) detailCache[key] = entry else store.removeDetail(key)
        }
    }

    private suspend fun loadIdentityScopedListPagesFromStore() {
        val now = currentTimeMillis()
        store.loadListPages().forEach { (key, entry) ->
            if (isIdentityScopedKey(key) && !entry.isExpired(now)) {
                listPageCache[key] = entry
            } else {
                store.removeListPage(key)
            }
        }
    }

    private suspend fun cleanupExpiredEntriesLocked() {
        val now = currentTimeMillis()
        listPageCache.entries.filter { it.value.isExpired(now) }.forEach { (key, _) ->
            listPageCache.remove(key)
            store.removeListPage(key)
        }
        namespaceIndexCache.entries.filter { it.value.isExpired(now) }.forEach { (key, _) ->
            namespaceIndexCache.remove(key)
            namespaceIndexAuthority.remove(key)
        }
    }

    private suspend fun cleanupOversizedCaches() {
        // Only run the full sweep once any cache exceeds the hard cap plus a buffer;
        // this keeps individual writes O(1) instead of degrading to O(n^2) when
        // entries are inserted one at a time.
        val oversized = detailCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER ||
            listPageCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER ||
            namespaceIndexCache.size > MAX_CACHE_SIZE + CLEANUP_BUFFER
        if (!oversized) return
        cleanupExpiredEntriesLocked()
        trimOldest(detailCache) { key -> store.removeDetail(key) }
        trimOldest(listPageCache) { key -> store.removeListPage(key) }
        trimOldest(namespaceIndexCache) { /* in-memory only, never persisted */ }
    }

    /**
     * Cuts a new immutable published state and advances its version. The lock
     * pairs the two: a reader never sees a version alongside content published
     * under a different one.
     *
     * It does not make the read of the live maps atomic against a mutation in
     * progress. Every mutation publishes at the end of [applyMutation] while
     * still holding the write lock, so the only publish that can observe a
     * half-applied mutation is the one on the read path, and the mutation's own
     * publish supersedes it at a higher version.
     *
     * Namespace-index records are rebuilt with the state rather than derived per
     * read, so the data-id set behind an absence check is computed at most once
     * per version instead of once per gutter decision.
     */
    private fun publish() {
        synchronized(publishLock) {
            publishedState = PublishedState(
                publishedState.version + 1,
                detailCache.toMap(),
                namespaceIndexCache.entries.associate { (key, entry) ->
                    key to NamespaceIndexRecord(entry, namespaceIndexAuthority[key] == true)
                }
            )
        }
    }

    private suspend fun <T> trimOldest(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        removeFromStore: suspend (String) -> Unit
    ) {
        if (cache.size <= MAX_CACHE_SIZE) return
        cache.entries
            .sortedBy { it.value.createdAt }
            .take(cache.size - MAX_CACHE_SIZE)
            .forEach {
                cache.remove(it.key)
                removeFromStore(it.key) // reclaims the payload together with its key entry
            }
    }

    private fun detailKey(identity: AccessIdentity, namespaceId: String?, dataId: String, group: String): String =
        CacheCoordinate.detailKey(identity, namespaceId, dataId, group)

    private fun listPageKey(identity: AccessIdentity, namespaceId: String?, requestKey: String): String =
        CacheCoordinate.listPageKey(identity, namespaceId, requestKey)

    private fun namespaceKey(identity: AccessIdentity, namespaceId: String?): String =
        CacheCoordinate.namespaceIndexKey(identity, namespaceId)

    private fun identityPrefix(identity: AccessIdentity): String = CacheCoordinate.identityPrefix(identity)

    private fun detailNamespacePrefix(identity: AccessIdentity, namespaceId: String?): String =
        "${identityPrefix(identity)}|${normalizeNamespace(namespaceId)}|"

    // Gate scopes are prefixed by entry kind because a detail key and a list-page
    // key for the same identity and namespace can otherwise coincide.
    private fun detailScope(detailKey: String): String = "detail|$detailKey"

    private fun listPageScope(listPageKey: String): String = "list|$listPageKey"

    private fun indexScope(identity: AccessIdentity, namespaceId: String?): String =
        "index|" + namespaceKey(identity, namespaceId)

    private fun namespaceScope(identity: AccessIdentity, namespaceId: String?): String =
        "ns|" + namespaceKey(identity, namespaceId)

    private fun isIdentityScopedKey(key: String): Boolean = key.startsWith("v2|")

    private fun normalizeNamespace(namespaceId: String?): String =
        com.nanyin.nacos.search.models.NamespaceInfo.canonicalId(namespaceId)

    data class CacheEntry<T>(
        val type: CacheEntryType,
        val data: T,
        val createdAt: Long, // wall-clock epoch millis — persisted for stale-age checks
        val ttlMs: Long,
        val source: CacheSource,
        val stale: Boolean = false
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt > ttlMs
        fun freshness(now: Long = System.currentTimeMillis()): DetailFreshness {
            val age = now - createdAt
            return when {
                age <= ttlMs -> DetailFreshness.FRESH
                age <= DEEP_STALE_AGE_MILLIS -> DetailFreshness.STALE
                else -> DetailFreshness.DEEP_STALE
            }
        }
        fun isStale(now: Long = System.currentTimeMillis()): Boolean = freshness(now) == DetailFreshness.STALE
        fun isDeepStale(now: Long = System.currentTimeMillis()): Boolean = freshness(now) == DetailFreshness.DEEP_STALE
    }

    data class CachedConfiguration(
        val configuration: NacosConfiguration,
        val freshness: DetailFreshness,
        val freshUntilMillis: Long,
        val deepStaleAtMillis: Long
    )

    data class NamespaceIndexState(
        val dataIds: Set<String>,
        val freshness: DetailFreshness,
        val authoritativeForAbsence: Boolean
    )

    enum class DetailFreshness {
        FRESH,
        STALE,
        DEEP_STALE
    }

    enum class CacheEntryType {
        LIST_PAGE,
        CONFIG_DETAIL,
        NAMESPACE_INDEX
    }

    enum class CacheSource {
        REMOTE,
        CACHE,
        STALE_CACHE
    }
}

private fun defaultTombstones(): ProfileTombstoneRegistry =
    try {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(ProfileTombstoneRegistry::class.java) ?: ProfileTombstoneRegistry()
    } catch (e: Exception) {
        ProfileTombstoneRegistry()
    }
