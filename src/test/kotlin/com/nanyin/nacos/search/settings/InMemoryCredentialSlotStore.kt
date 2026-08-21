package com.nanyin.nacos.search.settings

import java.util.concurrent.ConcurrentHashMap

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
