package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode

/** Shared factory for tests that need a complete, non-legacy [AccessIdentity]. */
internal fun testIdentity(
    endpoint: String = "http://nacos:8848",
    principal: String = "admin",
    authMode: AuthMode = AuthMode.TOKEN,
    profileId: String = endpoint.trim().trimEnd('/').ifBlank { "<default>" },
    accessRevision: Long = 1L,
    generation: NacosApiGeneration = NacosApiGeneration.V1
): AccessIdentity = AccessIdentity.ofProfile(
    profileId = profileId,
    accessRevision = accessRevision,
    canonicalEndpoint = endpoint,
    resolvedGeneration = generation,
    authMode = authMode,
    principal = principal
)
