package com.nanyin.nacos.search.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.nanyin.nacos.search.services.CacheSnapshot
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import kotlinx.coroutines.CoroutineDispatcher
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
 * hash standing in for a generation token. Visibility is the second rule: the
 * version advances when a block or clear lands, and an index built under one
 * visibility state is served only for snapshots that hide the same Namespaces
 * and identity — never as the previous index for the same identity while a
 * rebuild is in flight (issue #126).
 */
@Service(Service.Level.APP)
class NacosKeyIndexService internal constructor(
    rebuildDispatcher: CoroutineDispatcher,
    private val onIndexPublished: () -> Unit = Companion::requestGutterPassAfterPublish
) : Disposable {
    constructor() : this(Dispatchers.Default)

    /**
     * Built here rather than accepted as a parameter. A constructor taking a
     * bare [CoroutineScope] is the signature the platform injects a
     * service-owned scope into, and then [dispose] would be cancelling a scope
     * this service did not create. A dispatcher is not injectable, so the
     * no-argument constructor is unambiguously the one the platform uses and
     * the scope is this service's own.
     */
    private val scope = CoroutineScope(rebuildDispatcher + SupervisorJob())

    @Volatile
    private var index: NacosKeyResolver.KeyIndex? = null
    private val building = AtomicBoolean(false)
    private val publishLock = Any()

    /**
     * The most recent snapshot asked for while a rebuild was already running.
     * Without it the ask would simply be dropped, and the fire-and-forget
     * [ensureIndexBuilt] callers have no later pass to heal on.
     */
    @Volatile
    private var pendingRebuild: CacheSnapshot? = null

    companion object {
        /**
         * After an async rebuild publishes, gutters that already painted gray
         * against the previous empty/stale index must re-analyze. Rebuild the
         * index is not enough — without a daemon pass the hollow icon sticks.
         */
        private fun requestGutterPassAfterPublish() {
            try {
                ApplicationManager.getApplication()
                    .getService(NavigationIndexRefreshService::class.java)
                    .requestGutterPass(null)
            } catch (_: Exception) {
                // Application may be unavailable in unit tests / teardown.
            }
        }
    }
    /**
     * The index for [snapshot], or null while none has been built for its
     * access identity. Never blocks and never rebuilds inline: PSI asks from
     * the highlighter, so a moved version only schedules a rebuild and the
     * previous index for the same identity is served meanwhile — but only when
     * it was derived under the same visibility state. A block or clear that
     * moved the version also changed what the snapshot hides, so the previous
     * index may hold definitions the snapshot now hides; serving it would be a
     * temporary bypass, so it is dropped and the rebuild's index is awaited
     * (issue #126).
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
        // this is still the previous index — only for this identity AND a
        // compatible visibility state, so one environment's keys never stand
        // in for another's and a hidden definition is never served.
        return index?.takeIf {
            it.accessIdentity == snapshot.identity && it.visibilityCompatibleWith(snapshot)
        }
    }

    /** Rebuilds from [snapshot] on the calling thread. Never call this from the EDT. */
    fun refreshIndex(snapshot: CacheSnapshot): NacosKeyResolver.KeyIndex {
        val built = NacosKeyResolver.buildIndex(snapshot)
        publishUnlessOlder(built)
        return built
    }

    /**
     * Publishes [built] unless what is already there was derived from a later
     * snapshot. A rebuild that started earlier can finish after one that started
     * later; letting it win would make the next decision see a version that
     * moved backwards and rebuild again for nothing — the very churn a
     * version-stamped index exists to avoid.
     *
     * The comparison ignores access identity on purpose: versions come from one
     * cache-wide counter, so they order builds for different identities too, and
     * an environment switch with no intervening mutation compares equal and
     * publishes.
     */
    private fun publishUnlessOlder(built: NacosKeyResolver.KeyIndex) {
        synchronized(publishLock) {
            if ((index?.version ?: Long.MIN_VALUE) > built.version) return
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
        allowCrossNamespace: Boolean = true,
        formatOverride: ReferenceFormatOverride? = null
    ): List<NacosKeyResolver.KeyHit> = NacosKeyResolver.resolve(
        key = key,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId,
        preferredGroup = preferredGroup,
        preferredNamespaceId = preferredNamespaceId,
        preferredDataId = preferredDataId,
        allowCrossNamespace = allowCrossNamespace,
        formatOverride = formatOverride
    )

    fun resolveCurrentState(
        snapshot: CacheSnapshot,
        key: String,
        activeNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true,
        formatOverride: ReferenceFormatOverride? = null
    ): ConfigResolution = NacosKeyResolver.resolveCurrentState(
        key = key,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId,
        preferredDataId = preferredDataId,
        allowCrossNamespace = allowCrossNamespace,
        formatOverride = formatOverride
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

    /**
     * Whether a hollow marker for a declared Data ID is useful under the
     * session-selected Namespace — see
     * [NacosKeyResolver.isDeclaredSourceActionableInActiveNamespace].
     */
    fun isDeclaredSourceActionableInActiveNamespace(
        snapshot: CacheSnapshot,
        dataId: String,
        activeNamespaceId: String? = null
    ): Boolean = NacosKeyResolver.isDeclaredSourceActionableInActiveNamespace(
        dataId = dataId,
        index = currentIndex(snapshot),
        snapshot = snapshot,
        activeNamespaceId = activeNamespaceId
    )

    /**
     * Runs at most one rebuild at a time. An ask that arrives while one is
     * running is remembered rather than dropped, and picked up when the running
     * one finishes: a highlighter pass would heal a dropped ask on its own, but
     * [ensureIndexBuilt] is fire-and-forget and has no later pass to heal on.
     *
     * When a rebuild publishes a newer index (or the first index for an
     * identity), [onIndexPublished] runs so gutters re-analyze — otherwise a
     * hollow icon painted while the previous empty index was served sticks
     * even though the configuration keys are now present.
     */
    private fun scheduleRebuild(snapshot: CacheSnapshot) {
        pendingRebuild = snapshot
        if (!building.compareAndSet(false, true)) return
        scope.launch {
            try {
                val before = index
                var next = pendingRebuild
                while (next != null) {
                    pendingRebuild = null
                    refreshIndex(next)
                    next = pendingRebuild
                }
                val after = index
                if (after != null &&
                    (before == null ||
                        after.version > before.version ||
                        after.accessIdentity != before.accessIdentity ||
                        after.accessBlocked != before.accessBlocked ||
                        after.blockedNamespaces != before.blockedNamespaces)
                ) {
                    onIndexPublished()
                }
            } finally {
                building.set(false)
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }
}
