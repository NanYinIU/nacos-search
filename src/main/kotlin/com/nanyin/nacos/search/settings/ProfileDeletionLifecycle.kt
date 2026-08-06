package com.nanyin.nacos.search.settings

/**
 * One fail-closed lifecycle for confirmed environment-profile deletion
 * (ADR-0025 / design §13.6 / issue #105).
 *
 * Ordering is fixed and not re-derived by callers:
 *
 * 1. Persist the profile-deletion tombstone (hard fail → deletion withheld).
 * 2. The tombstone is the common lifecycle guard for context capture, credential
 *    publication, auth-token publication, last-known generation, probe/session
 *    commit, and every cache mutation; async completions recheck it.
 * 3. Move referring project sessions to the profile-unavailable state, advance
 *    their epochs, and clear resolved generation — never auto-select another
 *    environment.
 * 4. Remove credential slots, authentication sessions, last-known generation,
 *    and residual cache entries. Each step is idempotent so interrupted cleanup
 *    may be retried without duplicate lifecycle effects.
 *
 * Invoked only from the profile-store write boundary when a removal is confirmed.
 * **No read path** discovers a missing profile and runs this lifecycle later.
 */
class ProfileDeletionLifecycle(
    private val tombstones: ProfileTombstoneWriter,
    private val cleanup: ProfileDeletionCleanup
) {

    /**
     * Confirmed deletion of [profileId]. Persists the tombstone first; only on
     * success runs cleanup. A tombstone failure returns
     * [ProfileDeletionResult.TombstoneFailed] and must not unpublish the profile.
     *
     * Safe to call again for the same id (retry of interrupted cleanup or a
     * repeated deletion gesture): re-entomb is a no-op success, and every cleanup
     * step is idempotent.
     */
    fun delete(profileId: String): ProfileDeletionResult {
        val id = profileId.trim()
        // Blank is not a valid profile coordinate — refuse rather than report
        // a successful deletion of nothing.
        if (id.isBlank()) return ProfileDeletionResult.TombstoneFailed(profileId)
        if (!tombstones.tryEntomb(id)) {
            return ProfileDeletionResult.TombstoneFailed(id)
        }
        runCleanup(id)
        return ProfileDeletionResult.Deleted(id)
    }

    /**
     * Re-runs cleanup for an already-entombed profile. Does not entomb and does
     * not imply the profile may be unpublished. Used when a prior attempt
     * partially failed after the tombstone landed, and by the host write
     * boundary to drain residuals for entombed ids already absent from the
     * published set (issue #105 AC7).
     */
    fun retryCleanup(profileId: String): ProfileDeletionResult {
        val id = profileId.trim()
        if (id.isBlank()) return ProfileDeletionResult.TombstoneFailed(profileId)
        if (!tombstones.isEntombed(id)) {
            return ProfileDeletionResult.TombstoneFailed(id)
        }
        runCleanup(id)
        return ProfileDeletionResult.Deleted(id)
    }

    /** Lifecycle guard query shared with credential staging / context capture. */
    fun isEntombed(profileId: String): Boolean = tombstones.isEntombed(profileId.trim())

    /** Every currently entombed profile id (residual cleanup drain). */
    fun entombedProfileIds(): Set<String> = tombstones.entombedProfileIds()

    private fun runCleanup(profileId: String) {
        // Design §13.6 steps 3–4. Each step is isolated: a failure must not
        // prevent the others, and must never lift the tombstone.
        runCatching { cleanup.markReferringSessionsUnavailable(profileId) }
        runCatching { cleanup.removeCredentialSlots(profileId) }
        runCatching { cleanup.invalidateAuthentication(profileId) }
        runCatching { cleanup.clearLastKnownGeneration(profileId) }
        runCatching { cleanup.purgeCache(profileId) }
    }
}

/** Outcome of one profile-deletion lifecycle attempt. */
sealed interface ProfileDeletionResult {
    /** Tombstone is durable and cleanup was attempted (possibly partially). */
    data class Deleted(val profileId: String) : ProfileDeletionResult

    /**
     * Tombstone could not be persisted. The profile must remain published and
     * no cleanup for this deletion may run.
     */
    data class TombstoneFailed(val profileId: String) : ProfileDeletionResult
}

/**
 * Persist and query profile-deletion tombstones. [tryEntomb] must return false
 * when the marker cannot be made durable so the write boundary can fail closed.
 */
interface ProfileTombstoneWriter {
    /** Make the tombstone durable. Idempotent when already entombed. */
    fun tryEntomb(profileId: String): Boolean

    fun isEntombed(profileId: String): Boolean

    /** Snapshot of every entombed profile id (for residual cleanup drain). */
    fun entombedProfileIds(): Set<String>
}

/**
 * Post-tombstone cleanup. Every method must be safe to call repeatedly
 * (ADR-0025). Implementations must not re-create credentials, tokens, cache
 * entries, or generation state for an entombed profile.
 */
interface ProfileDeletionCleanup {
    fun markReferringSessionsUnavailable(profileId: String)
    fun removeCredentialSlots(profileId: String)
    fun invalidateAuthentication(profileId: String)
    fun clearLastKnownGeneration(profileId: String)
    fun purgeCache(profileId: String)
}

/**
 * In-memory tombstone writer for unit tests. [failEntomb] forces a durable-write
 * failure so the write boundary can prove it withholds the deletion.
 */
class InMemoryProfileTombstoneWriter(
    private val failEntomb: Boolean = false
) : ProfileTombstoneWriter {
    private val entombed = linkedSetOf<String>()

    val entombedIds: Set<String> get() = entombed.toSet()

    override fun tryEntomb(profileId: String): Boolean {
        if (failEntomb) return false
        entombed += profileId
        return true
    }

    override fun isEntombed(profileId: String): Boolean = profileId in entombed

    override fun entombedProfileIds(): Set<String> = entombed.toSet()
}

/**
 * Recording cleanup adapter for unit tests. Optional failure hooks let a test
 * simulate a partial cleanup that a later [ProfileDeletionLifecycle.delete]
 * retry must complete.
 */
class RecordingProfileDeletionCleanup(
    private val failCredentialRemove: Boolean = false,
    private val failAuthInvalidate: Boolean = false,
    private val failGenerationClear: Boolean = false,
    private val failCachePurge: Boolean = false,
    private val failSessionMark: Boolean = false
) : ProfileDeletionCleanup {
    val sessionsMarked = mutableListOf<String>()
    val credentialsRemoved = mutableListOf<String>()
    val authInvalidated = mutableListOf<String>()
    val generationsCleared = mutableListOf<String>()
    val cachesPurged = mutableListOf<String>()

    override fun markReferringSessionsUnavailable(profileId: String) {
        if (failSessionMark) error("session mark refused")
        sessionsMarked += profileId
    }

    override fun removeCredentialSlots(profileId: String) {
        if (failCredentialRemove) error("credential remove refused")
        credentialsRemoved += profileId
    }

    override fun invalidateAuthentication(profileId: String) {
        if (failAuthInvalidate) error("auth invalidate refused")
        authInvalidated += profileId
    }

    override fun clearLastKnownGeneration(profileId: String) {
        if (failGenerationClear) error("generation clear refused")
        generationsCleared += profileId
    }

    override fun purgeCache(profileId: String) {
        if (failCachePurge) error("cache purge refused")
        cachesPurged += profileId
    }
}
