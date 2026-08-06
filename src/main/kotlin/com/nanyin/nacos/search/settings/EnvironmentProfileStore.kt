package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.ProfileIntent

/**
 * Single publisher of environment profiles from [ProfileIntent] inputs
 * (ADR-0049 / issue #103).
 *
 * Callers never supply profile revision, access revision, or credential slot
 * identity — those are derived here. Secret changes stage the new immutable
 * credential slot successfully **before** the corresponding profile and
 * revisions become visible (ADR-0035). Revision rules follow ADR-0024:
 *
 * - endpoint, API policy, auth strategy, principal, secret → both revisions
 * - write-intent (operation-affecting, non-security) → profile revision only
 * - display name, suggested namespace, preference records → neither
 * - reordering intents alone → neither and not operational
 *
 * Preference / display / namespace classification is recorded only when that
 * profile's stage succeeds (or, on Keep/Unchanged stage failure, the previous
 * published prefs/namespace are retained and no change bits are set).
 *
 * The store is pure with respect to settings persistence: it returns published
 * snapshots and a [ProfileStoreWriteOutcome]; the host ([NacosSettings]) is
 * responsible for replacing its lists and dual-writing the legacy server surface
 * **only** for successfully published ids.
 *
 * Removals are **classified** here (ids present previously and absent from
 * intents) but not executed: the host runs [ProfileDeletionLifecycle] at the
 * write boundary so the tombstone lands before the published set omits the
 * environment (ADR-0025 / issue #105). [isEntombed] refuses Add/Keep of a
 * tombstoned id so credential staging and profile publication recheck the
 * lifecycle guard (ADR-0035).
 */
