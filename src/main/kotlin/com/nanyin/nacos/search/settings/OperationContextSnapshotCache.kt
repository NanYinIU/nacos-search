package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentProfile
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds one prepared [NacosOperationContext] for Swing handlers so they never
 * read PasswordSafe on the EDT (ADR-0039 / issue #53).
 *
 * Async refreshes race when the user switches environments quickly: an older
 * capture must not overwrite a newer one. Each [beginRefresh] bumps a
 * monotonic epoch; [publishIfCurrent] only installs the context when that
 * epoch is still latest **and** the captured profile/revisions still match
 * the caller's currently selected profile.
 */
internal data class PreparedOperationContext(
    val profileId: String,
    val profileRevision: Long,
    val accessRevision: Long,
    val context: NacosOperationContext
) {
    fun matches(selectedProfileId: String, profile: EnvironmentProfile?): Boolean {
        if (profile == null) return false
        if (profileId != selectedProfileId || profile.id != selectedProfileId) return false
        if (profileRevision != profile.profileRevision) return false
        if (accessRevision != profile.accessRevision) return false
        return context.identity.profileId == selectedProfileId &&
            context.profileRevision == profile.profileRevision &&
            context.accessRevision == profile.accessRevision
    }
}

internal class OperationContextSnapshotCache {
    private val epoch = AtomicLong(0)

    @Volatile
    private var snapshot: PreparedOperationContext? = null

    /** Call before starting an async capture. Later [publishIfCurrent] uses the returned epoch. */
    fun beginRefresh(): Long = epoch.incrementAndGet()

    /**
     * Installs [context] only when this refresh is still the latest and the
     * selection has not moved away from [capturedProfileId].
     *
     * @param epochAtStart value from [beginRefresh] for this attempt
     * @param capturedProfileId profile id captured at the start of the refresh
     * @param selectedProfileId profile id at publish time (re-read after IO)
     * @param selectedProfile live profile for revision checks, or null if missing
     */
    fun publishIfCurrent(
        epochAtStart: Long,
        capturedProfileId: String,
        selectedProfileId: String,
        selectedProfile: EnvironmentProfile?,
        context: NacosOperationContext?
    ): Boolean {
        if (context == null) return false
        if (epochAtStart != epoch.get()) return false
        if (capturedProfileId != selectedProfileId) return false
        if (selectedProfile == null || selectedProfile.id != selectedProfileId) return false
        if (context.identity.profileId != selectedProfileId) return false
        if (context.profileRevision != selectedProfile.profileRevision) return false
        if (context.accessRevision != selectedProfile.accessRevision) return false

        snapshot = PreparedOperationContext(
            profileId = selectedProfileId,
            profileRevision = context.profileRevision,
            accessRevision = context.accessRevision,
            context = context
        )
        return true
    }

    /**
     * Returns the prepared context only when it still matches the current
     * selection. A stale or wrong-environment snapshot yields null so the
     * caller can fall back to an off-EDT capture for the live profile.
     */
    fun resolve(
        selectedProfileId: String,
        selectedProfile: EnvironmentProfile?
    ): NacosOperationContext? {
        val prepared = snapshot ?: return null
        return prepared.context.takeIf { prepared.matches(selectedProfileId, selectedProfile) }
    }

    fun invalidate() {
        epoch.incrementAndGet()
        snapshot = null
    }

    /** Test / inspection helper. */
    internal fun currentSnapshot(): PreparedOperationContext? = snapshot

    /** Test helper: latest epoch. */
    internal fun currentEpoch(): Long = epoch.get()
}
