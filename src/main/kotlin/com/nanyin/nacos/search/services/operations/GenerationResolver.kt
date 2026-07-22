package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration

/**
 * Resolves AUTO generation selection using an isolated probe context.
 *
 * V3 is tried first; only a typed [RemoteOperationError.GenerationUnsupported]
 * permits a V1 candidate probe. All other failures propagate without
 * fallback. Probe credentials and temporary tokens are never shared with
 * the formal authentication registry.
 */
class GenerationResolver(
    private val v3Adapter: ProtocolAdapter,
    private val v1Adapter: ProtocolAdapter
) {
    suspend fun resolve(target: OperationTarget): Result<NacosApiGeneration> {
        // Build candidate targets with the generation the adapter validates.
        val v3Target = target.withGeneration(NacosApiGeneration.V3)
        val v3Result = v3Adapter.probe(v3Target)
        if (v3Result.isSuccess) return Result.success(NacosApiGeneration.V3)

        val v3Error = v3Result.exceptionOrNull()
        // Only typed GenerationUnsupported authorises a V1 candidate probe.
        if (v3Error !is RemoteOperationError.GenerationUnsupported) {
            return Result.failure(v3Error!!)
        }

        val v1Target = target.withGeneration(NacosApiGeneration.V1)
        val v1Result = v1Adapter.probe(v1Target)
        if (v1Result.isSuccess) return Result.success(NacosApiGeneration.V1)

        // V1 probe failure propagates as-is; no further fallback.
        return Result.failure(v1Result.exceptionOrNull()!!)
    }
}


/** Rebuilds a target with a different resolved generation for probing. */
private fun OperationTarget.withGeneration(generation: NacosApiGeneration): OperationTarget {
    if (context.resolvedGeneration == generation) return this
    val newIdentity = com.nanyin.nacos.search.models.AccessIdentity.ofProfile(
        profileId = context.identity.profileId,
        accessRevision = context.identity.accessRevision,
        canonicalEndpoint = context.identity.canonicalEndpoint,
        resolvedGeneration = generation,
        authMode = context.identity.authMode,
        principal = context.identity.principal
    )
    return copy(context = context.copy(
        identity = newIdentity,
        resolvedGeneration = generation
    ))
}
