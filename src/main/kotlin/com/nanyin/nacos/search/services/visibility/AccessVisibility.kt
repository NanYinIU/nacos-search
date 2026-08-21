package com.nanyin.nacos.search.services.visibility

import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheStore
import com.nanyin.nacos.search.services.operations.ObservationHighWater
import com.nanyin.nacos.search.services.operations.ProtocolCapability
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
interface VisibilityReporter {
    /**
     * Single mutation entry point. Accepts the observation only when its
     * start-time sequence outranks every relevant high-water mark; raises the
     * mark only after the block or clear is durably applied (or applied
     * fail-closed in memory when a clear cannot yet drop the store record).
     *
     * @return true when the observation was **accepted** (high-water raised
     * and any applicable block/clear mutation applied). A success that only
     * advances ordering with no prior block still returns true.
     */
    suspend fun reportCompleted(observation: CompletedObservation): Boolean

    /**
     * True when an identity-wide authentication block hides authenticated
     * data surfaces for [identity] (configuration cache and history cache hits).
     */
    fun isIdentityAuthBlocked(identity: AccessIdentity): Boolean = false
}

/** Diagnostic / test reporter that never mutates visibility. */
object NoOpVisibilityReporter : VisibilityReporter {
    override suspend fun reportCompleted(observation: CompletedObservation): Boolean = false
}