class EnvironmentProfileStore(
    private val credentialSlots: CredentialSlotStore,
    private val isEntombed: (String) -> Boolean = { false }
) {

    /**
     * Compares [intents] to [previousProfiles] by stable profile id, stages any
     * required credential slots, and returns the next published set plus one
     * authoritative [ProfileStoreWriteOutcome].
     *
     * @param intents ordered list of user-chosen attributes (order may change
     *   without advancing revisions)
     * @param activeProfileId desired active selection after the write
     * @param previousProfiles currently published profiles
     * @param previousPreferences currently published preference records
     * @param previousActiveId previously active profile id
     * @param previousSuggestedNamespaces last published suggested namespaces by
     *   profile id (from the dual-write server list until intents own it alone)
     */
    fun applyIntents(
        intents: List<ProfileIntent>,
        activeProfileId: String,
        previousProfiles: List<EnvironmentProfile>,
        previousPreferences: List<EnvironmentPreferences> = emptyList(),
        previousActiveId: String = "",
        previousSuggestedNamespaces: Map<String, String> = emptyMap()
    ): ProfileStoreWriteOutcome {
        val previousById = previousProfiles
            .filter { it.id.isNotBlank() }
            .associateBy { it.id }
        val previousPrefsById = previousPreferences
            .filter { it.profileId.isNotBlank() }
            .associate { it.profileId to it.copyPreferences() }

        val intentIds = linkedSetOf<String>()
        val normalizedIntents = intents.mapNotNull { intent ->
            val id = intent.profileId.trim()
            if (id.isBlank() || !intentIds.add(id)) return@mapNotNull null
            intent.copy(
                profileId = id,
                preferences = intent.preferences.copyPreferences().also { it.profileId = id }
            )
        }

        val currentIds = intentIds.toSet()
        val previousIds = previousById.keys
        val removedIds = previousIds - currentIds
        val addedIds = mutableSetOf<String>()
        val preferenceChanged = mutableSetOf<String>()
        val displayOnlyChanged = mutableSetOf<String>()
        val profileRevisionAdvanced = mutableSetOf<String>()
        val accessRevisionAdvanced = mutableSetOf<String>()
        val namespaceChanged = mutableSetOf<String>()
        val failedStage = mutableSetOf<String>()
        val suggestedNamespaces = linkedMapOf<String, String>()

        val published = mutableListOf<EnvironmentProfile>()
        val publishedPrefs = mutableListOf<EnvironmentPreferences>()

        for (intent in normalizedIntents) {
            val id = intent.profileId
            val previous = previousById[id]
            val nextPrefs = intent.preferences.copyPreferences().also { it.profileId = id }
            val prevPrefs = previousPrefsById[id] ?: EnvironmentPreferences.defaultsFor(id)
            val suggestedNs = intent.suggestedNamespace.trim().ifBlank { "public" }

            // Lifecycle guard (ADR-0035 / #105): a tombstoned id cannot stage a
            // new credential or re-enter the published set under the same id.
            if (isEntombed(id)) {
                failedStage += id
                if (previous != null) {
                    retainPreviousPublication(
                        previous = previous,
                        prevPrefs = prevPrefs,
                        previousSuggestedNamespaces = previousSuggestedNamespaces,
                        published = published,
                        publishedPrefs = publishedPrefs,
                        suggestedNamespaces = suggestedNamespaces
                    )
                }
                continue
            }

            when (val decision = decidePublication(previous, intent)) {
                is PublishDecision.Add -> {
                    when (stageNewProfile(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> {
                            published += snapshot(decision.profile)
                            addedIds += id
                            recordSuccessfulSideEffects(
                                id = id,
                                previous = null,
                                previousDisplayName = null,
                                nextDisplayName = intent.displayName,
                                prevPrefs = prevPrefs,
                                nextPrefs = nextPrefs,
                                suggestedNs = suggestedNs,
                                previousSuggestedNamespaces = previousSuggestedNamespaces,
                                accessAdvanced = false,
                                profileAdvanced = false,
                                preferenceChanged = preferenceChanged,
                                displayOnlyChanged = displayOnlyChanged,
                                namespaceChanged = namespaceChanged,
                                publishedPrefs = publishedPrefs,
                                suggestedNamespaces = suggestedNamespaces
                            )
                        }
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            // Fail-closed: do not publish profile, prefs, or namespace.
                        }
                    }
                }
                is PublishDecision.Keep -> {
                    when (credentialSlots.stage(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> {
                            val withDisplay = decision.profile.copy(displayName = intent.displayName)
                            published += snapshot(withDisplay)
                            if (decision.accessAdvanced) accessRevisionAdvanced += id
                            if (decision.profileAdvanced) profileRevisionAdvanced += id
                            recordSuccessfulSideEffects(
                                id = id,
                                previous = previous,
                                previousDisplayName = previous?.displayName,
                                nextDisplayName = intent.displayName,
                                prevPrefs = prevPrefs,
                                nextPrefs = nextPrefs,
                                suggestedNs = suggestedNs,
                                previousSuggestedNamespaces = previousSuggestedNamespaces,
                                accessAdvanced = decision.accessAdvanced,
                                profileAdvanced = decision.profileAdvanced,
                                preferenceChanged = preferenceChanged,
                                displayOnlyChanged = displayOnlyChanged,
                                namespaceChanged = namespaceChanged,
                                publishedPrefs = publishedPrefs,
                                suggestedNamespaces = suggestedNamespaces
                            )
                        }
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            // Keep the previous published pair and its prefs/namespace.
                            retainPreviousPublication(
                                previous = previous,
                                prevPrefs = prevPrefs,
                                previousSuggestedNamespaces = previousSuggestedNamespaces,
                                published = published,
                                publishedPrefs = publishedPrefs,
                                suggestedNamespaces = suggestedNamespaces
                            )
                        }
                    }
                }
                is PublishDecision.Unchanged -> {
                    when (credentialSlots.stage(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> {
                            val withDisplay = decision.profile.copy(displayName = intent.displayName)
                            published += snapshot(withDisplay)
                            recordSuccessfulSideEffects(
                                id = id,
                                previous = previous,
                                previousDisplayName = previous?.displayName,
                                nextDisplayName = intent.displayName,
                                prevPrefs = prevPrefs,
                                nextPrefs = nextPrefs,
                                suggestedNs = suggestedNs,
                                previousSuggestedNamespaces = previousSuggestedNamespaces,
                                accessAdvanced = false,
                                profileAdvanced = false,
                                preferenceChanged = preferenceChanged,
                                displayOnlyChanged = displayOnlyChanged,
                                namespaceChanged = namespaceChanged,
                                publishedPrefs = publishedPrefs,
                                suggestedNamespaces = suggestedNamespaces
                            )
                        }
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            retainPreviousPublication(
                                previous = previous,
                                prevPrefs = prevPrefs,
                                previousSuggestedNamespaces = previousSuggestedNamespaces,
                                published = published,
                                publishedPrefs = publishedPrefs,
                                suggestedNamespaces = suggestedNamespaces
                            )
                        }
                    }
                }
            }
        }

        val resolvedActive = when {
            activeProfileId.isNotBlank() && published.any { it.id == activeProfileId } -> activeProfileId
            published.any { it.id == previousActiveId } -> previousActiveId
            else -> published.firstOrNull()?.id.orEmpty()
        }

        return ProfileStoreWriteOutcome(
            addedProfileIds = addedIds.toSet(),
            removedProfileIds = removedIds,
            preferenceChangedIds = preferenceChanged.toSet(),
            displayOnlyChangedIds = displayOnlyChanged.toSet(),
            profileRevisionAdvancedIds = profileRevisionAdvanced.toSet(),
            accessRevisionAdvancedIds = accessRevisionAdvanced.toSet(),
            suggestedNamespaceChangedIds = namespaceChanged.toSet(),
            // An empty previous active id means "no prior selection" (first
            // publish), not "changed away from blank". Only a real switch
            // between two known selections is operational for sessions.
            activeProfileIdChanged = previousActiveId.isNotBlank() &&
                resolvedActive.isNotBlank() &&
                resolvedActive != previousActiveId,
            failedStageProfileIds = failedStage.toSet(),
            publishedProfiles = published.toList(),
            publishedPreferences = publishedPrefs.toList(),
            activeProfileId = resolvedActive,
            suggestedNamespaces = suggestedNamespaces.toMap()
        )
    }

    /**
     * Immutable defensive copy of a published profile. Callers must not mutate
     * the returned instance and write it back — only [applyIntents] publishes.
     */
    fun snapshotOf(profile: EnvironmentProfile): EnvironmentProfile = snapshot(profile)

    /**
     * Stage the first slot for a profile that is not currently published.
     * Orphan slots left after an incomplete deletion (or a throwaway settings
     * instance that staged into the shared store) must not block re-creation:
     * when the first stage is refused as **immutable**, clear every slot for
     * this id once and retry. Other failures (platform rejection, index write)
     * stay fail-closed without reclaiming history.
     */
    private fun stageNewProfile(
        profileId: String,
        accessRevision: Long,
        secret: String
    ): CredentialStageResult {
        when (val first = credentialSlots.stage(profileId, accessRevision, secret)) {
            is CredentialStageResult.Success -> return first
            is CredentialStageResult.Failure -> {
                if (!isImmutableSlotFailure(first)) return first
                // Only reclaim orphans for a brand-new publication. Never call
                // removeAllForProfile on an id that still has a published pair.
                credentialSlots.removeAllForProfile(profileId)
                return credentialSlots.stage(profileId, accessRevision, secret)
            }
        }
    }

    private fun isImmutableSlotFailure(failure: CredentialStageResult.Failure): Boolean =
        failure.reason.contains("immutable", ignoreCase = true)

    private fun recordSuccessfulSideEffects(
        id: String,
        previous: EnvironmentProfile?,
        previousDisplayName: String?,
        nextDisplayName: String,
        prevPrefs: EnvironmentPreferences,
        nextPrefs: EnvironmentPreferences,
        suggestedNs: String,
        previousSuggestedNamespaces: Map<String, String>,
        accessAdvanced: Boolean,
        profileAdvanced: Boolean,
        preferenceChanged: MutableSet<String>,
        displayOnlyChanged: MutableSet<String>,
        namespaceChanged: MutableSet<String>,
        publishedPrefs: MutableList<EnvironmentPreferences>,
        suggestedNamespaces: MutableMap<String, String>
    ) {
        if (!preferencesEqual(prevPrefs, nextPrefs)) {
            preferenceChanged += id
        }
        publishedPrefs += nextPrefs
        suggestedNamespaces[id] = suggestedNs
        if (previous != null && previousSuggestedNamespaces.containsKey(id)) {
            val previousNs = previousSuggestedNamespaces.getValue(id).trim().ifBlank { "public" }
            if (previousNs != suggestedNs) {
                namespaceChanged += id
            }
        }
        if (previous != null &&
            previousDisplayName != nextDisplayName &&
            !accessAdvanced &&
            !profileAdvanced
        ) {
            displayOnlyChanged += id
        }
    }

    private fun retainPreviousPublication(
        previous: EnvironmentProfile?,
        prevPrefs: EnvironmentPreferences,
        previousSuggestedNamespaces: Map<String, String>,
        published: MutableList<EnvironmentProfile>,
        publishedPrefs: MutableList<EnvironmentPreferences>,
        suggestedNamespaces: MutableMap<String, String>
    ) {
        if (previous == null) return
        published += snapshot(previous)
        publishedPrefs += prevPrefs.copyPreferences()
        val previousNs = previousSuggestedNamespaces[previous.id]?.trim()?.ifBlank { "public" }
            ?: "public"
        suggestedNamespaces[previous.id] = previousNs
    }

    private fun decidePublication(
        previous: EnvironmentProfile?,
        intent: ProfileIntent
    ): PublishDecision {
        val endpoint = normalizeEndpoint(intent.endpoint)
        val principal = intent.principal.trim()
        if (previous == null) {
            val profile = EnvironmentProfile(
                id = intent.profileId,
                displayName = intent.displayName,
                canonicalEndpoint = endpoint,
                apiPolicy = intent.apiPolicy,
                authMode = intent.authMode,
                principal = principal,
                writeIntent = intent.writeIntent,
                profileRevision = 1,
                accessRevision = 1,
                credentialSlotId = CredentialSlotStore.slotKey(intent.profileId, 1L),
                credentialSlotVersion = 1L
            )
            return PublishDecision.Add(profile)
        }

        val publishedSecret = credentialSlots
            .read(previous.id, previous.credentialSlotVersion)
            .orEmpty()
        val secretChanged = publishedSecret != intent.secret
        val accessBoundaryChanged =
            previous.canonicalEndpoint != endpoint ||
                previous.apiPolicy != intent.apiPolicy ||
                previous.authMode != intent.authMode ||
                previous.principal != principal ||
                secretChanged
        val profileOnlyChanged = previous.writeIntent != intent.writeIntent
        // displayName is intentionally ignored for revision advancement.

        if (!accessBoundaryChanged && !profileOnlyChanged) {
            return PublishDecision.Unchanged(previous)
        }

        val nextAccessRevision =
            if (accessBoundaryChanged) previous.accessRevision + 1 else previous.accessRevision
        val nextProfileRevision =
            if (accessBoundaryChanged || profileOnlyChanged) previous.profileRevision + 1
            else previous.profileRevision
        // Credential slot version advances with the secret (and therefore with
        // the access identity when the secret is the boundary that moved).
        // Other access-boundary edits keep the existing slot so captures can
        // still read the unchanged secret under the new access revision.
        val nextSlotVersion =
            if (secretChanged) previous.credentialSlotVersion + 1 else previous.credentialSlotVersion
        val nextSlotId = CredentialSlotStore.slotKey(previous.id, nextSlotVersion)

        val updated = previous.copy(
            displayName = intent.displayName,
            canonicalEndpoint = endpoint,
            apiPolicy = intent.apiPolicy,
            authMode = intent.authMode,
            principal = principal,
            writeIntent = intent.writeIntent,
            profileRevision = nextProfileRevision,
            accessRevision = nextAccessRevision,
            credentialSlotId = nextSlotId,
            credentialSlotVersion = nextSlotVersion,
            cacheTombstones = previous.cacheTombstones.toMutableList()
        )
        return PublishDecision.Keep(
            profile = updated,
            accessAdvanced = accessBoundaryChanged,
            profileAdvanced = accessBoundaryChanged || profileOnlyChanged
        )
    }

    private fun normalizeEndpoint(raw: String): String =
        CanonicalNacosEndpoint.parse(raw).getOrNull()?.value
            ?: raw.trim()

    private fun preferencesEqual(a: EnvironmentPreferences, b: EnvironmentPreferences): Boolean =
        a.allowCrossNamespaceNavigation == b.allowCrossNamespaceNavigation &&
            a.navigationDetailPrefetchEnabled == b.navigationDetailPrefetchEnabled

    private fun snapshot(profile: EnvironmentProfile): EnvironmentProfile =
        profile.copy(cacheTombstones = profile.cacheTombstones.toMutableList())

    private sealed interface PublishDecision {
        data class Add(val profile: EnvironmentProfile) : PublishDecision
        data class Keep(
            val profile: EnvironmentProfile,
            val accessAdvanced: Boolean,
            val profileAdvanced: Boolean
        ) : PublishDecision
        data class Unchanged(val profile: EnvironmentProfile) : PublishDecision
    }
}
