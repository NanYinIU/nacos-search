package com.nanyin.nacos.search.services.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The outcome of asking to start an edit. A closed set, so a caller cannot
 * silently let the user type into a draft that could never be published.
 */
sealed interface EditStart {
    /** Editing started; [session] is what the project now holds. */
    data class Started(val session: EditSession) : EditStart

    /**
     * Publishing is not enabled for the selected environment (ADR-0026), so no
     * session was created. The edit action explains this instead of letting the
     * user type and fail at save time.
     */
    data class WritesWithheld(val cause: WriteIntent.Cause) : EditStart

    /** No operation target could be captured for the selection. */
    data class Unavailable(val error: Throwable) : EditStart
}

/**
 * What an action that would destroy a draft must do first (ADR-0027). A draft is
 * never discarded silently: the only way past [ConfirmDiscard] is an explicit
 * [EditSessionService.discardDraft]. [RefuseInFlight] and [RequireWarnedAbandon]
 * are not ordinary discard prompts — mid-write the target must not be destroyed,
 * and after server-state-unknown only [EditSessionService.abandonPublish] may
 * drop the draft.
 */
sealed interface DraftGuard {
    /** No draft is at stake. Proceed with no prompt. */
    object Proceed : DraftGuard

    /**
     * The view is already showing this draft — either because the action names
     * the configuration it is bound to, or because the action is not about a
     * configuration at all. There is nothing to prompt about and nothing to
     * reload or clear: doing so would discard the draft as silently as
     * retargeting would.
     */
    object AlreadyEditing : DraftGuard

    /**
     * A dirty draft would be lost. The caller prompts with exactly two choices:
     * cancel the action, or discard the draft and proceed.
     */
    data class ConfirmDiscard(val draft: EditBinding) : DraftGuard

    /**
     * A publish is in [PublishState.Publishing] or [PublishState.Verifying].
     * Target-destroying actions are refused outright — no ordinary discard.
     */
    object RefuseInFlight : DraftGuard

    /**
     * The draft is in server-state-unknown. Ordinary discard is not offered:
     * only reconciliation, copying the draft, or an explicit
     * [EditSessionService.abandonPublish] that warns the server may already
     * have changed.
     */
    data class RequireWarnedAbandon(val draft: EditBinding) : DraftGuard
}

/**
 * Notified when [EditSessionService.discardDraft] or
 * [EditSessionService.abandonPublish] actually drops a held session. Panels
 * that render the draft use this to leave edit mode when Settings (or any
 * other non-panel site) discards on the caller's behalf — the session lives
 * outside the tool window (ADR-0046), so discarding there cannot update Swing
 * by itself.
 */
fun interface DraftDiscardListener {
    fun onDraftDiscarded()
}

/**
 * The project selection an edit is started against, and the credentials a
 * publish runs with.
 *
 * Capturing a target reads PasswordSafe, so it is a suspending call and never
 * happens on the event dispatch thread (ADR-0039). It is also the **only**
 * place a credential is read: the edit session binds an access identity and
 * captures fresh operation credentials per operation (ADR-0027).
 */
interface EditEnvironment {
    /** The environment profile currently selected for this project, or null. */
    fun selectedProfile(): EnvironmentProfile?

    /** The profile with [profileId] as it stands now, or null when it is gone. */
    fun profile(profileId: String): EnvironmentProfile?

    /** Captures a fresh operation target for [profileId] in [namespaceId]. */
    suspend fun captureTarget(profileId: String, namespaceId: String): Result<OperationTarget>
}

/**
 * Holds the one edit session a project may have, outside the tool window.
 *
 * ADR-0046: a session that lives inside the tool-window content cannot block
 * that content from being destroyed, and ADR-0027 requires exactly that. So the
 * draft belongs to the project, panels only render it, and every action that
 * would destroy it asks this service first.
 *
 * Publishing lives here too, because ADR-0027 derives the publish target solely
 * from the session's binding.
 *
 * P0 provides no draft shelf. Nothing here is persisted.
 */