/**
 * Dedicated access-visibility module (parent #48 / issues #123–#125).
 *
 * Owns identity-wide authentication blocks, Namespace-scoped configuration-
 * read authorization blocks, and capability-scoped authorization blocks for
 * publishing, Namespace discovery, and history. The cache depends on this
 * module for read decisions and does not own the confirmation-state machine
 * (ADR-0044 / ADR-0050).
 *
 * Capability blocks (configuration-read, publish, discovery, history) affect
 * **only their own capability state**. Configuration-list, detail, local-search,
 * and snapshot surfaces consult configuration-read (and identity-wide auth)
 * only, so a write or optional-capability denial cannot hide data the user can
 * still read (issue #125). Apply and clear for those four capabilities share
 * one internal pipeline (issue #237): high-water check and durable-store
 * ordering run once per accepted mutation. A success clears only the matching
 * capability and scope.
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
    /** Capability-scoped records keyed by [VisibilityScopes.capabilityKey]. */
    private val capabilityBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
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
            capabilityBlocks.clear()
            loaded.forEach { (key, record) ->
                when (record.capability) {
                    AccessVisibilityRecord.IDENTITY_AUTH -> identityAuthBlocks[key] = record
                    AccessVisibilityRecord.CONFIGURATION_READ,
                    AccessVisibilityRecord.PUBLISH,
                    AccessVisibilityRecord.NAMESPACE_DISCOVERY,
                    AccessVisibilityRecord.HISTORY -> capabilityBlocks[key] = record
                }
            }
        }
    }

    /**
     * Identity-wide configuration-read visibility (authentication block only).
     * Does not consult Namespace-scoped authorization; use the overload with a
     * Namespace for coordinate-scoped cache reads (issue #124).
     */
    fun configurationVisibility(identity: AccessIdentity): ConfigurationVisibility {
        val identityRecord = identityAuthBlocks[VisibilityScopes.identityAuthKey(identity)]
            ?: return ConfigurationVisibility.Visible
        return ConfigurationVisibility.Blocked(
            reason = identityRecord.refusal(),
            detail = identityRecord.detail
        )
    }

    /**
     * Configuration-read visibility for [identity] and a Namespace coordinate.
     * Null, blank, and `public` all name the public Namespace (ADR-0015).
     *
     * Identity-wide authentication outranks a Namespace authorization block:
     * when the whole identity is refused, every Namespace is hidden. A Namespace
     * authorization block never hides another Namespace or another identity.
     */
    fun configurationVisibility(
        identity: AccessIdentity,
        namespaceId: String?
    ): ConfigurationVisibility {
        val identityDecision = configurationVisibility(identity)
        if (identityDecision is ConfigurationVisibility.Blocked) return identityDecision
        val ns = NamespaceInfo.canonicalId(namespaceId)
        val nsRecord = capabilityBlocks[VisibilityScopes.configurationReadKey(identity, ns)]
            ?: return ConfigurationVisibility.Visible
        return ConfigurationVisibility.Blocked(
            reason = nsRecord.refusal(),
            detail = nsRecord.detail
        )
    }

    override fun isIdentityAuthBlocked(identity: AccessIdentity): Boolean =
        identityAuthBlocks.containsKey(VisibilityScopes.identityAuthKey(identity))

    /**
     * True when configuration-read data for [identity] and [namespaceId] is
     * hidden by either an identity-wide authentication block or a Namespace-
     * scoped authorization block (issue #124). [namespaceId] is canonicalized
     * (null / blank / public → public).
     */
    fun isConfigurationReadBlocked(identity: AccessIdentity, namespaceId: String?): Boolean =
        configurationVisibility(identity, namespaceId) is ConfigurationVisibility.Blocked

    fun identityAuthBlock(identity: AccessIdentity): AccessVisibilityRecord? =
        identityAuthBlocks[VisibilityScopes.identityAuthKey(identity)]

    fun namespaceConfigReadBlock(identity: AccessIdentity, namespaceId: String): AccessVisibilityRecord? =
        capabilityBlocks[VisibilityScopes.configurationReadKey(identity, namespaceId)]

    // --- Optional-capability blocks (issue #125) ---
    //
    // Capability state only: configuration-read surfaces never consult these
    // records. A matching newer success clears only its own capability + scope.

    fun publishAuthBlock(identity: AccessIdentity, namespaceId: String): AccessVisibilityRecord? =
        capabilityBlocks[VisibilityScopes.publishKey(identity, namespaceId)]

    fun discoveryAuthBlock(identity: AccessIdentity): AccessVisibilityRecord? =
        capabilityBlocks[VisibilityScopes.discoveryKey(identity)]

    fun historyAuthBlock(identity: AccessIdentity, namespaceId: String): AccessVisibilityRecord? =
        capabilityBlocks[VisibilityScopes.historyKey(identity, namespaceId)]

    /**
     * Canonical Namespace ids whose configuration reads are authorization-
     * blocked for [identity]. Empty when the identity-wide authentication gate
     * hides everything (that decision lives on [configurationVisibility]) or
     * nothing is blocked. Cheap: one pass over the in-memory block map, which
     * is bounded by identities × refused Namespaces.
     *
     * Consumed by [com.nanyin.nacos.search.services.CacheService.snapshot] so
     * a derived key index can record the visibility state it was built under
     * and refuse to serve a snapshot that hides different Namespaces
     * (issue #126).
     */
    fun configurationReadBlockedNamespaces(identity: AccessIdentity): Set<String> {
        val prefix = VisibilityScopes.configurationReadKeyPrefix(identity)
        return capabilityBlocks.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapTo(linkedSetOf()) { it.removePrefix(prefix) }
    }

    /**
     * Snapshot of every identity-wide auth block currently held in memory.
     * For tests and lifecycle enumeration; not a public API for UI.
     */
    fun identityAuthBlocks(): Map<String, AccessVisibilityRecord> =
        identityAuthBlocks.toMap()

    /** Snapshot of every Namespace-scoped configuration-read authorization block. */
    fun namespaceConfigReadBlocks(): Map<String, AccessVisibilityRecord> =
        capabilityBlocks.filterValues { it.capability == AccessVisibilityRecord.CONFIGURATION_READ }

    /** Snapshot of every publish authorization block (capability state only). */
    fun publishAuthBlocks(): Map<String, AccessVisibilityRecord> =
        capabilityBlocks.filterValues { it.capability == AccessVisibilityRecord.PUBLISH }

    /** Snapshot of every namespace-discovery authorization block (capability state only). */
    fun discoveryAuthBlocks(): Map<String, AccessVisibilityRecord> =
        capabilityBlocks.filterValues { it.capability == AccessVisibilityRecord.NAMESPACE_DISCOVERY }

    /** Snapshot of every history authorization block (capability state only). */
    fun historyAuthBlocks(): Map<String, AccessVisibilityRecord> =
        capabilityBlocks.filterValues { it.capability == AccessVisibilityRecord.HISTORY }

    override suspend fun reportCompleted(observation: CompletedObservation): Boolean {
        // Cache hits and non-remote outcomes never reach here; still guard.
        if (observation.observation <= 0L) return false
        return mutex.withLock {
            when (val outcome = observation.outcome) {
                is ObservationOutcome.Success ->
                    applySuccessLocked(
                        observation.identity,
                        observation.observation,
                        observation.operationClass,
                        observation.namespaceId
                    )
                is ObservationOutcome.RemoteFailure ->
                    applyFailureLocked(
                        observation.identity,
                        observation.observation,
                        observation.operationClass,
                        observation.namespaceId,
                        outcome.error
                    )
            }
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
        val prefixes = VisibilityScopes.profileKeyPrefixes(id)
        mutex.withLock {
            val memoryKeys = (identityAuthBlocks.keys + capabilityBlocks.keys)
                .filter { key -> prefixes.any { key.startsWith(it) } }
                .toSet()
            if (memoryKeys.isEmpty()) {
                // Still reclaim any orphaned store records for this profile.
                store.loadVisibilityRecords().keys
                    .filter { key -> prefixes.any { key.startsWith(it) } }
                    .forEach { key -> runCatching { store.removeVisibilityRecord(key) } }
                return@withLock
            }
            memoryKeys.forEach { key ->
                identityAuthBlocks.remove(key)
                capabilityBlocks.remove(key)
            }
            generation.incrementAndGet()
            memoryKeys.forEach { key ->
                runCatching { store.removeVisibilityRecord(key) }
                    .onFailure { logger.warn("Failed to remove visibility record for deleted profile $id: $key", it) }
            }
        }
    }

    /**
     * User clear of the whole cache: drop every in-memory visibility block and
     * fence the visibility high-water with [observation] so an older late
     * failure cannot re-block after this clear (ADR-0020).
     *
     * Call **before** [CacheStore.clear]: fencing first closes the race where a
     * concurrent [reportCompleted] failure re-puts a visibility file after the
     * wipe and before the fence (orphan re-block on restart). The subsequent
     * store clear drops any put that completed fully before this fence.
     * This is the only forget path for user clear.
     */
    suspend fun onUserClear(observation: Long) {
        mutex.withLock {
            val hadBlocks = identityAuthBlocks.isNotEmpty() || capabilityBlocks.isNotEmpty()
            if (hadBlocks) {
                identityAuthBlocks.clear()
                capabilityBlocks.clear()
                generation.incrementAndGet()
            }
            // Collapse every per-scope mark into the global clear mark so
            // the gate map cannot grow for the life of the IDE and any
            // observation at or below this clear is rejected for block and
            // clear mutations (mirrors CacheService GLOBAL_SCOPE on clear).
            if (observation > 0L) {
                highWater.collapseTo(GLOBAL_CLEAR_SCOPE, observation)
            }
        }
    }

    private suspend fun applySuccessLocked(
        identity: AccessIdentity,
        observation: Long,
        operationClass: ProtocolCapability,
        namespaceId: String?
    ): Boolean {
        // Decide identity against the *current* marks before raising it.
        // Raising the identity mark first would make the capability chain
        // (which includes that mark) reject the same observation with
        // sequence <= mark (ADR-0020 / issue #124). Capability high-water
        // is checked once inside the pipeline.
        val identityScope = identityAuthScopeChain(identity)
        val identityAccepted = highWater.accepts(identityScope, observation)
        val target = capabilityScope(operationClass, identity, namespaceId)

        // Any authenticated success under the same complete identity clears
        // an identity-wide authentication block (ADR-0019 / issue #123).
        if (identityAccepted && !durableRemove(VisibilityScopes.identityAuthKey(identity), identityAuthBlocks)) {
            return false
        }

        // A success clears only the matching capability and scope (issue
        // #124 / #125 / #237). Authenticated contact has no capability target.
        val capabilityLanded = target?.let {
            applyCapabilityMutationLocked(it, observation, CapabilityMutation.Clear)
        } ?: false
        if (!identityAccepted && !capabilityLanded) return false
        if (identityAccepted) {
            highWater.raise(identityScope.last(), observation)
        }
        return true
    }

    private suspend fun applyFailureLocked(
        identity: AccessIdentity,
        observation: Long,
        operationClass: ProtocolCapability,
        namespaceId: String?,
        error: RemoteOperationError
    ): Boolean {
        identityAuthReason(error)?.let { reason ->
            return applyIdentityAuthBlockLocked(identity, observation, reason, error.message)
        }
        // Authorization denials scope to the capability that was denied
        // (issue #48 / #124 / #125 / #237). An optional-capability denial
        // never creates a configuration-read or authentication block.
        if (error !is RemoteOperationError.Authorization) return false
        val target = capabilityScope(operationClass, identity, namespaceId) ?: return false
        return applyCapabilityMutationLocked(
            target,
            observation,
            CapabilityMutation.Block(error.message)
        )
    }

    private suspend fun applyIdentityAuthBlockLocked(
        identity: AccessIdentity,
        observation: Long,
        reason: AccessRefusalReason,
        detail: String?
    ): Boolean {
        val scope = identityAuthScopeChain(identity)
        if (!highWater.accepts(scope, observation)) return false
        val key = VisibilityScopes.identityAuthKey(identity)
        val record = AccessVisibilityRecord.identityAuth(
            identity = identity,
            reason = reason,
            detail = detail,
            recordedAtMillis = currentTimeMillis()
        )
        persistBlock(key, record, identityAuthBlocks)
        highWater.raise(scope.last(), observation)
        return true
    }

    /**
     * One capability-scoped mutation. Caller holds [mutex]. High-water check,
     * durable-store ordering, and raise run here and nowhere else for
     * configuration-read, publish, discovery, and history (issue #237).
     *
     * Returns false when the observation is rejected, or when a durable clear
     * cannot land (fail closed: memory and the capability mark stay put).
     */
    private suspend fun applyCapabilityMutationLocked(
        target: CapabilityScope,
        observation: Long,
        mutation: CapabilityMutation
    ): Boolean {
        if (!highWater.accepts(target.scopeChain, observation)) return false
        when (mutation) {
            is CapabilityMutation.Block -> {
                persistBlock(
                    target.key,
                    AccessVisibilityRecord.capabilityAuthz(
                        identity = target.identity,
                        capability = target.capability,
                        namespaceId = target.namespaceId,
                        detail = mutation.detail,
                        recordedAtMillis = currentTimeMillis()
                    ),
                    capabilityBlocks
                )
            }
            CapabilityMutation.Clear -> {
                if (!durableRemove(target.key, capabilityBlocks)) return false
            }
        }
        highWater.raise(target.scopeChain.last(), observation)
        return true
    }

    /** Memory first: fail closed even if the store write throws. */
    private suspend fun persistBlock(
        key: String,
        record: AccessVisibilityRecord,
        into: ConcurrentHashMap<String, AccessVisibilityRecord>
    ) {
        into[key] = record
        generation.incrementAndGet()
        try {
            store.putVisibilityRecord(key, record)
        } catch (e: Exception) {
            logger.warn("Failed to persist visibility record; keeping in-memory block: $key", e)
        }
    }

    /**
     * Store first: a failed remove must not drop the in-memory block or raise
     * the mark. True when there was nothing to remove or the remove landed.
     */
    private suspend fun durableRemove(
        key: String,
        from: ConcurrentHashMap<String, AccessVisibilityRecord>
    ): Boolean {
        if (!from.containsKey(key)) return true
        try {
            store.removeVisibilityRecord(key)
        } catch (e: Exception) {
            logger.warn("Failed to clear visibility record; keeping block: $key", e)
            return false
        }
        from.remove(key)
        generation.incrementAndGet()
        return true
    }

    private fun identityAuthReason(error: RemoteOperationError): AccessRefusalReason? = when (error) {
        is RemoteOperationError.Authentication,
        is RemoteOperationError.InvalidOrExpiredNacosPasswordToken -> AccessRefusalReason.AUTHENTICATION
        else -> null
    }

    /**
     * Scope chain for identity-wide auth mutations: global clear mark first,
     * then the identity's own mark. A user clear raises only the global mark;
     * an identity mutation raises only its own (last) mark.
     */
    private fun identityAuthScopeChain(identity: AccessIdentity): List<String> = listOf(
        GLOBAL_CLEAR_SCOPE,
        VisibilityScopes.identityAuthScope(identity)
    )

    /**
     * Scope chain for one capability scope: global clear → identity auth mark →
     * capability mark (and optional Namespace) (ADR-0020). A completion must
     * outrank every mark on the chain; it raises only its own (last) mark.
     */
    private fun capabilityScopeChain(identity: AccessIdentity, capabilityScope: String): List<String> = listOf(
        GLOBAL_CLEAR_SCOPE,
        VisibilityScopes.identityAuthScope(identity),
        capabilityScope
    )

    /**
     * Resolve the one capability-scoped visibility gate for [operationClass].
     * Null when the class has no capability record ([AUTHENTICATED_CONTACT])
     * or a Namespace-scoped class was reported without a namespace.
     */
    private fun capabilityScope(
        operationClass: ProtocolCapability,
        identity: AccessIdentity,
        namespaceId: String?
    ): CapabilityScope? {
        val key = VisibilityScopes.capabilityKey(operationClass, identity, namespaceId) ?: return null
        return CapabilityScope(
            identity = identity,
            key = key,
            scopeChain = capabilityScopeChain(identity, key),
            capability = operationClass.name,
            namespaceId = if (VisibilityScopes.namespaceScoped(operationClass)) {
                NamespaceInfo.canonicalId(namespaceId)
            } else {
                null
            }
        )
    }

    private data class CapabilityScope(
        val identity: AccessIdentity,
        val key: String,
        val scopeChain: List<String>,
        val capability: String,
        val namespaceId: String?
    )

    private sealed interface CapabilityMutation {
        data class Block(val detail: String?) : CapabilityMutation
        object Clear : CapabilityMutation
    }

    companion object {
        /**
         * Broadest visibility scope: a user's cache clear collapses every
         * per-identity mark into this one so late older failures cannot re-block
         * after clear and the high-water map stays bounded.
         */
        const val GLOBAL_CLEAR_SCOPE = "vis|*"
    }
}
