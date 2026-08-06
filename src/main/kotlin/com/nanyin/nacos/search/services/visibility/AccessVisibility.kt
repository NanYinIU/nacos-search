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
 * Capability blocks (publish / discovery / history, issue #125) affect **only
 * their own capability state**: configuration-list, detail, local-search, and
 * snapshot surfaces never consult them, so a write or optional-capability
 * denial cannot hide data the user can still read. A success clears only the
 * matching capability and scope, never an unrelated configuration-read or
 * authentication block.
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
    private val namespaceConfigReadBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
    private val publishAuthBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
    private val discoveryAuthBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
    private val historyAuthBlocks = ConcurrentHashMap<String, AccessVisibilityRecord>()
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
            namespaceConfigReadBlocks.clear()
            publishAuthBlocks.clear()
            discoveryAuthBlocks.clear()
            historyAuthBlocks.clear()
            loaded.forEach { (key, record) ->
                when (record.capability) {
                    AccessVisibilityRecord.IDENTITY_AUTH -> identityAuthBlocks[key] = record
                    AccessVisibilityRecord.CONFIGURATION_READ -> namespaceConfigReadBlocks[key] = record
                    AccessVisibilityRecord.PUBLISH -> publishAuthBlocks[key] = record
                    AccessVisibilityRecord.NAMESPACE_DISCOVERY -> discoveryAuthBlocks[key] = record
                    AccessVisibilityRecord.HISTORY -> historyAuthBlocks[key] = record
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
        val nsRecord = namespaceConfigReadBlocks[VisibilityScopes.configurationReadKey(identity, ns)]
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
        namespaceConfigReadBlocks[VisibilityScopes.configurationReadKey(identity, namespaceId)]

    // --- Optional-capability blocks (issue #125) ---
    //
    // Capability state only: configuration-read surfaces never consult these
    // maps. A matching newer success clears only its own capability + scope.

    fun publishAuthBlock(identity: AccessIdentity, namespaceId: String): AccessVisibilityRecord? =
        publishAuthBlocks[VisibilityScopes.publishKey(identity, namespaceId)]

    fun discoveryAuthBlock(identity: AccessIdentity): AccessVisibilityRecord? =
        discoveryAuthBlocks[VisibilityScopes.discoveryKey(identity)]

    fun historyAuthBlock(identity: AccessIdentity, namespaceId: String): AccessVisibilityRecord? =
        historyAuthBlocks[VisibilityScopes.historyKey(identity, namespaceId)]

    /**
     * Snapshot of every identity-wide auth block currently held in memory.
     * For tests and lifecycle enumeration; not a public API for UI.
     */
    fun identityAuthBlocks(): Map<String, AccessVisibilityRecord> =
        identityAuthBlocks.toMap()

    /** Snapshot of every Namespace-scoped configuration-read authorization block. */
    fun namespaceConfigReadBlocks(): Map<String, AccessVisibilityRecord> =
        namespaceConfigReadBlocks.toMap()

    /** Snapshot of every publish authorization block (capability state only). */
    fun publishAuthBlocks(): Map<String, AccessVisibilityRecord> =
        publishAuthBlocks.toMap()

    /** Snapshot of every namespace-discovery authorization block (capability state only). */
    fun discoveryAuthBlocks(): Map<String, AccessVisibilityRecord> =
        discoveryAuthBlocks.toMap()

    /** Snapshot of every history authorization block (capability state only). */
    fun historyAuthBlocks(): Map<String, AccessVisibilityRecord> =
        historyAuthBlocks.toMap()

    override suspend fun reportCompleted(observation: CompletedObservation): Boolean {
        // Cache hits and non-remote outcomes never reach here; still guard.
        if (observation.observation <= 0L) return false
        return when (val outcome = observation.outcome) {
            is ObservationOutcome.Success ->
                acceptSuccess(
                    observation.identity,
                    observation.observation,
                    observation.operationClass,
                    observation.namespaceId
                )
            is ObservationOutcome.RemoteFailure ->
                acceptFailure(
                    observation.identity,
                    observation.observation,
                    observation.operationClass,
                    observation.namespaceId,
                    outcome.error
                )
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
        val prefixes = listOf(
            "vis|auth|v2|$id|",
            "vis|cfgread|v2|$id|",
            "vis|publish|v2|$id|",
            "vis|discovery|v2|$id|",
            "vis|history|v2|$id|"
        )
        mutex.withLock {
            val memoryKeys = (
                identityAuthBlocks.keys +
                    namespaceConfigReadBlocks.keys +
                    publishAuthBlocks.keys +
                    discoveryAuthBlocks.keys +
                    historyAuthBlocks.keys
                ).filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
            if (memoryKeys.isEmpty()) {
                // Still reclaim any orphaned store records for this profile.
                store.loadVisibilityRecords().keys
                    .filter { key -> prefixes.any { key.startsWith(it) } }
                    .forEach { key -> runCatching { store.removeVisibilityRecord(key) } }
                return@withLock
            }
            memoryKeys.forEach { key ->
                identityAuthBlocks.remove(key)
                namespaceConfigReadBlocks.remove(key)
                publishAuthBlocks.remove(key)
                discoveryAuthBlocks.remove(key)
                historyAuthBlocks.remove(key)
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
            val hadBlocks = identityAuthBlocks.isNotEmpty() ||
                namespaceConfigReadBlocks.isNotEmpty() ||
                publishAuthBlocks.isNotEmpty() ||
                discoveryAuthBlocks.isNotEmpty() ||
                historyAuthBlocks.isNotEmpty()
            if (hadBlocks) {
                identityAuthBlocks.clear()
                namespaceConfigReadBlocks.clear()
                publishAuthBlocks.clear()
                discoveryAuthBlocks.clear()
                historyAuthBlocks.clear()
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

    private suspend fun acceptSuccess(
        identity: AccessIdentity,
        observation: Long,
        operationClass: ProtocolCapability,
        namespaceId: String?
    ): Boolean {
        return mutex.withLock {
            // Decide both halves against the *current* marks before raising any.
            // Raising the identity mark first would make the capability chain
            // (which includes that mark) reject the same observation with
            // sequence <= mark (ADR-0020 / issue #124).
            val identityScope = identityAuthScopeChain(identity)
            val identityAccepted = highWater.accepts(identityScope, observation)

            val ns = namespaceIdForScope(operationClass)?.let { NamespaceInfo.canonicalId(namespaceId) }
            val capabilityChain = capabilityChainFor(operationClass, identity, ns)
            val capabilityAccepted = capabilityChain != null && highWater.accepts(capabilityChain, observation)

            if (!identityAccepted && !capabilityAccepted) return@withLock false

            // Any authenticated success under the same complete identity clears
            // an identity-wide authentication block (ADR-0019 / issue #123).
            if (identityAccepted) {
                val key = VisibilityScopes.identityAuthKey(identity)
                if (identityAuthBlocks.containsKey(key)) {
                    try {
                        store.removeVisibilityRecord(key)
                    } catch (e: Exception) {
                        logger.warn("Failed to clear visibility record; keeping block: $key", e)
                        return@withLock false
                    }
                    identityAuthBlocks.remove(key)
                    generation.incrementAndGet()
                }
            }

            // A success clears only the matching capability and scope (issue
            // #124 / #125): a configuration-read success clears that identity +
            // Namespace read block, a publish success only its publish block,
            // and so on. No success of one capability can clear another
            // capability's block; authenticated contact clears nothing
            // capability-scoped (it only proves authentication).
            if (capabilityAccepted && capabilityChain != null) {
                val key = capabilityBlockKeyFor(operationClass, identity, ns)
                val blocks = capabilityBlocksFor(operationClass)
                if (key != null && blocks != null && blocks.containsKey(key)) {
                    try {
                        store.removeVisibilityRecord(key)
                    } catch (e: Exception) {
                        logger.warn("Failed to clear capability visibility record; keeping block: $key", e)
                        // Identity clear (if any) already landed; still fail
                        // closed for the capability half so we do not raise its
                        // mark. Raise the identity mark alone when that half
                        // was accepted.
                        if (identityAccepted) {
                            highWater.raise(identityScope.last(), observation)
                        }
                        return@withLock identityAccepted
                    }
                    blocks.remove(key)
                    generation.incrementAndGet()
                }
            }

            // Raise only after durable apply. Raise both when both accepted so a
            // single success advances identity and capability ordering together.
            if (identityAccepted) {
                highWater.raise(identityScope.last(), observation)
            }
            if (capabilityAccepted && capabilityChain != null) {
                highWater.raise(capabilityChain.last(), observation)
            }
            true
        }
    }

    private suspend fun acceptFailure(
        identity: AccessIdentity,
        observation: Long,
        operationClass: ProtocolCapability,
        namespaceId: String?,
        error: RemoteOperationError
    ): Boolean {
        identityAuthReason(error)?.let { reason ->
            return applyIdentityAuthBlock(identity, observation, reason, error.message)
        }
        // Authorization denials scope to the capability that was denied
        // (issue #48 / #124 / #125). Configuration-read, publish, and history
        // are additionally Namespace-scoped; discovery is identity-scoped. An
        // optional-capability denial never creates a configuration-read or
        // authentication block, so readable data stays visible.
        if (error !is RemoteOperationError.Authorization) return false
        return when (operationClass) {
            ProtocolCapability.CONFIGURATION_READ -> applyNamespaceConfigReadBlock(
                identity, observation, namespaceId ?: return false, error.message
            )
            ProtocolCapability.PUBLISH -> applyPublishAuthBlock(
                identity, observation, namespaceId ?: return false, error.message
            )
            ProtocolCapability.NAMESPACE_DISCOVERY ->
                applyDiscoveryAuthBlock(identity, observation, error.message)
            ProtocolCapability.HISTORY -> applyHistoryAuthBlock(
                identity, observation, namespaceId ?: return false, error.message
            )
            // A probe denial proves nothing about a capability; it never
            // creates a block.
            ProtocolCapability.AUTHENTICATED_CONTACT -> false
        }
    }

    private suspend fun applyIdentityAuthBlock(
        identity: AccessIdentity,
        observation: Long,
        reason: AccessRefusalReason,
        detail: String?
    ): Boolean {
        val scope = identityAuthScopeChain(identity)
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.identityAuthKey(identity)
            val record = AccessVisibilityRecord.identityAuth(
                identity = identity,
                reason = reason,
                detail = detail,
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

    private suspend fun applyNamespaceConfigReadBlock(
        identity: AccessIdentity,
        observation: Long,
        namespaceId: String,
        detail: String?
    ): Boolean {
        val ns = NamespaceInfo.canonicalId(namespaceId)
        val scope = capabilityScopeChain(identity, VisibilityScopes.configurationReadScope(identity, ns))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.configurationReadKey(identity, ns)
            val record = AccessVisibilityRecord.configurationReadAuthz(
                identity = identity,
                namespaceId = ns,
                detail = detail,
                recordedAtMillis = currentTimeMillis()
            )
            namespaceConfigReadBlocks[key] = record
            generation.incrementAndGet()
            try {
                store.putVisibilityRecord(key, record)
            } catch (e: Exception) {
                logger.warn("Failed to persist namespace visibility record; keeping in-memory block: $key", e)
            }
            highWater.raise(scope.last(), observation)
            true
        }
    }

    /** Publish authorization block: identity + Namespace capability state only (issue #125). */
    private suspend fun applyPublishAuthBlock(
        identity: AccessIdentity,
        observation: Long,
        namespaceId: String,
        detail: String?
    ): Boolean {
        val ns = NamespaceInfo.canonicalId(namespaceId)
        val scope = capabilityScopeChain(identity, VisibilityScopes.publishScope(identity, ns))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.publishKey(identity, ns)
            val record = AccessVisibilityRecord.publishAuthz(
                identity = identity,
                namespaceId = ns,
                detail = detail,
                recordedAtMillis = currentTimeMillis()
            )
            publishAuthBlocks[key] = record
            generation.incrementAndGet()
            try {
                store.putVisibilityRecord(key, record)
            } catch (e: Exception) {
                logger.warn("Failed to persist publish visibility record; keeping in-memory block: $key", e)
            }
            highWater.raise(scope.last(), observation)
            true
        }
    }

    /** Namespace-discovery authorization block: identity-scoped capability state only (issue #125). */
    private suspend fun applyDiscoveryAuthBlock(
        identity: AccessIdentity,
        observation: Long,
        detail: String?
    ): Boolean {
        val scope = capabilityScopeChain(identity, VisibilityScopes.discoveryScope(identity))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.discoveryKey(identity)
            val record = AccessVisibilityRecord.discoveryAuthz(
                identity = identity,
                detail = detail,
                recordedAtMillis = currentTimeMillis()
            )
            discoveryAuthBlocks[key] = record
            generation.incrementAndGet()
            try {
                store.putVisibilityRecord(key, record)
            } catch (e: Exception) {
                logger.warn("Failed to persist discovery visibility record; keeping in-memory block: $key", e)
            }
            highWater.raise(scope.last(), observation)
            true
        }
    }

    /** History authorization block: identity + Namespace capability state only (issue #125). */
    private suspend fun applyHistoryAuthBlock(
        identity: AccessIdentity,
        observation: Long,
        namespaceId: String,
        detail: String?
    ): Boolean {
        val ns = NamespaceInfo.canonicalId(namespaceId)
        val scope = capabilityScopeChain(identity, VisibilityScopes.historyScope(identity, ns))
        return mutex.withLock {
            if (!highWater.accepts(scope, observation)) return@withLock false
            val key = VisibilityScopes.historyKey(identity, ns)
            val record = AccessVisibilityRecord.historyAuthz(
                identity = identity,
                namespaceId = ns,
                detail = detail,
                recordedAtMillis = currentTimeMillis()
            )
            historyAuthBlocks[key] = record
            generation.incrementAndGet()
            try {
                store.putVisibilityRecord(key, record)
            } catch (e: Exception) {
                logger.warn("Failed to persist history visibility record; keeping in-memory block: $key", e)
            }
            highWater.raise(scope.last(), observation)
            true
        }
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
     * outrank every mark on the chain; it raises only its own (last) mark. The
     * same chain shape serves configuration-read, publish, discovery, and
     * history authorization; only the leaf scope differs.
     */
    private fun capabilityScopeChain(identity: AccessIdentity, capabilityScope: String): List<String> = listOf(
        GLOBAL_CLEAR_SCOPE,
        VisibilityScopes.identityAuthScope(identity),
        capabilityScope
    )

    /** True when [operationClass]'s block is scoped to a canonical Namespace. */
    private fun namespaceIdForScope(operationClass: ProtocolCapability): Boolean = when (operationClass) {
        ProtocolCapability.CONFIGURATION_READ,
        ProtocolCapability.PUBLISH,
        ProtocolCapability.HISTORY -> true
        ProtocolCapability.NAMESPACE_DISCOVERY,
        ProtocolCapability.AUTHENTICATED_CONTACT -> false
    }

    /**
     * The success-clear chain for [operationClass] under [identity] and the
     * canonical [namespaceId] (null when the class is not Namespace-scoped or
     * no coordinate was reported). Null when the class has no capability scope
     * ([AUTHENTICATED_CONTACT]) or the coordinate is missing.
     */
    private fun capabilityChainFor(
        operationClass: ProtocolCapability,
        identity: AccessIdentity,
        namespaceId: String?
    ): List<String>? = when (operationClass) {
        ProtocolCapability.CONFIGURATION_READ ->
            namespaceId?.let { capabilityScopeChain(identity, VisibilityScopes.configurationReadScope(identity, it)) }
        ProtocolCapability.PUBLISH ->
            namespaceId?.let { capabilityScopeChain(identity, VisibilityScopes.publishScope(identity, it)) }
        ProtocolCapability.NAMESPACE_DISCOVERY ->
            capabilityScopeChain(identity, VisibilityScopes.discoveryScope(identity))
        ProtocolCapability.HISTORY ->
            namespaceId?.let { capabilityScopeChain(identity, VisibilityScopes.historyScope(identity, it)) }
        ProtocolCapability.AUTHENTICATED_CONTACT -> null
    }

    /** The storage-key prefix for [operationClass]'s clear target, or null when not capability-scoped. */
    private fun capabilityBlockKeyFor(
        operationClass: ProtocolCapability,
        identity: AccessIdentity,
        namespaceId: String?
    ): String? = when (operationClass) {
        ProtocolCapability.CONFIGURATION_READ ->
            namespaceId?.let { VisibilityScopes.configurationReadKey(identity, it) }
        ProtocolCapability.PUBLISH ->
            namespaceId?.let { VisibilityScopes.publishKey(identity, it) }
        ProtocolCapability.NAMESPACE_DISCOVERY ->
            VisibilityScopes.discoveryKey(identity)
        ProtocolCapability.HISTORY ->
            namespaceId?.let { VisibilityScopes.historyKey(identity, it) }
        ProtocolCapability.AUTHENTICATED_CONTACT -> null
    }

    /** The in-memory block map [operationClass] clears on success, or null when not capability-scoped. */
    private fun capabilityBlocksFor(operationClass: ProtocolCapability): ConcurrentHashMap<String, AccessVisibilityRecord>? =
        when (operationClass) {
            ProtocolCapability.CONFIGURATION_READ -> namespaceConfigReadBlocks
            ProtocolCapability.PUBLISH -> publishAuthBlocks
            ProtocolCapability.NAMESPACE_DISCOVERY -> discoveryAuthBlocks
            ProtocolCapability.HISTORY -> historyAuthBlocks
            ProtocolCapability.AUTHENTICATED_CONTACT -> null
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
