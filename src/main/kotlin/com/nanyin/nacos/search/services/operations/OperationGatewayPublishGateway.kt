package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * Production [PublishGateway] that routes every preflight / write / read-back
 * through the generation-neutral [OperationGateway] using the target derived
 * from the edit session's binding — never the live UI selection.
 */
class OperationGatewayPublishGateway(
    private val gateway: OperationGateway
) : PublishGateway {

    override suspend fun preflight(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> {
        return gateway.readDetail(
            target = target,
            coordinate = coordinate,
            forceRefresh = true,
            useCache = false
        ).map { it.value }
    }

    override suspend fun write(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> {
        return gateway.publish(target, command).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                when (error) {
                    is RemoteOperationError.WriteConflict -> Result.success(PublishOutcome.CasConflict)
                    else -> Result.failure(error)
                }
            }
        )
    }

    /**
     * The reconciliation read-back obeys the ordering rule like every other
     * remote read rather than being suppressed until the publish state becomes
     * verified (ADR-0020): it always goes to the server, and the detail it
     * observes updates the cache under that observation's sequence, so
     * verifying a publish cannot be undone by an older read.
     */
    override suspend fun readBack(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> {
        return gateway.readDetail(
            target = target,
            coordinate = coordinate,
            forceRefresh = true,
            useCache = true
        ).map { it.value }
    }
}
