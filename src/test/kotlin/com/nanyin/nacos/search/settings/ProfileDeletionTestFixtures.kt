package com.nanyin.nacos.search.settings

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
