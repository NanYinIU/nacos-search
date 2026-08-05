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
 * The store is pure with respect to settings persistence: it returns published
 * snapshots and a [ProfileStoreWriteOutcome]; the host ([NacosSettings]) is
 * responsible for replacing its lists and dual-writing the legacy server surface.
 */
class EnvironmentProfileStore(
    private val credentialSlots: CredentialSlotStore
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
            if (!preferencesEqual(prevPrefs, nextPrefs)) {
                preferenceChanged += id
            }
            publishedPrefs += nextPrefs

            val suggestedNs = intent.suggestedNamespace.trim().ifBlank { "public" }
            suggestedNamespaces[id] = suggestedNs
            // Namespace is only comparable for previously published profiles that
            // already had a suggested namespace recorded by the host.
            if (previous != null && previousSuggestedNamespaces.containsKey(id)) {
                val previousNs = previousSuggestedNamespaces.getValue(id).trim().ifBlank { "public" }
                if (previousNs != suggestedNs) {
                    namespaceChanged += id
                }
            }

            val decision = decidePublication(previous, intent)
            when (decision) {
                is PublishDecision.Add -> {
                    when (stageNewProfile(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> {
                            published += snapshot(decision.profile)
                            addedIds += id
                            // First publication establishes both revisions at 1;
                            // callers that care about membership see [addedIds].
                        }
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            // Fail-closed: do not publish a profile whose required
                            // slot never became durable.
                        }
                    }
                }
                is PublishDecision.Keep -> {
                    // Restage under the current slot so retries stay aligned with
                    // the published pair; same secret is idempotent success.
                    when (credentialSlots.stage(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> {
                            val withDisplay = decision.profile.copy(displayName = intent.displayName)
                            if (previous != null && previous.displayName != intent.displayName &&
                                !decision.accessAdvanced && !decision.profileAdvanced
                            ) {
                                displayOnlyChanged += id
                            }
                            published += snapshot(withDisplay)
                            if (decision.accessAdvanced) accessRevisionAdvanced += id
                            if (decision.profileAdvanced) profileRevisionAdvanced += id
                        }
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            // Keep the previous published pair visible.
                            previous?.let { published += snapshot(it) }
                        }
                    }
                }
                is PublishDecision.Unchanged -> {
                    val withDisplay = decision.profile.copy(displayName = intent.displayName)
                    if (previous != null && previous.displayName != intent.displayName) {
                        displayOnlyChanged += id
                    }
                    // Secret may still need an idempotent restage (empty anonymous
                    // or same secret under the current slot).
                    when (credentialSlots.stage(id, decision.profile.credentialSlotVersion, intent.secret)) {
                        is CredentialStageResult.Success -> published += snapshot(withDisplay)
                        is CredentialStageResult.Failure -> {
                            failedStage += id
                            previous?.let { published += snapshot(it) }
                        }
                    }
                }
            }
        }

        // Removed profiles: the host entombs and deletes slots. The store only
        // classifies the removal so the outcome is complete.
        removedIds.forEach { /* classified below */ }

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
     * when the first stage is refused as immutable, clear every slot for this
     * id once and retry. A second failure stays fail-closed.
     */
    private fun stageNewProfile(
        profileId: String,
        accessRevision: Long,
        secret: String
    ): CredentialStageResult {
        when (val first = credentialSlots.stage(profileId, accessRevision, secret)) {
            is CredentialStageResult.Success -> return first
            is CredentialStageResult.Failure -> {
                // Only reclaim orphans for a brand-new publication. Never call
                // removeAllForProfile on an id that still has a published pair.
                credentialSlots.removeAllForProfile(profileId)
                return credentialSlots.stage(profileId, accessRevision, secret)
            }
        }
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
