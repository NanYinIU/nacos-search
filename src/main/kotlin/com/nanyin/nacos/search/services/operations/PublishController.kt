package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosConfiguration

/** Result of a full publish flow. */
data class PublishResult(
    val state: PublishState,
    val isDirty: Boolean,
    val verifiedDetail: NacosConfiguration? = null,
    /**
     * True when the draft was retained and the next write must run a fresh
     * preflight first — after a not-currently-visible read-back, or an
     * operation-affecting profile change (ADR-0027 / ADR-0028).
     */
    val requiresFreshPreflight: Boolean = false
)

/**
 * Material held after a successful preflight, ready for one non-retried write
 * when the user confirms. Carries the captured [target] (with its credential
 * snapshot) and the command built from the preflight detail — neither is
 * exposed on [PublishState.AwaitingConfirmation], which is what the UI sees.
 */
data class PreparedPublish(
    val session: EditSession,
    val target: OperationTarget,
    val command: PublishCommand,
    val preflightDetail: NacosConfiguration,
    val namedTarget: PublishNamedTarget
)

/**
 * Outcome of [PublishController.prepare]: either ready for confirmation, or a
 * terminal failure that never reaches the write.
 */
sealed class PublishPrepareOutcome {
    abstract val result: PublishResult

    /** Preflight matched the baseline; confirmation may proceed. */
    data class Ready(
        val prepared: PreparedPublish,
        val awaiting: PublishState.AwaitingConfirmation
    ) : PublishPrepareOutcome() {
        override val result: PublishResult
            get() = PublishResult(awaiting, isDirty = prepared.session.isDirty)
    }

    /** Policy, preflight, or conflict blocked the publish; nothing is pending. */
    data class Blocked(override val result: PublishResult) : PublishPrepareOutcome()
}

/**
 * The publish boundary. The controller does not know about adapters or
 * transports directly; it delegates to this interface so tests can script
 * outcomes and production wires it to the OperationGateway.
 *
 * Every call names the [OperationTarget] explicitly because the edit session
 * holds no credential (ADR-0027): the target is captured fresh from the
 * session's binding when a publish runs, and handed in here.
 */