@Service(Service.Level.PROJECT)
class EditSessionService internal constructor(
    private val gateway: () -> OperationGateway,
    private val environment: EditEnvironment
) {
    @Suppress("unused")
    constructor(project: Project) : this(
        { ApplicationManager.getApplication().getService(NacosApiService::class.java).operationGateway() },
        ProjectEditEnvironment(project)
    )

    private val lock = Any()
    private var session: EditSession? = null
    private var pendingPublish: PreparedPublish? = null
    /** Profile revision captured when the draft started (ADR-0024 / ADR-0027). */
    private var boundProfileRevision: Long = 0
    /** Sticky after an ambiguous write; cleared only by reconcile or abandon. */
    private var serverStateUnknown: Boolean = false
    /** Set when a fresh preflight is required before the next write. */
    private var requiresFreshPreflight: Boolean = false
    /** Confirmation of the retained pre-write detail for the open draft. */
    private var preWriteConfirmation: DatasetConfirmation = DatasetConfirmation.CONFIRMED
    private val discardListeners = CopyOnWriteArrayList<DraftDiscardListener>()
    private val _publishState = MutableStateFlow<PublishState?>(null)

    /**
     * The live publish phase, if any. [PublishState.Publishing] and
     * [PublishState.Verifying] are visible here while they last.
     */
    val publishState: StateFlow<PublishState?> = _publishState.asStateFlow()

    fun currentSession(): EditSession? = synchronized(lock) { session }

    fun isDirty(): Boolean = currentSession()?.isDirty == true

    fun isAwaitingConfirmation(): Boolean = synchronized(lock) { pendingPublish != null }

    fun isServerStateUnknown(): Boolean = synchronized(lock) { serverStateUnknown }

    fun requiresFreshPreflight(): Boolean = synchronized(lock) { requiresFreshPreflight }

    /**
     * Confirmation of the retained pre-write detail. Becomes
     * [DatasetConfirmation.UNCONFIRMED] immediately on server-state-unknown.
     */
    fun preWriteDetailConfirmation(): DatasetConfirmation =
        synchronized(lock) { preWriteConfirmation }

    /**
     * Registers [listener] for [discardDraft] / [abandonPublish] notifications.
     * Returns a handle that unregisters it — callers that own a
     * [com.intellij.openapi.Disposable] should release the handle from that
     * disposable so a disposed panel never hears about a later discard.
     */
    fun addDiscardListener(listener: DraftDiscardListener): AutoCloseable {
        discardListeners.add(listener)
        return AutoCloseable { discardListeners.remove(listener) }
    }

    /**
     * Starts editing [configuration], binding the draft to the profile, access
     * identity, canonical namespace and coordinate that are current **now**.
     * [namespaceId] is the namespace the tool window is operating in, which the
     * caller resolves; the configuration's own tenant may be absent from a list
     * response.
     */
    suspend fun beginEdit(
        configuration: NacosConfiguration,
        namespaceId: String?,
        baselineContent: String
    ): EditStart {
        val profile = environment.selectedProfile()
        val writeIntent = WriteIntent.of(profile)
        if (writeIntent is WriteIntent.Withheld) return EditStart.WritesWithheld(writeIntent.cause)
        val profileId = requireNotNull(profile).id

        val target = environment.captureTarget(profileId, namespaceId.orEmpty())
            .getOrElse { return EditStart.Unavailable(it) }

        val started = EditSession(
            binding = EditBinding.of(
                profileId = profileId,
                identity = target.context.identity,
                namespaceId = namespaceId,
                dataId = configuration.dataId,
                group = configuration.group
            ),
            baselineContent = baselineContent,
            baselineMd5 = configuration.md5,
            baselineType = configuration.type,
            baselineAppName = configuration.appName,
            baselineDesc = configuration.desc,
            baselineConfigTags = configuration.configTags,
            draftContent = baselineContent,
            writeIntent = writeIntent
        )
        synchronized(lock) {
            session = started
            pendingPublish = null
            boundProfileRevision = target.context.profileRevision
            serverStateUnknown = false
            requiresFreshPreflight = false
            preWriteConfirmation = DatasetConfirmation.CONFIRMED
            _publishState.value = null
        }
        return EditStart.Started(started)
    }

    fun updateDraft(content: String) = synchronized(lock) {
        // Mid-write: do not mutate the in-flight command's draft identity.
        if (isInFlightLocked()) return@synchronized
        pendingPublish = null
        if (_publishState.value is PublishState.AwaitingConfirmation) {
            _publishState.value = PublishState.Dirty
        }
        session = session?.copy(draftContent = content)
    }

    /**
     * Drops the draft. This is the explicit discard a [DraftGuard.ConfirmDiscard]
     * demands; nothing else in the plugin may throw a draft away. Refused while
     * publishing/verifying, and while server-state-unknown — use
     * [abandonPublish] for the latter. When a session was actually present,
     * [DraftDiscardListener]s are notified so renderers outside this service can
     * leave edit mode.
     */
    fun discardDraft() {
        val dropped = synchronized(lock) {
            if (isInFlightLocked() || serverStateUnknown) return
            val had = session != null
            clearSessionLocked()
            had
        }
        if (dropped) notifyDiscarded()
    }

    /**
     * Warned abandon after server-state-unknown (ADR-0027). Drops the draft and
     * never writes to cache. Returns false when not in server-state-unknown.
     */
    fun abandonPublish(): Boolean {
        val dropped = synchronized(lock) {
            if (!serverStateUnknown) return false
            val had = session != null
            clearSessionLocked()
            had
        }
        if (dropped) notifyDiscarded()
        return dropped
    }

    private fun notifyDiscarded() {
        for (listener in discardListeners) listener.onDraftDiscarded()
    }

    private fun clearSessionLocked() {
        session = null
        pendingPublish = null
        boundProfileRevision = 0
        serverStateUnknown = false
        requiresFreshPreflight = false
        preWriteConfirmation = DatasetConfirmation.CONFIRMED
        _publishState.value = null
    }

    private fun isInFlightLocked(): Boolean {
        val state = _publishState.value
        return state is PublishState.Publishing || state is PublishState.Verifying
    }

    /**
     * Shared answer for every ADR-0027 blocking rule. Mid-write refuses;
     * server-state-unknown requires warned abandon; otherwise ordinary discard
     * prompting when dirty.
     */
    private fun guardDestroyingAction(sameTarget: Boolean = false): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        synchronized(lock) {
            if (isInFlightLocked()) return DraftGuard.RefuseInFlight
            if (serverStateUnknown) return DraftGuard.RequireWarnedAbandon(current.binding)
        }
        if (sameTarget) {
            return if (current.isDirty) DraftGuard.AlreadyEditing else DraftGuard.Proceed
        }
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    fun guardRetarget(namespaceId: String?, dataId: String?, group: String?): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        return guardDestroyingAction(sameTarget = current.binding.names(namespaceId, dataId, group))
    }

    /**
     * Empty-list / no-namespace clear never prompts. Mid-write still refuses;
     * server-state-unknown keeps the view showing the draft ([AlreadyEditing]).
     */
    fun guardClear(): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        synchronized(lock) {
            if (isInFlightLocked()) return DraftGuard.RefuseInFlight
            if (serverStateUnknown) return DraftGuard.AlreadyEditing
        }
        return if (current.isDirty) DraftGuard.AlreadyEditing else DraftGuard.Proceed
    }

    fun guardDestroy(): DraftGuard = guardDestroyingAction()

    /**
     * Whether switching the project environment to [newProfileId] may proceed.
     * Staying on the draft's own profile is not a switch. A dirty draft bound to
     * a different profile would be orphaned, so the caller must prompt.
     */
    fun guardEnvironmentSwitch(newProfileId: String): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.profileId == newProfileId) return DraftGuard.Proceed
        return guardDestroyingAction()
    }

    /**
     * Whether switching the selected namespace to [newNamespaceId] may proceed.
     * [newNamespaceId] is compared canonically (ADR-0015). Staying on the
     * draft's own namespace is not a switch.
     */
    fun guardNamespaceSwitch(newNamespaceId: String?): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.namespaceId == NamespaceInfo.canonicalId(newNamespaceId)) {
            return DraftGuard.Proceed
        }
        return guardDestroyingAction()
    }

    /**
     * Whether turning write intent off for [profileId] may proceed. Only the
     * draft's own profile matters: withholding writes for an unrelated profile
     * cannot orphan this draft. Turning writes off for the bound profile would
     * leave a draft that can no longer be published (ADR-0026 / ADR-0027).
     */
    fun guardWriteIntentOff(profileId: String): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.profileId != profileId) return DraftGuard.Proceed
        return guardDestroyingAction()
    }

    /**
     * Whether deleting the environment profile [profileId] may proceed. Only the
     * draft's own profile matters: deleting an unrelated profile leaves the
     * draft publishable. Deleting the bound profile would leave a draft that
     * can no longer be published (ADR-0025 / ADR-0027).
     */
    fun guardProfileDeletion(profileId: String): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.profileId != profileId) return DraftGuard.Proceed
        return guardDestroyingAction()
    }

    /**
     * Whether closing the project may proceed. Same shape as [guardDestroy]: a
     * dirty draft is project-scoped (ADR-0046) and must not be dropped silently
     * when the project that holds it goes away.
     */
    fun guardProjectClose(): DraftGuard = guardDestroyingAction()

    /**
     * Runs preflight for the held draft and, on success, parks at
     * [PublishState.AwaitingConfirmation] carrying the diff and the named
     * target derived from the session binding (ADR-0027 / ADR-0028). Sends
     * nothing. Returns null when there is no draft.
     *
     * Blocked while server-state-unknown — [reconcilePublish] is the only
     * retryable network action then. The target is captured fresh from the
     * session's binding — never from the current selection.
     */
    suspend fun requestPublish(): PublishResult? {
        val current = currentSession() ?: return null
        synchronized(lock) {
            if (serverStateUnknown) {
                return emit(PublishResult(PublishState.ServerStateUnknown, isDirty = true))
            }
            if (isInFlightLocked()) {
                return PublishResult(
                    _publishState.value ?: PublishState.Publishing,
                    isDirty = true
                )
            }
            pendingPublish = null
        }

        // ADR-0026's opt-in can still change underneath a draft. Turning write
        // intent off is itself guarded by [guardWriteIntentOff] so Settings
        // cannot silently orphan a dirty draft (issue #77). The save path still
        // fails closed by re-running the same check against the bound profile —
        // the check, not a snapshot of it.
        val boundProfile = environment.profile(current.binding.profileId)
        if (WriteIntent.of(boundProfile) !is WriteIntent.Granted) {
            return emit(PublishResult(
                PublishState.ReadOnly("Publishing is disabled for this environment"),
                isDirty = current.isDirty
            ))
        }
        val target = environment.captureTarget(current.binding.profileId, current.binding.namespaceId)
            .getOrElse {
                return emit(PublishResult(
                    PublishState.ReadOnly("The environment this draft belongs to is unavailable"),
                    isDirty = current.isDirty
                ))
            }
        if (target.context.identity != current.binding.identity) {
            return emit(PublishResult(
                PublishState.ReadOnly("The access identity changed after this draft was started"),
                isDirty = current.isDirty
            ))
        }
        // Operation-affecting profile change: keep draft, force this fresh preflight.
        synchronized(lock) {
            if (target.context.profileRevision != boundProfileRevision) {
                requiresFreshPreflight = true
                boundProfileRevision = target.context.profileRevision
            }
        }

        val namedTarget = PublishNamedTarget.of(
            binding = current.binding,
            profileDisplayName = boundProfile?.displayName
        )
        val controller = PublishController(OperationGatewayPublishGateway(gateway()))
        return when (val outcome = controller.prepare(current, target, namedTarget)) {
            is PublishPrepareOutcome.Ready -> {
                synchronized(lock) {
                    if (session !== outcome.prepared.session) {
                        requiresFreshPreflight = true
                        return emit(PublishResult(
                            PublishState.Dirty,
                            isDirty = session?.isDirty == true,
                            requiresFreshPreflight = true
                        ))
                    }
                    pendingPublish = outcome.prepared
                    requiresFreshPreflight = false
                    _publishState.value = outcome.awaiting
                }
                outcome.result
            }
            is PublishPrepareOutcome.Blocked -> emit(outcome.result)
        }
    }

    suspend fun confirmPublish(): PublishResult? {
        val prepared = synchronized(lock) {
            if (serverStateUnknown) return null
            val pending = pendingPublish ?: return null
            pendingPublish = null
            if (session !== pending.session ||
                session?.draftContent != pending.command.content
            ) {
                requiresFreshPreflight = true
                return emit(PublishResult(
                    PublishState.Dirty,
                    isDirty = session?.isDirty == true,
                    requiresFreshPreflight = true
                ))
            }
            pending
        }

        val targetCheck = environment.captureTarget(
            prepared.session.binding.profileId,
            prepared.session.binding.namespaceId
        ).getOrNull()
        if (targetCheck == null ||
            targetCheck.context.identity != prepared.session.binding.identity
        ) {
            return emit(PublishResult(
                PublishState.ReadOnly("The access identity changed after this draft was started"),
                isDirty = true
            ))
        }
        val revisionAtPrepare = synchronized(lock) { boundProfileRevision }
        if (targetCheck.context.profileRevision != revisionAtPrepare) {
            synchronized(lock) {
                requiresFreshPreflight = true
                boundProfileRevision = targetCheck.context.profileRevision
            }
            return emit(PublishResult(
                PublishState.Dirty,
                isDirty = true,
                requiresFreshPreflight = true
            ))
        }

        _publishState.value = PublishState.Publishing
        val result = PublishController(OperationGatewayPublishGateway(gateway()))
            .confirm(prepared) { phase -> _publishState.value = phase }
        return applyTerminal(prepared, result)
    }

    /**
     * The only retryable network action after server-state-unknown: re-reads
     * with the bound target and reconciles. Never sends another write.
     */
    suspend fun reconcilePublish(): PublishResult? {
        val current = synchronized(lock) {
            if (!serverStateUnknown) return null
            session ?: return null
        }
        val target = environment.captureTarget(current.binding.profileId, current.binding.namespaceId)
            .getOrElse {
                return emit(PublishResult(PublishState.ServerStateUnknown, isDirty = true))
            }
        if (target.context.identity != current.binding.identity) {
            return emit(PublishResult(
                PublishState.ReadOnly("The access identity changed after this draft was started"),
                isDirty = true
            ))
        }

        _publishState.value = PublishState.Verifying
        val publishGateway = OperationGatewayPublishGateway(gateway())
        val readBack = publishGateway.readBack(target, current.binding.coordinate)
        if (readBack.isFailure) {
            return emit(PublishResult(PublishState.ServerStateUnknown, isDirty = true))
        }
        val readBackDetail = readBack.getOrNull()
        val preflightDetail = NacosConfiguration(
            dataId = current.binding.dataId,
            group = current.binding.group,
            content = current.baselineContent,
            md5 = current.baselineMd5,
            type = current.baselineType,
            appName = current.baselineAppName,
            desc = current.baselineDesc,
            configTags = current.baselineConfigTags
        )
        val command = current.toCommand(readBackDetail ?: preflightDetail)
        val result = PublishController(publishGateway).reconcile(
            current, command, preflightDetail, readBackDetail
        )
        return applyTerminal(prepared = null, result = result)
    }

    fun cancelPublish(): PublishResult? {
        val cleared = synchronized(lock) {
            val pending = pendingPublish
            pendingPublish = null
            pending
        } ?: return null
        return emit(PublishResult(
            state = PublishState.Dirty,
            isDirty = cleared.session.isDirty
        ))
    }

    private fun emit(result: PublishResult): PublishResult {
        _publishState.value = result.state
        return result
    }

    private fun applyTerminal(prepared: PreparedPublish?, result: PublishResult): PublishResult {
        val terminal = when (result.state) {
            is PublishState.Dirty -> result.copy(requiresFreshPreflight = true)
            else -> result
        }
        when (val state = terminal.state) {
            is PublishState.Verified -> {
                synchronized(lock) {
                    if (prepared == null || session === prepared.session) {
                        clearSessionLocked()
                    }
                    _publishState.value = PublishState.Verified
                }
            }
            is PublishState.ServerStateUnknown -> {
                // The retained pre-write detail for this draft is no longer
                // confirmed (ADR-0028 / ADR-0036). AccessVisibility will own
                // the durable confirmation machine later; while a draft is
                // open, the session is what retains that detail.
                synchronized(lock) {
                    serverStateUnknown = true
                    requiresFreshPreflight = true
                    preWriteConfirmation = DatasetConfirmation.UNCONFIRMED
                    _publishState.value = PublishState.ServerStateUnknown
                }
            }
            is PublishState.Dirty -> {
                synchronized(lock) {
                    // A definitive not-currently-visible read-back leaves SSU.
                    serverStateUnknown = false
                    requiresFreshPreflight = true
                    _publishState.value = PublishState.Dirty
                }
            }
            else -> {
                // Conflict / deleted / permission / read-only: definitive, leave SSU.
                synchronized(lock) {
                    if (state !is PublishState.Publishing && state !is PublishState.Verifying) {
                        serverStateUnknown = false
                    }
                    _publishState.value = state
                }
            }
        }
        return terminal
    }
}

/**
 * Reads the project-selected profile and captures its operation target. Both
 * come from the project session rather than the app-wide active server, so one
 * project's edits cannot publish to another project's environment.
 */
private class ProjectEditEnvironment(private val project: Project) : EditEnvironment {
    private val settings: NacosSettings
        get() = ApplicationManager.getApplication().getService(NacosSettings::class.java)

    override fun selectedProfile(): EnvironmentProfile? {
        val profileId = project.getService(NacosProjectSession::class.java)
            ?.sessionState?.selectedProfileId
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return profile(profileId)
    }

    override fun profile(profileId: String): EnvironmentProfile? = settings.getProfile(profileId)

    override suspend fun captureTarget(profileId: String, namespaceId: String): Result<OperationTarget> {
        // Do not heal: deleted explicit profiles must fail closed (ADR-0025).
        val context = withContext(Dispatchers.IO) { settings.captureOperationContext(profileId) }
            .getOrElse { return Result.failure(it) }
        return ApplicationManager.getApplication().getService(NacosApiService::class.java)
            .resolveOperationTarget(context, namespaceId)
    }
}
