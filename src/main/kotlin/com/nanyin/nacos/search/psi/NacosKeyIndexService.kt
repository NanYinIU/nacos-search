package com.nanyin.nacos.search.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.nanyin.nacos.search.services.CacheSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the derived `@NacosValue` key index: the one cached copy, and the scope
 * its rebuilds run in.
 *
 * The index used to live in a stateless object and borrow the cache's coroutine
 * scope, so clearing the cache left it running work in a scope that no longer
 * made sense. Its lifecycle is now its own — an application service disposed
 * with the application (issue #64).
 *
 * Staleness is decided by one number: the [CacheSnapshot.version] the index was
 * built from. No modification counter, no clock comparison, and no JVM identity
 * hash standing in for a generation token.
 */
@Service(Service.Level.APP)
class NacosKeyIndexService internal constructor(
    private val scope: CoroutineScope
) : Disposable {
    constructor() : this(CoroutineScope(Dispatchers.Default + SupervisorJob()))

    @Volatile
    private var index: NacosKeyResolver.KeyIndex? = null
    private val building = AtomicBoolean(false)
    private val publishLock = Any()

    /**
     * The index for [snapshot], or null while none has been built for its access
     * identity. Never blocks and never rebuilds inline: PSI asks from the
     * highlighter, so a moved version only schedules a rebuild and the previous
     * index for the same identity is served meanwhile.
     */
    fun currentIndex(snapshot: CacheSnapshot): NacosKeyResolver.KeyIndex? {
        index?.let { existing ->
            if (existing.accessIdentity == snapshot.identity && existing.version == snapshot.version) {
                return existing
            }
        }
        scheduleRebuild(snapshot)
        // Re-read: the rebuild may already have landed, and if it has, serving
        // the previous index instead would be gratuitously stale. Otherwise
        // this is still the previous index — for this identity only, so one
        // environment's keys never stand in for another's.
        return index?.takeIf { it.accessIdentity == snapshot.identity }
    }

    /** Rebuilds from [snapshot] on the calling thread. Never call this from the EDT. */
    fun refreshIndex(snapshot: CacheSnapshot): NacosKeyResolver.KeyIndex {
        val built = NacosKeyResolver.buildIndex(snapshot)
        publishUnlessOlder(built)
        return built
    }

    /**
     * Publishes [built] unless what is already there describes a later version of
     * the same access identity. A rebuild that started earlier can finish after
     * one that started later; letting it win would make the next decision see a
     * version that moved backwards and rebuild again for nothing — the very
     * churn a version-stamped index exists to avoid.
     */
    private fun publishUnlessOlder(built: NacosKeyResolver.KeyIndex) {
        synchronized(publishLock) {
            val current = index
            if (current != null &&
                current.accessIdentity == built.accessIdentity &&
                current.version > built.version
            ) {
                return
            }
            index = built
        }
    }

    /**
     * Warms the index for [snapshot] without blocking the caller, so the first
     * gutter pass after startup or a namespace switch already has one.
     */
    fun ensureIndexBuilt(snapshot: CacheSnapshot) {
        scheduleRebuild(snapshot)
    }

    // ── Resolution against one snapshot ──

    fun resolve(
        snapshot: CacheSnapshot,
        key: String,
        activeNamespaceId: String? = null,
        preferredGroup: String? = null,
        preferredNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true
    ): List<NacosKeyResolver.KeyHit> = NacosKeyResolver.resolve(
        key = key,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId,
        preferredGroup = preferredGroup,
        preferredNamespaceId = preferredNamespaceId,
        preferredDataId = preferredDataId,
        allowCrossNamespace = allowCrossNamespace
    )

    fun resolveCurrentState(
        snapshot: CacheSnapshot,
        key: String,
        activeNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true
    ): ConfigResolution = NacosKeyResolver.resolveCurrentState(
        key = key,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId,
        preferredDataId = preferredDataId,
        allowCrossNamespace = allowCrossNamespace
    )

    fun hasKey(
        snapshot: CacheSnapshot,
        key: String,
        activeNamespaceId: String? = null,
        allowCrossNamespace: Boolean = true
    ): Boolean = NacosKeyResolver.hasKey(
        key = key,
        index = currentIndex(snapshot),
        activeNamespaceId = activeNamespaceId,
        allowCrossNamespace = allowCrossNamespace
    )

    fun isDataIdKnown(
        snapshot: CacheSnapshot,
        dataId: String,
        activeNamespaceId: String? = null
    ): Boolean = NacosKeyResolver.isDataIdKnown(
        dataId = dataId,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId
    )

    private fun scheduleRebuild(snapshot: CacheSnapshot) {
        if (!building.compareAndSet(false, true)) return
        scope.launch {
            try {
                refreshIndex(snapshot)
            } finally {
                building.set(false)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
