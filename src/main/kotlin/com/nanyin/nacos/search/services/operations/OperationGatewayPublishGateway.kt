package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * Production [PublishGateway] that routes every preflight / write / read-back
 * through the generation-neutral [OperationGateway] using the edit session's
 * bound [OperationTarget] — never the live UI selection.
 */
class OperationGatewayPublishGateway(
    private val gateway: OperationGateway
) : PublishGateway {

    override suspend fun preflight(session: EditSession): Result<NacosConfiguration?> {
        val coordinate = ConfigurationCoordinate(session.dataId, session.group)
        return gateway.readDetail(
            target = session.target,
            coordinate = coordinate,
            forceRefresh = true,
            useCache = false
        )
    }

    override suspend fun write(session: EditSession, command: PublishCommand): Result<PublishOutcome> {
        return gateway.publish(session.target, command).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                when (error) {
                    is RemoteOperationError.WriteConflict -> Result.success(PublishOutcome.CasConflict)
                    else -> Result.failure(error)
                }
            }
        )
    }

    override suspend fun readBack(session: EditSession): Result<NacosConfiguration?> {
        val coordinate = ConfigurationCoordinate(session.dataId, session.group)
        return gateway.readDetail(
            target = session.target,
            coordinate = coordinate,
            forceRefresh = true,
            useCache = false
        )
    }
}
