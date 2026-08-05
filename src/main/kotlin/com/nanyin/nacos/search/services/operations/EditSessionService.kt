package com.nanyin.nacos.search.services.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.Dispatchers
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
 * [EditSessionService.discardDraft].
 */
sealed interface DraftGuard {
    /** No draft is at stake. Proceed with no prompt. */
    data object Proceed : DraftGuard

    /**
     * The view is already showing this draft — either because the action names
     * the configuration it is bound to, or because the action is not about a
     * configuration at all. There is nothing to prompt about and nothing to
     * reload or clear: doing so would discard the draft as silently as
     * retargeting would.
     */
    data object AlreadyEditing : DraftGuard

    /**
     * A dirty draft would be lost. The caller prompts with exactly two choices:
     * cancel the action, or discard the draft and proceed.
     */
    data class ConfirmDiscard(val draft: EditBinding) : DraftGuard
}

/**
 * Notified when [EditSessionService.discardDraft] actually drops a held session.
 * Panels that render the draft use this to leave edit mode when Settings (or
 * any other non-panel site) discards on the caller's behalf — the session lives
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
 * would destroy it — selecting another configuration, switching environment or
 * namespace, turning write intent off, deleting its profile, closing the
 * project, and destroying the tool-window content — asks this service first.
 *
 * Publishing lives here too, because ADR-0027 derives the publish target solely
 * from the session's binding: changing the selection after starting a draft
 * cannot retarget where it publishes.
 *
 * P0 provides no draft shelf. Nothing here is persisted, and no configuration
 * content is written anywhere.
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
    private val discardListeners = CopyOnWriteArrayList<DraftDiscardListener>()

    /** The draft this project holds, or null when nothing is being edited. */
    fun currentSession(): EditSession? = synchronized(lock) { session }

    /** Whether the held draft differs from the baseline it was started from. */
    fun isDirty(): Boolean = currentSession()?.isDirty == true

    /**
     * Registers [listener] for [discardDraft] notifications. Returns a handle
     * that unregisters it — callers that own a [com.intellij.openapi.Disposable]
     * should release the handle from that disposable so a disposed panel never
     * hears about a later discard.
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
        // WriteIntent.Granted is only produced from a non-null profile.
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
        synchronized(lock) { session = started }
        return EditStart.Started(started)
    }

    /** Records what the user has typed. No-op when nothing is being edited. */
    fun updateDraft(content: String) = synchronized(lock) {
        session = session?.copy(draftContent = content)
    }

    /**
     * Drops the draft. This is the explicit discard a [DraftGuard.ConfirmDiscard]
     * demands; nothing else in the plugin may throw a draft away. When a session
     * was actually present, [DraftDiscardListener]s are notified so renderers
     * outside this service can leave edit mode.
     */
    fun discardDraft() {
        val dropped = synchronized(lock) {
            val had = session != null
            session = null
            had
        }
        if (dropped) {
            for (listener in discardListeners) {
                listener.onDraftDiscarded()
            }
        }
    }

    /**
     * Whether selecting the configuration at [namespaceId]/[dataId]/[group] may
     * proceed. Pass nulls for "nothing selected", which is still a retarget away
     * from a draft.
     */
    fun guardRetarget(namespaceId: String?, dataId: String?, group: String?): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.names(namespaceId, dataId, group)) {
            return if (current.isDirty) DraftGuard.AlreadyEditing else DraftGuard.Proceed
        }
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    /**
     * Whether the tool window may clear its detail view because it has nothing
     * left to show — the result list came back empty, or no namespace is
     * selected.
     *
     * This guard never prompts, and that is deliberate. An empty result list is
     * not a statement about the configuration being edited, and search is
     * debounced, so a few keystrokes that match nothing would put a modal
     * dialog in front of the user mid-typing. A dirty draft simply survives:
     * the view keeps showing it, and it stays publishable because its target
     * comes from its binding rather than from whatever the list holds.
     */
    fun guardClear(): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        return if (current.isDirty) DraftGuard.AlreadyEditing else DraftGuard.Proceed
    }

    /** Whether closing or destroying the tool-window content may proceed. */
    fun guardDestroy(): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    /**
     * Whether switching the project environment to [newProfileId] may proceed.
     * Staying on the draft's own profile is not a switch. A dirty draft bound to
     * a different profile would be orphaned, so the caller must prompt.
     */
    fun guardEnvironmentSwitch(newProfileId: String): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        if (current.binding.profileId == newProfileId) return DraftGuard.Proceed
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    /**
     * Whether switching the selected namespace to [newNamespaceId] may proceed.
     * [newNamespaceId] is compared canonically (ADR-0015). Staying on the
     * draft's own namespace is not a switch.
     */
    fun guardNamespaceSwitch(newNamespaceId: String?): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        val canonical = NamespaceInfo.canonicalId(newNamespaceId)
        if (current.binding.namespaceId == canonical) return DraftGuard.Proceed
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
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
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
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
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    /**
     * Whether closing the project may proceed. Same shape as [guardDestroy]: a
     * dirty draft is project-scoped (ADR-0046) and must not be dropped silently
     * when the project that holds it goes away.
     */
    fun guardProjectClose(): DraftGuard {
        val current = currentSession() ?: return DraftGuard.Proceed
        return if (current.isDirty) DraftGuard.ConfirmDiscard(current.binding) else DraftGuard.Proceed
    }

    /**
     * Publishes the held draft, or returns null when there is nothing to
     * publish. The target is captured fresh from the session's binding — never
     * from the current selection — and must still resolve to the access
     * identity the draft was bound to; an identity that moved underneath it
     * leaves the text available for copying but blocks the write (ADR-0027).
     *
     * Only a verified publish clears the draft (ADR-0028).
     */
    suspend fun publish(): PublishResult? {
        val current = currentSession() ?: return null
        // ADR-0026's opt-in can still change underneath a draft (e.g. another
        // settings surface, or a race with apply). The save path fails closed by
        // re-running the same check against the profile the draft is bound to —
        // the check, not a snapshot of it. Turning write intent off is itself
        // guarded by [guardWriteIntentOff] so the user cannot silently orphan a
        // dirty draft from Settings (issue #77).
        if (WriteIntent.of(environment.profile(current.binding.profileId)) !is WriteIntent.Granted) {
            return PublishResult(
                PublishState.ReadOnly("Publishing is disabled for this environment"),
                isDirty = current.isDirty
            )
        }
        val target = environment.captureTarget(current.binding.profileId, current.binding.namespaceId)
            .getOrElse {
                return PublishResult(
                    PublishState.ReadOnly("The environment this draft belongs to is unavailable"),
                    isDirty = current.isDirty
                )
            }
        if (target.context.identity != current.binding.identity) {
            return PublishResult(
                PublishState.ReadOnly("The access identity changed after this draft was started"),
                isDirty = current.isDirty
            )
        }

        val result = PublishController(OperationGatewayPublishGateway(gateway())).publish(current, target)
        if (result.state is PublishState.Verified) {
            synchronized(lock) { if (session === current) session = null }
        }
        return result
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
