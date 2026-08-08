package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/**
 * User-edited environment attributes **without** profile revision, access
 * revision, or credential slot identity (ADR-0049 / issue #103).
 *
 * The environment-profile store is the only writer of [EnvironmentProfile]
 * snapshots: it compares an intent against the published profile, stages any
 * required credential slot, and derives revisions and slot coordinates itself.
 * Callers must not invent or carry those derived values on this type.
 *
 * Avoid naming this an environment profile, draft, or settings form — it is
 * only the attributes a user chooses.
 *
 * **Scope note (issue #153 / #156):** `defaultGroup` lives on [EnvironmentPreferences]
 * via [preferences]. Retired dual-write-only fields (connection timeout,
 * auto-refresh-on-open) are no longer part of the intent or draft surface.
 */
data class ProfileIntent(
    /** Stable environment profile id. Empty values are rejected by the store. */
    val profileId: String,
    /** Display label. Changing it advances neither revision. */
    val displayName: String = "",
    /**
     * User-entered endpoint origin. The store normalizes it to a
     * [CanonicalNacosEndpoint] value on publish; an unparseable value still
     * participates in access-boundary comparison so a bad edit cannot be lost.
     */
    val endpoint: String = "http://localhost:8848",
    val apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
    val authMode: AuthMode = AuthMode.NACOS_PASSWORD,
    val principal: String = "",
    /**
     * Desired secret for this profile. Never stored on the profile itself;
     * the store stages it under the next credential slot before publication
     * (ADR-0035). Empty is valid for anonymous access.
     */
    val secret: String = "",
    /** Explicit publish opt-in; advances only the profile revision when it changes. */
    val writeIntent: Boolean = false,
    /**
     * Suggested default namespace for migration / settings defaults.
     * Not part of the access identity; advances neither profile nor access
     * revision, but is reported on the write outcome so sessions can recapture.
     */
    val suggestedNamespace: String = "public",
    /**
     * Preference-only values associated with this profile id (ADR-0042 / #101).
     * Changes advance neither profile nor access revision.
     */
    val preferences: EnvironmentPreferences = EnvironmentPreferences()
) {
    companion object {
        /**
         * Bridge from the settings dual-write draft row into a profile intent.
         * The dialog holds dual-write rows that map 1:1 to intents (issue #106);
         * revisions and credential-slot identity are never present on this type.
         */
        fun fromServerConfig(server: NacosServerConfig): ProfileIntent {
            val id = server.id.ifBlank { "default" }
            val suggested = server.namespace.ifBlank { "public" }
            return ProfileIntent(
                profileId = id,
                displayName = server.displayName,
                endpoint = server.serverUrl,
                apiPolicy = server.apiPolicy,
                authMode = server.authMode,
                principal = server.username.trim(),
                secret = server.password,
                writeIntent = server.writeIntent,
                suggestedNamespace = suggested,
                preferences = EnvironmentPreferences(
                    profileId = id,
                    allowCrossNamespaceNavigation = server.allowCrossNamespaceNavigation,
                    navigationDetailPrefetchEnabled = server.navigationDetailPrefetchEnabled,
                    suggestedNamespace = suggested,
                    defaultGroup = server.defaultGroup.trim().ifBlank { "DEFAULT_GROUP" }
                )
            )
        }
    }
}
