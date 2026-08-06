package com.nanyin.nacos.search.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
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
    /**
     * The epoch this operation started under. A caller that hands its result to
     * a view passes this along so the view can make the same judgement after
     * its own thread hop (ADR-0047).
     */
    val capturedEpoch: Long,
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
 * for that profile identity is rejected. The tombstone survives IDE restarts
 * and profile re-creation with the same id so stale in-flight work can never
 * resurrect deleted state.
 */
@Service(Service.Level.APP)
@State(name = "NacosProfileTombstones", storages = [Storage("nacos-profile-tombstones.xml")])
class ProfileTombstoneRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) : PersistentStateComponent<ProfileTombstoneRegistry.State> {
    constructor() : this(System::currentTimeMillis)

    data class State(
        var profileIds: MutableSet<String> = linkedSetOf()
    )

    private data class Tombstone(val profileId: String, val entombedAt: Long)

    private val tombstones = ConcurrentHashMap<String, Tombstone>()
    private var state = State()

    override fun getState(): State {
        state.profileIds = tombstones.keys.toMutableSet()
        return state
    }

    override fun loadState(state: State) {
        this.state = state
        tombstones.clear()
        state.profileIds.forEach { id ->
            tombstones[id] = Tombstone(id, clock())
        }
    }

    fun entomb(profileId: String, @Suppress("UNUSED_PARAMETER") accessRevision: Long) {
        tryEntomb(profileId)
    }

    /**
     * Record a deletion marker for [profileId] in the in-process map and the
     * [PersistentStateComponent] bean. Idempotent when already entombed.
     *
     * Returns false only for a blank id. Durability beyond the component bean
     * is the platform's normal settings-save bound: this method does not fsync
     * the XML and does not observe save failures. Hosts that cannot obtain this
     * service at all must fail closed before unpublishing (issue #105 AC2).
     */
    fun tryEntomb(profileId: String): Boolean {
        val id = profileId.trim()
        if (id.isBlank()) return false
        tombstones[id] = Tombstone(id, clock())
        state.profileIds.add(id)
        return true
    }

    /**
     * Returns true if [identity] belongs to an entombed profile. Once entombed,
     * all subsequent operations for the same profile id are rejected regardless
     * of access revision — a re-created profile must use a fresh id.
     */
    fun isEntombed(identity: AccessIdentity): Boolean =
        isEntombed(identity.profileId)

    /** Profile-id form of the lifecycle guard (no fabricated access identity). */
    fun isEntombed(profileId: String): Boolean =
        tombstones.containsKey(profileId.trim())

    /** Every entombed profile id currently held by this registry. */
    fun entombedProfileIds(): Set<String> = tombstones.keys.toSet()

    fun clear(profileId: String) {
        tombstones.remove(profileId)
        state.profileIds.remove(profileId)
    }
}
