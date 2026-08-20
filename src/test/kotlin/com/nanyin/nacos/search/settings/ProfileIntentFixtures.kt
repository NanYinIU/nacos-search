package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.EnvironmentPreferences
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.ProfileIntent

/** Concise intent builder for tests whose subject is outside Settings. */
internal fun profileIntentFixture(
    id: String,
    displayName: String = "",
    serverUrl: String = "http://localhost:8848",
    username: String = "",
    password: String = "",
    namespace: String = "public",
    apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
    authMode: AuthMode = AuthMode.ANONYMOUS,
    defaultGroup: String = "DEFAULT_GROUP",
    allowCrossNamespaceNavigation: Boolean = false,
    navigationDetailPrefetchEnabled: Boolean = true,
    writeIntent: Boolean = false
): ProfileIntent = ProfileIntent(
    profileId = id,
    displayName = displayName,
    endpoint = serverUrl,
    apiPolicy = apiPolicy,
    authMode = authMode,
    principal = username,
    secret = password,
    writeIntent = writeIntent,
    suggestedNamespace = namespace,
    preferences = EnvironmentPreferences(
        profileId = id,
        allowCrossNamespaceNavigation = allowCrossNamespaceNavigation,
        navigationDetailPrefetchEnabled = navigationDetailPrefetchEnabled,
        suggestedNamespace = namespace,
        defaultGroup = defaultGroup
    )
)
