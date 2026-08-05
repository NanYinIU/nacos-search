package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile

/**
 * Authoritative classification of one environment-profile store write
 * (ADR-0049 / issue #103).
 *
 * Downstream callers — settings notifications, session recapture, UI dirty
 * state — must use this outcome instead of independently comparing connection
 * signatures, passwords, list positions, or profile fields.
 *
 * Reordering intents alone is not an operational change and advances no
 * revision. Display-only and preference-only changes appear here without
 * advancing either revision.
 */
data class ProfileStoreWriteOutcome(
    /** Profiles present in the intent set that were not previously published. */
    val addedProfileIds: Set<String> = emptySet(),
    /** Previously published profiles absent from the intent set. */
    val removedProfileIds: Set<String> = emptySet(),
    /** Preference-record fields changed; neither revision advanced. */
    val preferenceChangedIds: Set<String> = emptySet(),
    /** Display-name-only changes; neither revision advanced. */
    val displayOnlyChangedIds: Set<String> = emptySet(),
    /** Operation-affecting non-security (and access-boundary) changes. */
    val profileRevisionAdvancedIds: Set<String> = emptySet(),
    /** Security-boundary changes (endpoint, API policy, auth, principal, secret). */
    val accessRevisionAdvancedIds: Set<String> = emptySet(),
    /** Suggested default namespace changed for these profile ids. */
    val suggestedNamespaceChangedIds: Set<String> = emptySet(),
    /** Active profile selection changed. */
    val activeProfileIdChanged: Boolean = false,
    /**
     * Profiles whose pending publication was withheld because credential
     * staging failed. The previous published pair remains active for each.
     */
    val failedStageProfileIds: Set<String> = emptySet(),
    /** Immutable snapshots of the profiles that are now published, in intent order. */
    val publishedProfiles: List<EnvironmentProfile> = emptyList(),
    /** Immutable preference records corresponding to the intent set. */
    val publishedPreferences: List<EnvironmentPreferences> = emptyList(),
    /** Active profile id after the write. */
    val activeProfileId: String = "",
    /**
     * Suggested namespaces after the write, keyed by profile id.
     * Carried so the host can dual-write the legacy server list without
     * re-deriving from intents.
     */
    val suggestedNamespaces: Map<String, String> = emptyMap()
) {
    /**
     * True when open projects must recapture sessions or treat connection
     * identity as changed. Preference/display-only edits and pure reorders
     * return false.
     */
    fun isOperationalChange(): Boolean =
        addedProfileIds.isNotEmpty() ||
            removedProfileIds.isNotEmpty() ||
            profileRevisionAdvancedIds.isNotEmpty() ||
            accessRevisionAdvancedIds.isNotEmpty() ||
            activeProfileIdChanged ||
            suggestedNamespaceChangedIds.isNotEmpty()

    /**
     * True when only preference or display-name fields moved — the notification
     * path that must not bump session epochs (ADR-0042).
     */
    fun isPreferencesOnlyChange(): Boolean =
        !isOperationalChange() &&
            (preferenceChangedIds.isNotEmpty() || displayOnlyChangedIds.isNotEmpty())

    /** True when nothing observable changed (including pure reorder). */
    fun isNoOp(): Boolean =
        !isOperationalChange() &&
            preferenceChangedIds.isEmpty() &&
            displayOnlyChangedIds.isEmpty() &&
            failedStageProfileIds.isEmpty()
}
