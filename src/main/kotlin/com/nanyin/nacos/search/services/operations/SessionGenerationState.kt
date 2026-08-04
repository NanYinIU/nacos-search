package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode

/**
 * Capture of the non-secret inputs that bound a formal AUTO detection result
 * to a project session. Secrets never appear here.
 *
 * Concurrent formal probes share a flight keyed by [GenerationProbeKey]; each
 * consumer rechecks this capture (plus the live session epoch and tombstone)
 * before committing a shared result into its own session.
 */
data class GenerationSessionCapture(
    val profileId: String,
    val profileRevision: Long,
    val accessRevision: Long,
    val sessionEpoch: Long,
    val canonicalEndpoint: String,
    val authMode: AuthMode,
    val principal: String
)

/**
 * Application-level single-flight key: complete non-secret probe inputs.
 * Isolated diagnostics never use this key.
 */
data class GenerationProbeKey(
    val profileId: String,
    val profileRevision: Long,
    val accessRevision: Long,
    val canonicalEndpoint: String,
    val authMode: AuthMode,
    val principal: String
) {
    companion object {
        fun from(capture: GenerationSessionCapture): GenerationProbeKey =
            GenerationProbeKey(
                profileId = capture.profileId,
                profileRevision = capture.profileRevision,
                accessRevision = capture.accessRevision,
                canonicalEndpoint = capture.canonicalEndpoint,
                authMode = capture.authMode,
                principal = capture.principal
            )
    }
}

/**
 * In-memory resolved API generation for one project session.
 *
 * Not persisted. Assigning a *different* generation advances the session epoch
 * via [onGenerationChanged]. Profile / access revision mismatches prevent both
 * reuse and commit; a consumer whose captured session epoch no longer matches
 * (or whose profile is entombed) does not commit a shared probe result.
 *
 * Epoch numbers are owned by the surrounding [com.nanyin.nacos.search.services.SessionEpochRegistry]
 * (or a test double) and supplied through [currentEpoch].
 */
class SessionGenerationState(
    private val currentEpoch: () -> Long,
    private val isEntombed: (profileId: String) -> Boolean = { false },
    private val onGenerationChanged: () -> Unit = {}
) {
    @Volatile
    private var bound: Bound? = null

    private data class Bound(
        val generation: NacosApiGeneration,
        val profileId: String,
        val profileRevision: Long,
        val accessRevision: Long,
        val canonicalEndpoint: String,
        val authMode: AuthMode,
        val principal: String
    )

    /** Live session epoch used to stamp a consumer capture before probing. */
    fun snapshotEpoch(): Long = currentEpoch()

    /**
     * Returns the session's resolved generation when it still matches the
     * supplied profile identity keys. Session epoch is intentionally not part
     * of the reuse key: a namespace switch advances the epoch without invalidating
     * the server generation (ADR-0006 / ADR-0010).
     */
    fun resolvedIfCompatible(
        profileId: String,
        profileRevision: Long,
        accessRevision: Long
    ): NacosApiGeneration? {
        val current = bound ?: return null
        if (isEntombed(profileId)) return null
        if (current.profileId != profileId) return null
        if (current.profileRevision != profileRevision) return null
        if (current.accessRevision != accessRevision) return null
        return current.generation
    }

    /**
     * Live non-secret identity keys re-read at commit time. ADR-0034 requires
     * each consumer to recheck these against its capture before writing the
     * session or persisting last-known generation.
     */
    data class LiveIdentityKeys(
        val profileId: String,
        val profileRevision: Long,
        val accessRevision: Long
    )

    /**
     * Commits a shared probe result into this session when the consumer's
     * capture still matches live identity keys, the session epoch, and the
     * profile is not entombed (ADR-0034).
     *
     * @return true when the generation was written (or already equal);
     *         false when the consumer must discard the result.
     */
    fun tryCommit(
        generation: NacosApiGeneration,
        capture: GenerationSessionCapture,
        live: LiveIdentityKeys
    ): Boolean {
        if (generation != NacosApiGeneration.V1 && generation != NacosApiGeneration.V3) return false
        if (isEntombed(capture.profileId) || isEntombed(live.profileId)) return false
        if (currentEpoch() != capture.sessionEpoch) return false
        // Independent recheck of the consumer capture against live settings.
        if (capture.profileId != live.profileId) return false
        if (capture.profileRevision != live.profileRevision) return false
        if (capture.accessRevision != live.accessRevision) return false

        val existing = bound
        // Same generation already bound for the same identity keys — success,
        // no epoch bump.
        if (existing != null &&
            existing.generation == generation &&
            existing.profileId == capture.profileId &&
            existing.profileRevision == capture.profileRevision &&
            existing.accessRevision == capture.accessRevision
        ) {
            return true
        }

        val previousGeneration = existing
            ?.takeIf {
                it.profileId == capture.profileId &&
                    it.profileRevision == capture.profileRevision &&
                    it.accessRevision == capture.accessRevision
            }
            ?.generation

        bound = Bound(
            generation = generation,
            profileId = capture.profileId,
            profileRevision = capture.profileRevision,
            accessRevision = capture.accessRevision,
            canonicalEndpoint = capture.canonicalEndpoint,
            authMode = capture.authMode,
            principal = capture.principal
        )
        // Only a *different* generation under the same identity keys advances
        // the session epoch (ADR-0010). A first assignment or a revision-key
        // change does not.
        if (previousGeneration != null && previousGeneration != generation) {
            onGenerationChanged()
        }
        return true
    }

    /** Clears the in-memory generation (profile switch / test teardown). */
    fun clear() {
        bound = null
    }

    /** Test/inspection helper — never used as a remote-authorization signal. */
    fun peekResolvedGeneration(): NacosApiGeneration? = bound?.generation
}
