package com.nanyin.nacos.search.services.visibility

import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.CacheStore
import com.nanyin.nacos.search.services.operations.ObservationHighWater
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reports a completed formal remote observation to the access-visibility module.
 *
 * Production uses [AccessVisibility]. Isolated connection diagnostics use
 * [NoOpVisibilityReporter] so they never affect production visibility state
 * (issue #48 / ADR-0050).
 */
fun interface VisibilityReporter {
    /**
     * Single mutation entry point. Accepts the observation only when its
     * start-time sequence outranks every relevant high-water mark; raises the
     * mark only after the block or clear is durably applied (or applied
     * fail-closed in memory when a clear cannot yet drop the store record).
     *
     * @return true when a visibility mutation was accepted and applied
     */
    suspend fun reportCompleted(observation: CompletedObservation): Boolean
}

/** Diagnostic / test reporter that never mutates visibility. */
object NoOpVisibilityReporter : VisibilityReporter {
    override suspend fun reportCompleted(observation: CompletedObservation): Boolean = false
}

/**
 * Dedicated access-visibility module (parent #48 / issue #123).
 *
 * Owns identity-wide authentication blocks (and hooks for later namespace /
 * capability scopes). The cache depends on this module for read decisions and
 * does not own the confirmation-state machine (ADR-0044 / ADR-0050).
 *
 * Blocks persist through [CacheStore]; the observation high-water is
 * process-local and empty after reconstruction. Cache hits never report here
 * and cannot clear a block.
 */
internal class AccessVisibility(
    private val store: CacheStore,
    private val highWater: ObservationHighWater = ObservationHighWater(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : VisibilityReporter {
    private val logger = thisLogger()
    private val mutex = Mutex()
    private val identityAuthBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
    private val generation = AtomicLong(0)

    /**
     * Advances when an accepted block or clear lands. Combined into
     * [com.nanyin.nacos.search.services.CacheSnapshot] versions so a derived
     * key index cannot outlive a visibility change.
     */
    fun stateGeneration(): Long = generation.get()

    /**
     * Replaces in-memory records from the store (startup / restart over an
     * injected store). Does not touch the process-local high-water: after
     * reconstruction the first matching success may clear a restored block
     * even with a lower sequence number than the previous process used.
     */
    suspend fun hydrateFromStore() {
        mutex.withLock {
            val loaded = store.loadVisibilityRecords()
            identityAuthBlocks.clear()
            loaded.forEach { (key, record) ->
                if (record.capability == AccessVisibilityRecord.IDENTITY_AUTH) {
                    identityAuthBlocks[key] = record
                }
            }
        }
    }

    /** Read-side decision for configuration data under [identity]. */
    fun configurationVisibility(identity: AccessIdentity): ConfigurationVisibility {
        val record = identityAuthBlocks[VisibilityScopes.identityAuthKey(identity)]
            ?: return ConfigurationVisibility.Visible
        return ConfigurationVisibility.Blocked(
            reason = record.refusal(),
            detail = record.detail
        )
    }

    fun isIdentityAuthBlocked(identity: AccessIdentity): Boolean =
        configurationVisibility(identity) is ConfigurationVisibility.Blocked

    fun identityAuthBlock(identity: AccessIdentity): AccessVisibilityRecord? =
        identityAuthBlocks[VisibilityScopes.identityAuthKey(identity)]

    /**
     * Snapshot of every identity-wide auth block currently held in memory.
     * For tests and lifecycle enumeration; not a public API for UI.
     */
    fun identityAuthBlocks(): Map<String, AccessVisibilityRecord> =
        identityAuthBlocks.toMap()

    override suspend fun reportCompleted(observation: CompletedObservation): Boolean {
        // Cache hits and non-remote outcomes never reach here; still guard.
        if (observation.observation <= 0L) return false
        return when (val outcome = observation.outcome) {
            is ObservationOutcome.Success ->
                acceptSuccess(observation.identity, observation.observation)
            is ObservationOutcome.RemoteFailure ->
                acceptFailure(observation.identity, observation.observation, outcome.error)
        }
    }

    /**
     * Profile-deletion lifecycle: drop every visibility record whose storage
     * key belongs to [profileId], fail-closed in memory first so a store wipe
     * race cannot reveal residual blocked state as "visible with no record".
     * Payload purge is the cache's job; this only drops the gates.
     */
    suspend fun purgeProfile(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        val prefix = "vis|auth|v2|$id|"
        mutex.withLock {
            val keys = identityAuthBlocks.keys.filter { it.startsWith(prefix) }
            if (keys.isEmpty()) {
                // Still reclaim any orphaned store records for this profile.
                store.loadVisibilityRecords().keys
                    .filter { it.startsWith(prefix) }
                    .forEach { key -> runCatching { store.removeVisibilityRecord(key) } }
                return@withLock
            }
            keys.forEach { identityAuthBlocks.remove(it) }
            generation.incrementAndGet()
            keys.forEach { key ->
                runCatching { store.removeVisibilityRecord(key) }
                    .onFailure { logger.warn("Failed to remove visibility record for deleted profile $id: $key", it) }
            }
        }
    }

    /**
     * User clear of the whole cache: drop every visibility record together
     * with the payloads the store is about to wipe. Called from the cache's
     * clear mutation so the two cannot drift.
     */
    suspend fun clearAllRecords() {
        mutex.withLock {
            if (identityAuthBlocks.isNotEmpty()) {
                identityAuthBlocks.clear()
                generation.incrementAndGet()
            }
            // Store clear is owned by CacheStore.clear(); only wipe memory here
            // when the caller has already cleared or will clear the store.
        }
    }

    /**
     * Forget in-memory state after the store has already been cleared (e.g.
     * [CacheStore.clear] reclaimed both payloads and visibility files).
     */
    fun forgetAllInMemory() {
        if (identityAuthBlocks.isNotEmpty()) {
            identityAuthBlocks.clear()
            generation.incrementAndGet()
        }
    }

    private suspend fun acceptSuccess(identity: AccessIdentity, observation: Long): Boolean {
        val scope = listOf(VisibilityScopes.identityAuthScope(identity))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.identityAuthKey(identity)
            val hadBlock = identityAuthBlocks.containsKey(key)
            if (hadBlock) {
                // Fail closed: only reveal after the store record is gone.
                try {
                    store.removeVisibilityRecord(key)
                } catch (e: Exception) {
                    logger.warn("Failed to clear visibility record; keeping block: $key", e)
                    return@withLock false
                }
                identityAuthBlocks.remove(key)
                generation.incrementAndGet()
            }
            // Raise even when there was no block so an older late failure cannot
            // re-block after this newer success (ADR-0020).
            highWater.raise(scope.last(), observation)
            hadBlock
        }
    }

    private suspend fun acceptFailure(
        identity: AccessIdentity,
        observation: Long,
        error: RemoteOperationError
    ): Boolean {
        val reason = identityAuthReason(error) ?: return false
        val scope = listOf(VisibilityScopes.identityAuthScope(identity))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.identityAuthKey(identity)
            val record = AccessVisibilityRecord.identityAuth(
                identity = identity,
                reason = reason,
                detail = error.message,
                recordedAtMillis = currentTimeMillis()
            )
            // Memory first: fail closed even if the store write throws.
            identityAuthBlocks[key] = record
            generation.incrementAndGet()
            try {
                store.putVisibilityRecord(key, record)
            } catch (e: Exception) {
                logger.warn("Failed to persist visibility record; keeping in-memory block: $key", e)
            }
            highWater.raise(scope.last(), observation)
            true
        }
    }

    private fun identityAuthReason(error: RemoteOperationError): AccessRefusalReason? = when (error) {
        is RemoteOperationError.Authentication,
        is RemoteOperationError.InvalidOrExpiredNacosPasswordToken -> AccessRefusalReason.AUTHENTICATION
        // Namespace / capability authorization blocks are #124–#125.
        else -> null
    }
}
