package com.nanyin.nacos.search.settings

/**
 * Pure classification of a draft intent set against published profiles
 * (ADR-0049 / issue #106).
 *
 * Produced by [EnvironmentProfileStore.classifyIntents] without staging
 * credentials, deleting profiles, or writing settings. Row dirty indicators,
 * Apply enablement, revision publication, preference notifications, and
 * connection-change reporting all read this single answer — never an
 * independent field-by-field comparison.
 *
 * Dual-write-only fields that are not yet on [com.nanyin.nacos.search.models.ProfileIntent]
 * (timeout, default group, auto-refresh) are classified outside the store.
 */
data class ProfileIntentClassification(
    val addedProfileIds: Set<String> = emptySet(),
    val removedProfileIds: Set<String> = emptySet(),
    val preferenceChangedIds: Set<String> = emptySet(),
    val displayOnlyChangedIds: Set<String> = emptySet(),
    val profileRevisionWouldAdvanceIds: Set<String> = emptySet(),
    val accessRevisionWouldAdvanceIds: Set<String> = emptySet(),
    val suggestedNamespaceChangedIds: Set<String> = emptySet(),
    val activeProfileIdChanged: Boolean = false
) {
    /**
     * Profile ids whose draft row differs from the published state for any
     * store-owned reason (add, preference, display, revision, namespace).
     * Removals have no row.
     */
    fun dirtyProfileIds(): Set<String> =
        addedProfileIds +
            preferenceChangedIds +
            displayOnlyChangedIds +
            profileRevisionWouldAdvanceIds +
            accessRevisionWouldAdvanceIds +
            suggestedNamespaceChangedIds

    /**
     * True when open projects must treat connection identity as changed
     * (same rule as [ProfileStoreWriteOutcome.isOperationalChange]).
     */
    fun isOperationalChange(): Boolean =
        addedProfileIds.isNotEmpty() ||
            removedProfileIds.isNotEmpty() ||
            profileRevisionWouldAdvanceIds.isNotEmpty() ||
            accessRevisionWouldAdvanceIds.isNotEmpty() ||
            activeProfileIdChanged ||
            suggestedNamespaceChangedIds.isNotEmpty()

    /**
     * True when only preference or display-name fields moved.
     */
    fun isPreferencesOnlyChange(): Boolean =
        !isOperationalChange() &&
            (preferenceChangedIds.isNotEmpty() || displayOnlyChangedIds.isNotEmpty())

    /**
     * True when nothing store-owned changed (including pure reorder).
     */
    fun isNoOp(): Boolean =
        !isOperationalChange() &&
            preferenceChangedIds.isEmpty() &&
            displayOnlyChangedIds.isEmpty()

    /**
     * True when Apply would publish a store-owned change.
     */
    fun isModified(): Boolean = !isNoOp()
}
