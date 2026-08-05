package com.nanyin.nacos.search.models

/**
 * Per-environment preference-only values associated with an [EnvironmentProfile]
 * by stable profile id.
 *
 * Contains no connection target, credential, or derived revision. Changing these
 * values must publish the preference-only settings notification and must not
 * advance the profile revision, access revision, or project session epoch
 * (ADR-0042, issue #101). This record is the runtime source of truth so the
 * legacy server list can later become a deserialization-only input (ADR-0049).
 */
data class EnvironmentPreferences(
    /** Stable environment profile id this preference record belongs to. */
    var profileId: String = "",
    /**
     * When true, `@NacosValue` / Find Usages may resolve keys from namespaces
     * other than the project's selected namespace.
     */
    var allowCrossNamespaceNavigation: Boolean = false,
    /**
     * When true, the navigation detail prefetch loads configuration bodies for
     * the project's declared configuration sources so gutter markers resolve
     * without browsing (ADR-0041 / ADR-0042).
     */
    var navigationDetailPrefetchEnabled: Boolean = true
) {
    fun copyPreferences(): EnvironmentPreferences = copy()

    companion object {
        fun defaultsFor(profileId: String): EnvironmentPreferences =
            EnvironmentPreferences(profileId = profileId)

        fun fromLegacyServer(server: NacosServerConfig): EnvironmentPreferences =
            EnvironmentPreferences(
                profileId = server.id.ifBlank { "default" },
                allowCrossNamespaceNavigation = server.allowCrossNamespaceNavigation,
                navigationDetailPrefetchEnabled = server.navigationDetailPrefetchEnabled
            )
    }
}
