package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic per-project epoch that advances whenever profile, namespace, access
 * revision, profile revision, or resolved-generation changes. Operations capture
 * the epoch at issue time and publish results only if the epoch is unchanged.
 */
class SessionEpochRegistry {
    private val epochs = ConcurrentHashMap<String, AtomicLong>()

    fun currentEpoch(projectId: String): Long =
        epochs[projectId]?.get() ?: 0L

    fun bump(projectId: String): Long =
        epochs.computeIfAbsent(projectId) { AtomicLong(0) }.incrementAndGet()

    fun capture(projectId: String, identity: AccessIdentity): OperationTicket =
        OperationTicket(projectId, currentEpoch(projectId), this)
}

/**
 * Immutable snapshot of the session epoch at the moment an operation started.
 * Returns [isCurrent] false once any state change has bumped the epoch past
 * the captured value.
 */
class OperationTicket(
    private val projectId: String,
    private val capturedEpoch: Long,
    private val registry: SessionEpochRegistry
) {
    fun isCurrent(): Boolean = registry.currentEpoch(projectId) == capturedEpoch

    /**
     * Called by the operation immediately before publishing its result. If the
     * epoch has advanced, the caller must drop the result rather than write it
     * into cache, token, or UI state.
     */
    fun checkpoint() {
        // No-op; the real guard is [isCurrent], checked by the fence.
    }
}

/**
 * Outcome of a fenced operation. [published] is true only when the session
 * epoch was unchanged for the entire operation lifetime.
 */
data class FencedOutcome<T>(val published: Boolean, val value: T?)

/**
 * Wraps an operation so that its result is published only when the session
 * epoch has not advanced since the operation started. If a profile/namespace/
 * policy change occurred during the operation, the result is silently dropped.
 */
class OperationFence(private val registry: SessionEpochRegistry) {

    /**
     * Launches an operation in [scope] and returns a [Deferred] that resolves
     * to a [FencedOutcome]. The outcome is published only if the session epoch
     * has not advanced between launch and completion.
     */
    fun <T> launch(
        scope: CoroutineScope,
        projectId: String,
        identity: AccessIdentity,
        operation: suspend (OperationTicket) -> T
    ): Deferred<FencedOutcome<T>> {
        val ticket = registry.capture(projectId, identity)
        return scope.async {
            val result = operation(ticket)
            if (ticket.isCurrent()) {
                FencedOutcome(published = true, value = result)
            } else {
                FencedOutcome(published = false, value = null)
            }
        }
    }
}

/**
 * Persists a profile deletion marker before cleanup. Once entombed, every
 * late credential, token, probe, session, index, detail, and cache completion
 * for that profile identity is rejected. The tombstone survives profile
 * re-creation with the same id so stale in-flight work can never resurrect
 * deleted state.
 */
class ProfileTombstoneRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Tombstone(val profileId: String, val entombedAt: Long)

    private val tombstones = ConcurrentHashMap<String, Tombstone>()

    fun entomb(profileId: String, @Suppress("UNUSED_PARAMETER") accessRevision: Long) {
        tombstones[profileId] = Tombstone(profileId, clock())
    }

    /**
     * Returns true if [identity] belongs to an entombed profile. Once entombed,
     * all subsequent operations for the same profile id are rejected regardless
     * of access revision — a re-created profile must use a fresh id.
     */
    fun isEntombed(identity: AccessIdentity): Boolean =
        tombstones.containsKey(identity.profileId)

    fun clear(profileId: String) {
        tombstones.remove(profileId)
    }
}

/**
 * Observation ordering guard. Prevents older cache visibility successes or
 * failures from overriding a newer outcome for the same identity+namespace.
 * Each observation receives a monotonically increasing stamp; only the highest
 * stamp is considered current.
 */
class ObservationHighWater {
    data class Stamp(val identity: AccessIdentity, val namespaceId: String, val sequence: Long)

    private val highWater = ConcurrentHashMap<String, AtomicLong>()
    private val sequence = AtomicLong(0)

    fun capture(identity: AccessIdentity, namespaceId: String): Stamp {
        val seq = sequence.incrementAndGet()
        val key = visibilityKey(identity, namespaceId)
        highWater.compute(key) { _, current ->
            val existing = current ?: AtomicLong(0)
            while (true) {
                val seen = existing.get()
                if (seq <= seen) break
                if (existing.compareAndSet(seen, seq)) break
            }
            existing
        }
        return Stamp(identity, namespaceId, seq)
    }

    fun isCurrent(stamp: Stamp): Boolean {
        val current = highWater[visibilityKey(stamp.identity, stamp.namespaceId)]?.get() ?: 0L
        return stamp.sequence >= current
    }

    private fun visibilityKey(identity: AccessIdentity, namespaceId: String): String =
        "${identity.profileId}|${identity.accessRevision}|${identity.canonicalEndpoint}|" +
            "${identity.resolvedGeneration}|${identity.authMode}|${identity.principal}|$namespaceId"
}