interface PublishGateway {
    suspend fun preflight(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?>
    suspend fun write(target: OperationTarget, command: PublishCommand): Result<PublishOutcome>
    suspend fun readBack(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?>
}

/**
 * Orchestrates the controlled publish state machine outside Swing.
 *
 * The algorithm (design §16.3 / ADR-0028):
 * 1. Preflight: re-read detail; not-found → TARGET_DELETED.
 * 2. Compare baseline content/MD5; mismatch → REMOTE_CONFLICT.
 * 3. Generate PublishCommand preserving metadata.
 * 4. Encrypted config → READ_ONLY.
 * 5. Enter AWAITING_CONFIRMATION with the diff and named target — no write yet.
 * 6. On explicit confirm: send one write; CAS rejection → REMOTE_CONFLICT.
 * 7. No transport or auth replay.
 * 8. Possible post-send failure → SERVER_STATE_UNKNOWN.
 * 9. Immediate read-back; only verified clears dirty.
 *
 * Confirmation is a state plus a command, not a callback: [prepare] parks the
 * machine at [PublishState.AwaitingConfirmation], and [confirm] / cancel (at
 * the service) complete the ceremony.
 */
class PublishController(private val gateway: PublishGateway) {

    /**
     * Runs preflight and builds the command. On success returns [PublishPrepareOutcome.Ready]
     * carrying [PublishState.AwaitingConfirmation] — nothing has been written.
     * The target is captured fresh from the session's binding by the caller and
     * is never carried on the session itself (ADR-0027).
     */
    suspend fun prepare(
        session: EditSession,
        target: OperationTarget,
        namedTarget: PublishNamedTarget
    ): PublishPrepareOutcome {
        if (session.writeIntent !is WriteIntent.Granted) {
            return PublishPrepareOutcome.Blocked(
                PublishResult(
                    PublishState.ReadOnly("Publishing is disabled for this environment"),
                    isDirty = session.isDirty
                )
            )
        }
        val coordinate = session.binding.coordinate
        // Phase 1: Preflight
        val preflightResult = gateway.preflight(target, coordinate)
        if (preflightResult.isFailure) {
            val error = preflightResult.exceptionOrNull()!!
            return PublishPrepareOutcome.Blocked(
                when (error) {
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
            )
        }

        val remoteDetail = preflightResult.getOrNull()
        if (remoteDetail == null) {
            return PublishPrepareOutcome.Blocked(
                PublishResult(PublishState.TargetDeleted, isDirty = true)
            )
        }

        // Encrypted config is read-only
        if (!remoteDetail.encryptedDataKey.isNullOrBlank()) {
            return PublishPrepareOutcome.Blocked(
                PublishResult(
                    PublishState.ReadOnly("Configuration is encrypted"),
                    isDirty = true
                )
            )
        }

        // Phase 2: Conflict detection — compare baseline content/MD5
        val contentChanged = remoteDetail.content != session.baselineContent
        val md5Changed = session.baselineMd5 != null && remoteDetail.md5 != null &&
            session.baselineMd5 != remoteDetail.md5
        if (contentChanged || md5Changed) {
            return PublishPrepareOutcome.Blocked(
                PublishResult(
                    PublishState.RemoteConflict(remoteDetail.content, remoteDetail.md5),
                    isDirty = true
                )
            )
        }

        // Phase 3: Build command from preflight, preserving metadata
        val command = session.toCommand(remoteDetail)
        val awaiting = PublishState.AwaitingConfirmation(
            diff = PublishDiff(
                baselineContent = session.baselineContent,
                draftContent = session.draftContent
            ),
            namedTarget = namedTarget
        )
        return PublishPrepareOutcome.Ready(
            prepared = PreparedPublish(
                session = session,
                target = target,
                command = command,
                preflightDetail = remoteDetail,
                namedTarget = namedTarget
            ),
            awaiting = awaiting
        )
    }

    /**
     * Performs the single non-retried write and the immediate read-back for a
     * previously [prepare]d publish. Must not be called without an explicit
     * user confirmation of the awaiting state. [onPhase] observes
     * [PublishState.Publishing] then [PublishState.Verifying] while they last.
     */
    suspend fun confirm(
        prepared: PreparedPublish,
        onPhase: (PublishState) -> Unit = {}
    ): PublishResult {
        val session = prepared.session
        val target = prepared.target
        val command = prepared.command
        val remoteDetail = prepared.preflightDetail
        val coordinate = session.binding.coordinate

        // Phase 4: Send one write (no transport or auth replay)
        onPhase(PublishState.Publishing)
        val writeResult = gateway.write(target, command)
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

        // CAS conflict (V1 only) — re-read so the conflict diff shows the
        // concurrent remote value, not the stale preflight baseline.
        if (writeResult.getOrNull() == PublishOutcome.CasConflict) {
            onPhase(PublishState.Verifying)
            val conflictRead = gateway.readBack(target, coordinate)
            val remote = conflictRead.getOrNull() ?: remoteDetail
            return PublishResult(
                PublishState.RemoteConflict(remote.content, remote.md5),
                isDirty = true
            )
        }

        // Phase 5: Immediate read-back using the original context
        onPhase(PublishState.Verifying)
        val readBackResult = gateway.readBack(target, coordinate)
        if (readBackResult.isFailure) {
            return PublishResult(
                PublishState.ServerStateUnknown, isDirty = true
            )
        }

        val readBackDetail = readBackResult.getOrNull()
        return reconcile(session, command, remoteDetail, readBackDetail)
    }

    /**
     * Compares a read-back to the command and preflight baseline. Used after
     * the write and again by reconciliation after server-state-unknown.
     */
    fun reconcile(
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
            return PublishResult(
                PublishState.Dirty,
                isDirty = true,
                requiresFreshPreflight = true
            )
        }

        // The read-back carries the command's content but is missing metadata the
        // command was supposed to preserve (type, appName, desc, or configTags).
        // The write is only partially visible, so this is neither verified nor a
        // remote conflict; retain the draft and require a new preflight.
        if (readBackDetail.content == command.content && !metadataMatches(command, readBackDetail)) {
            return PublishResult(
                PublishState.Dirty,
                isDirty = true,
                requiresFreshPreflight = true
            )
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
