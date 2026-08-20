package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.ProfileIntent

/**
 * Pure, in-memory Settings draft over user-editable profile intents.
 *
 * Every ingress and snapshot is copied so mutable persisted preference beans
 * cannot escape into the draft. Secrets live only in these in-memory rows.
 */
class SettingsIntentDraft private constructor(
    intents: List<ProfileIntent>,
    val activeProfileId: String,
    val openBaselineActiveId: String
) {
    private val rows = intents.map(::copyIntent)

    /** Ordered defensive snapshot of the draft rows. */
    fun snapshot(): List<ProfileIntent> = rows.map(::copyIntent)

    fun add(intent: ProfileIntent): SettingsIntentDraft {
        require(intent.profileId.isNotBlank()) { "Profile id must not be blank" }
        require(rows.none { it.profileId == intent.profileId }) {
            "Profile id already exists: ${intent.profileId}"
        }
        return withRows(rows + intent, activeProfileId.ifBlank { intent.profileId })
    }

    fun duplicate(profileId: String, newProfileId: String): SettingsIntentDraft {
        val source = rows.firstOrNull { it.profileId == profileId }
            ?: throw IllegalArgumentException("Unknown profile id: $profileId")
        return add(
            source.copy(
                profileId = newProfileId,
                displayName = "${source.displayName} (copy)"
            )
        )
    }

    fun remove(profileId: String): SettingsIntentDraft {
        require(rows.any { it.profileId == profileId }) { "Unknown profile id: $profileId" }
        val remaining = rows.filterNot { it.profileId == profileId }
        val active = activeProfileId.takeIf { id -> remaining.any { it.profileId == id } }
            ?: remaining.firstOrNull()?.profileId.orEmpty()
        return withRows(remaining, active)
    }

    fun reorder(profileIds: List<String>): SettingsIntentDraft {
        val currentIds = rows.map { it.profileId }
        require(profileIds.size == currentIds.size && profileIds.toSet() == currentIds.toSet()) {
            "Reorder must contain every profile id exactly once"
        }
        val byId = rows.associateBy { it.profileId }
        return withRows(profileIds.map { byId.getValue(it) })
    }

    fun replace(profileId: String, intent: ProfileIntent): SettingsIntentDraft {
        require(intent.profileId == profileId) { "A draft row cannot change its profile id" }
        val index = rows.indexOfFirst { it.profileId == profileId }
        require(index >= 0) { "Unknown profile id: $profileId" }
        return withRows(rows.toMutableList().also { it[index] = intent })
    }

    fun update(profileId: String, transform: (ProfileIntent) -> ProfileIntent): SettingsIntentDraft {
        val current = rows.firstOrNull { it.profileId == profileId }
            ?: throw IllegalArgumentException("Unknown profile id: $profileId")
        return replace(profileId, transform(copyIntent(current)))
    }

    fun changeSecret(profileId: String, secret: String): SettingsIntentDraft =
        update(profileId) { it.copy(secret = secret) }

    fun reset(profileId: String, defaultIntent: ProfileIntent): SettingsIntentDraft =
        replace(profileId, defaultIntent)

    fun selectActive(profileId: String): SettingsIntentDraft {
        require(rows.any { it.profileId == profileId }) { "Unknown profile id: $profileId" }
        return withRows(rows, profileId)
    }

    private fun withRows(
        intents: List<ProfileIntent>,
        activeId: String = activeProfileId
    ): SettingsIntentDraft =
        SettingsIntentDraft(intents, activeId, openBaselineActiveId)

    companion object {
        fun of(
            intents: List<ProfileIntent>,
            activeProfileId: String,
            openBaselineActiveId: String = activeProfileId
        ): SettingsIntentDraft =
            SettingsIntentDraft(intents, activeProfileId, openBaselineActiveId)

        private fun copyIntent(intent: ProfileIntent): ProfileIntent {
            val namespace = intent.suggestedNamespace.trim().ifBlank { "public" }
            return intent.copy(
                suggestedNamespace = namespace,
                preferences = intent.preferences.copyPreferences().also {
                    it.profileId = intent.profileId
                    it.suggestedNamespace = namespace
                }
            )
        }
    }
}
