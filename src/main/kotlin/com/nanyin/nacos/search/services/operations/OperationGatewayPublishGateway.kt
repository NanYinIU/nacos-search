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
        ).map { it.value }
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

    /**
     * The reconciliation read-back obeys the ordering rule like every other
     * remote read rather than being suppressed until the publish state becomes
     * verified (ADR-0020): it always goes to the server, and the detail it
     * observes updates the cache under that observation's sequence, so
     * verifying a publish cannot be undone by an older read.
     */
    override suspend fun readBack(session: EditSession): Result<NacosConfiguration?> {
        val coordinate = ConfigurationCoordinate(session.dataId, session.group)
        return gateway.readDetail(
            target = session.target,
            coordinate = coordinate,
            forceRefresh = true,
            useCache = true
        ).map { it.value }
    }
}
