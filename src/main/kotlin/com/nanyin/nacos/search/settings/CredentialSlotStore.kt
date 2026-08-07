package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * Transactional credential-slot storage (ADR-0035, issue #102).
 *
 * Slots are addressed by environment profile ID and a revision coordinate
 * (the access-revision component of the published pair — today carried on
 * [EnvironmentProfile.credentialSlotVersion], which advances with the access
 * identity whenever the secret changes). A slot is written once when staged
 * and never used as a fallback for a different revision. A failed [stage]
 * must prevent the corresponding profile revision from becoming visible.
 * Secrets never appear in profiles, access identities, logs, observations,
 * or serialized settings.
 *
 * Production uses [PlatformCredentialSlotStore]; tests inject
 * [InMemoryCredentialSlotStore] and inspect its observations.
 */
interface CredentialSlotStore {

    /**
     * Read the slot named by [profileId] and [accessRevision].
     * Returns null when the slot is absent — never a predecessor slot.
     */
    fun read(profileId: String, accessRevision: Long): String?

    /**
     * Stage [secret] under the slot for [profileId] and [accessRevision].
     * Reports [CredentialStageResult.Success] only when the write is durable.
     * Does not replace a different secret already present at that address.
     */
    fun stage(profileId: String, accessRevision: Long, secret: String): CredentialStageResult

    /**
     * Remove every known slot for [profileId]. Safe to call repeatedly.
     * Does not touch slots belonging to other profiles.
     */
    fun removeAllForProfile(profileId: String)

    companion object {
        /** Storage key for the immutable slot at one profile access revision. */
        fun slotKey(profileId: String, accessRevision: Long): String = "$profileId:v$accessRevision"
    }
}

/** Outcome of a staged secret write. Callers must not publish a pending revision on [Failure]. */
sealed interface CredentialStageResult {
    data object Success : CredentialStageResult
    data class Failure(val reason: String) : CredentialStageResult
}

/** Observation records for the in-memory adapter — never carry secret values. */
sealed interface CredentialSlotObservation {
    data class Read(val profileId: String, val accessRevision: Long, val hit: Boolean) : CredentialSlotObservation
    data class Write(val profileId: String, val accessRevision: Long, val succeeded: Boolean) : CredentialSlotObservation
    data class RemoveAll(val profileId: String) : CredentialSlotObservation
}

/**
 * Isolated in-memory adapter for store tests. Tracks reads, writes, and
 * removals without ever recording secret values in the observation log.
 *
 * @param failWrites when true, every stage fails and leaves no durable entry —
 *   used to prove callers cannot publish a pending revision after a failed stage.
 */
class InMemoryCredentialSlotStore(
    private val failWrites: Boolean = false
) : CredentialSlotStore {

    private val values = ConcurrentHashMap<String, String>()
    private val _reads = mutableListOf<CredentialSlotObservation.Read>()
    private val _writes = mutableListOf<CredentialSlotObservation.Write>()
    private val _removals = mutableListOf<CredentialSlotObservation.RemoveAll>()

    val reads: List<CredentialSlotObservation.Read> get() = _reads.toList()
    val writes: List<CredentialSlotObservation.Write> get() = _writes.toList()
    val removals: List<CredentialSlotObservation.RemoveAll> get() = _removals.toList()

    override fun read(profileId: String, accessRevision: Long): String? {
        val key = CredentialSlotStore.slotKey(profileId, accessRevision)
        val value = values[key]
        _reads += CredentialSlotObservation.Read(profileId, accessRevision, hit = value != null)
        return value
    }

    override fun stage(profileId: String, accessRevision: Long, secret: String): CredentialStageResult {
        if (failWrites) {
            _writes += CredentialSlotObservation.Write(profileId, accessRevision, succeeded = false)
            return CredentialStageResult.Failure("credential slot write refused")
        }
        val key = CredentialSlotStore.slotKey(profileId, accessRevision)
        val existing = values[key]
        if (existing != null && existing != secret) {
            _writes += CredentialSlotObservation.Write(profileId, accessRevision, succeeded = false)
            return CredentialStageResult.Failure("credential slot is immutable once published")
        }
        // Empty secrets are valid for ANONYMOUS: durability means the stage
        // decision completed, not that a non-empty payload was stored.
        if (secret.isNotEmpty()) {
            values[key] = secret
        } else {
            values.remove(key)
        }
        _writes += CredentialSlotObservation.Write(profileId, accessRevision, succeeded = true)
        return CredentialStageResult.Success
    }

    override fun removeAllForProfile(profileId: String) {
        val prefix = "$profileId:v"
        values.keys.filter { it.startsWith(prefix) }.forEach { values.remove(it) }
        _removals += CredentialSlotObservation.RemoveAll(profileId)
    }

    /** Test helper: drop one slot without going through stage (simulates external loss). */
    fun remove(profileId: String, accessRevision: Long) {
        values.remove(CredentialSlotStore.slotKey(profileId, accessRevision))
    }
}

/**
 * Production adapter over the platform credential service.
 *
 * [removeAllForProfile] must survive process restarts: PasswordSafe cannot list
 * by prefix, so each successful stage also appends the revision to a durable
 * per-profile index entry (never a secret value). Cleanup is idempotent.
 */
class PlatformCredentialSlotStore(
    private val platform: PlatformCredentialBackend = PasswordSafeCredentialBackend
) : CredentialSlotStore {

    private val knownKeys = ConcurrentHashMap.newKeySet<String>()

    override fun read(profileId: String, accessRevision: Long): String? {
        val key = CredentialSlotStore.slotKey(profileId, accessRevision)
        return platform.get(key)
    }

    override fun stage(profileId: String, accessRevision: Long, secret: String): CredentialStageResult {
        val key = CredentialSlotStore.slotKey(profileId, accessRevision)
        val existing = platform.get(key)
        if (existing != null && existing.isNotEmpty() && existing != secret) {
            return CredentialStageResult.Failure("credential slot is immutable once published")
        }
        if (!platform.set(key, secret)) {
            return CredentialStageResult.Failure("platform credential service rejected the write")
        }
        knownKeys.add(key)
        if (!recordRevision(profileId, accessRevision)) {
            // Slot payload landed but the index did not — still report failure
            // so callers do not publish a revision they cannot later clean up.
            platform.remove(key)
            knownKeys.remove(key)
            return CredentialStageResult.Failure("credential slot index write failed")
        }
        return CredentialStageResult.Success
    }

    override fun removeAllForProfile(profileId: String) {
        val prefix = "$profileId:v"
        val fromIndex = readRevisions(profileId).map { CredentialSlotStore.slotKey(profileId, it) }
        val toRemove = (
            fromIndex +
                knownKeys.filter { it.startsWith(prefix) } +
                platform.knownKeys().filter { it.startsWith(prefix) }
            ).toSet()
        toRemove.forEach { key ->
            platform.remove(key)
            knownKeys.remove(key)
        }
        platform.remove(revisionIndexKey(profileId))
    }

    private fun recordRevision(profileId: String, accessRevision: Long): Boolean {
        val revisions = readRevisions(profileId).toMutableSet()
        revisions.add(accessRevision)
        // The index stores only revision numbers — never secret material.
        return platform.set(revisionIndexKey(profileId), revisions.sorted().joinToString(","))
    }

    private fun readRevisions(profileId: String): Set<Long> =
        platform.get(revisionIndexKey(profileId))
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toSet()
            .orEmpty()

    companion object {
        internal fun revisionIndexKey(profileId: String): String = "$profileId:__slot_revisions__"
    }
}

/**
 * Narrow backend for the platform credential service so the slot store can
 * report write success/failure and enumerate in-process known keys without
 * depending on PasswordSafe listing APIs.
 */
interface PlatformCredentialBackend {
    fun get(key: String): String?
    /** @return true when the write is considered durable. */
    fun set(key: String, secret: String): Boolean
    fun remove(key: String)
    fun knownKeys(): Set<String>
}

/** Default backend: IntelliJ PasswordSafe via [NacosCredentialStore]. */
internal object PasswordSafeCredentialBackend : PlatformCredentialBackend {
    override fun get(key: String): String? = NacosCredentialStore.get(key)
    override fun set(key: String, secret: String): Boolean = NacosCredentialStore.setDurable(key, secret)
    override fun remove(key: String) = NacosCredentialStore.remove(key)
    override fun knownKeys(): Set<String> = NacosCredentialStore.cachedKeys()
}

/**
 * Stages a replacement secret under the slot named by a pending profile.
 * The slot address is the profile's credential-slot version (encoded in
 * [EnvironmentProfile.credentialSlotId]); on a security-boundary edit that
 * version advances with the access revision, so the published pair stays
 * aligned. Callers must publish the profile only after [stage] returns
 * [CredentialStageResult.Success].
 */
class CredentialSlotStager(private val store: CredentialSlotStore) {
    fun stage(profile: EnvironmentProfile, secret: String): CredentialStageResult =
        store.stage(profile.id, profile.credentialSlotVersion, secret)
}

/** Shared production store used by settings write paths. */
internal val DefaultCredentialSlotStore: CredentialSlotStore = PlatformCredentialSlotStore()

