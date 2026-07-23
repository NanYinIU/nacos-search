package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosConfiguration

/** Result of a full publish flow. */
data class PublishResult(
    val state: PublishState,
    val isDirty: Boolean,
    val verifiedDetail: NacosConfiguration? = null
)

/**
 * The publish boundary. The controller does not know about adapters or
 * transports directly; it delegates to this interface so tests can script
 * outcomes and production wires it to the OperationGateway.
 */
interface PublishGateway {
    suspend fun preflight(session: EditSession): Result<NacosConfiguration?>
    suspend fun write(session: EditSession, command: PublishCommand): Result<PublishOutcome>
    suspend fun readBack(session: EditSession): Result<NacosConfiguration?>
}

/**
 * Orchestrates the controlled publish state machine outside Swing.
 *
 * The algorithm (design §16.3):
 * 1. Preflight: re-read detail; not-found → TARGET_DELETED.
 * 2. Compare baseline content/MD5; mismatch → REMOTE_CONFLICT.
 * 3. Generate PublishCommand preserving metadata.
 * 4. Encrypted config → READ_ONLY.
 * 5. Send one write; CAS rejection → REMOTE_CONFLICT.
 * 6. No transport or auth replay.
 * 7. Possible post-send failure → SERVER_STATE_UNKNOWN.
 * 8. Immediate read-back; only verified clears dirty.
 */
class PublishController(private val gateway: PublishGateway) {

    suspend fun publish(session: EditSession): PublishResult {
        // Phase 1: Preflight
        val preflightResult = gateway.preflight(session)
        if (preflightResult.isFailure) {
            return when (val error = preflightResult.exceptionOrNull()!!) {
                is RemoteOperationError.Authorization -> PublishResult(
                    PublishState.PermissionDenied, isDirty = true
                )
                is RemoteOperationError.NotFound -> PublishResult(
                    PublishState.TargetDeleted, isDirty = true
                )
                else -> PublishResult(
                    PublishState.ServerStateUnknown, isDirty = true
                )
            }
        }

        val remoteDetail = preflightResult.getOrNull()
        if (remoteDetail == null) {
            return PublishResult(PublishState.TargetDeleted, isDirty = true)
        }

        // Encrypted config is read-only
        if (!remoteDetail.encryptedDataKey.isNullOrBlank()) {
            return PublishResult(
                PublishState.ReadOnly("Configuration is encrypted"),
                isDirty = true
            )
        }

        // Phase 2: Conflict detection — compare baseline content/MD5
        val contentChanged = remoteDetail.content != session.baselineContent
        val md5Changed = session.baselineMd5 != null && remoteDetail.md5 != null &&
            session.baselineMd5 != remoteDetail.md5
        if (contentChanged || md5Changed) {
            return PublishResult(
                PublishState.RemoteConflict(remoteDetail.content, remoteDetail.md5),
                isDirty = true
            )
        }

        // Phase 3: Build command from preflight, preserving metadata
        val command = session.toCommand(remoteDetail)

        // Phase 4: Send one write (no transport or auth replay)
        val writeResult = gateway.write(session, command)
        if (writeResult.isFailure) {
            val error = writeResult.exceptionOrNull()!!
            return when (error) {
                is RemoteOperationError.Authorization -> PublishResult(
                    PublishState.PermissionDenied, isDirty = true
                )
                is RemoteOperationError.Cancelled -> PublishResult(
                    PublishState.ServerStateUnknown, isDirty = true
                )
                // After a possible post-send failure, we cannot know if the
                // write left the client. Enter server-state-unknown.
                is RemoteOperationError.Connection,
                is RemoteOperationError.Server,
                is RemoteOperationError.Protocol -> PublishResult(
                    PublishState.ServerStateUnknown, isDirty = true
                )
                else -> PublishResult(
                    PublishState.ServerStateUnknown, isDirty = true
                )
            }
        }

        // CAS conflict (V1 only)
        if (writeResult.getOrNull() == PublishOutcome.CasConflict) {
            return PublishResult(
                PublishState.RemoteConflict(remoteDetail.content, remoteDetail.md5),
                isDirty = true
            )
        }

        // Phase 5: Immediate read-back using the original context
        val readBackResult = gateway.readBack(session)
        if (readBackResult.isFailure) {
            return PublishResult(
                PublishState.ServerStateUnknown, isDirty = true
            )
        }

        val readBackDetail = readBackResult.getOrNull()
        return reconcile(session, command, remoteDetail, readBackDetail)
    }

    private fun reconcile(
        session: EditSession,
        command: PublishCommand,
        preflightDetail: NacosConfiguration,
        readBackDetail: NacosConfiguration?
    ): PublishResult {
        // Read-back is null: target was deleted after write
        if (readBackDetail == null) {
            return PublishResult(PublishState.TargetDeleted, isDirty = true)
        }

        // Read-back matches the draft content AND all readable metadata that
        // the adapter was supposed to preserve (design §16.3 step 10). Only
        // full semantic equality reaches VERIFIED; a silently lost type, tag,
        // or description is not a clean write.
        if (readBackDetail.content == command.content && metadataMatches(command, readBackDetail)) {
            return PublishResult(
                PublishState.Verified,
                isDirty = false,
                verifiedDetail = readBackDetail
            )
        }

        // Read-back equals preflight baseline: command result not currently visible.
        // Does NOT prove the write never applied; retain draft and require new preflight.
        if (readBackDetail.content == session.baselineContent &&
            readBackDetail.md5 == session.baselineMd5) {
            return PublishResult(PublishState.Dirty, isDirty = true)
        }

        // Content matches but metadata was partially lost. The command result
        // is only partially visible, so this is neither verified nor a remote
        // conflict. Retain the draft and require a new preflight.
        if (readBackDetail.content == session.baselineContent &&
            readBackDetail.md5 == session.baselineMd5) {
            return PublishResult(PublishState.Dirty, isDirty = true)
        }

        // Third value: someone else wrote something different
        return PublishResult(
            PublishState.RemoteConflict(readBackDetail.content, readBackDetail.md5),
            isDirty = true
        )
    }

    /**
     * Checks whether all readable metadata the command intended to preserve
     * was actually persisted and read back. A null on the server side where
     * the command sent a value means the field was silently lost.
     */
    private fun metadataMatches(command: PublishCommand, detail: NacosConfiguration): Boolean {
        // Type is always sent (defaults to "text"), so it must be preserved.
        if (!valuesEqual(command.type, detail.type)) return false
        // appName, desc, configTags are nullable. If the command carried a
        // value the server must echo it back. Null commands with null server
        // values are both absent and therefore equal.
        if (!optionalValuesEqual(command.appName, detail.appName)) return false
        if (!optionalValuesEqual(command.desc, detail.desc)) return false
        if (!optionalValuesEqual(command.configTags, detail.configTags)) return false
        return true
    }

    private fun valuesEqual(commandValue: String, serverValue: String?): Boolean =
        commandValue.equals(serverValue ?: "", ignoreCase = true)

    private fun optionalValuesEqual(commandValue: String?, serverValue: String?): Boolean =
        commandValue.orEmpty().equals(serverValue.orEmpty(), ignoreCase = true)
}
