package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo

/** The immutable command derived from preflight: replaces content,
 * preserves readable metadata from the remote detail.
 */
data class PublishCommand(
    val dataId: String,
    val group: String,
    val content: String,
    val type: String,
    val namespaceId: String,
    val appName: String? = null,
    val desc: String? = null,
    val configTags: String? = null,
    val casMd5: String? = null,
    val encryptedDataKey: String? = null
) {
    val isEncrypted: Boolean get() = !encryptedDataKey.isNullOrBlank()

    /**
     * Generation-neutral form fields both dialects POST. Namespace wire
     * encoding and CAS headers stay on each dialect.
     */
    fun commonFormFields(): List<Pair<String, String>> = buildList {
        add("dataId" to dataId)
        add("group" to group)
        add("content" to content)
        add("type" to type)
        appName?.let { add("appName" to it) }
        desc?.let { add("desc" to it) }
        configTags?.let { add("config_tags" to it) }
    }
}

/** The adapter-level result of a publish attempt. */
sealed class PublishOutcome {
    /** V1: server confirmed the CAS write succeeded. V3: server accepted the POST. */
    data class Written(val body: String) : PublishOutcome()
    /** V1 CAS returned false: a concurrent update was detected. */
    object CasConflict : PublishOutcome()
}

/**
 * Content side of the confirmation ceremony: the baseline that was verified
 * against the server versus the draft that will replace it (ADR-0028).
 */
data class PublishDiff(
    val baselineContent: String,
    val draftContent: String
)

/**
 * The destination a publish will write to, named from the edit session's
 * binding — never from the live UI selection (ADR-0027).
 *
 * Carries the facts the confirmation UI must show: which environment, which
 * endpoint, which namespace, and which configuration coordinate.
 */
data class PublishNamedTarget(
    val profileId: String,
    val profileDisplayName: String,
    val endpoint: String,
    val namespaceId: String,
    val dataId: String,
    val group: String
) {
    companion object {
        /**
         * Builds the named target from [binding] and the profile's display name.
         * Falls back to the profile id when the display name is blank or the
         * profile is gone — the binding remains the source of truth for every
         * other field.
         */
        fun of(binding: EditBinding, profileDisplayName: String?): PublishNamedTarget =
            PublishNamedTarget(
                profileId = binding.profileId,
                profileDisplayName = profileDisplayName?.takeIf { it.isNotBlank() } ?: binding.profileId,
                endpoint = binding.identity.canonicalEndpoint,
                namespaceId = binding.namespaceId,
                dataId = binding.dataId,
                group = binding.group
            )
    }
}

/** State of a controlled publishing session. */
sealed class PublishState {
    data class ReadOnly(val reason: String) : PublishState()
    object Dirty : PublishState()
    object Preflight : PublishState()
    object TargetDeleted : PublishState()
    data class RemoteConflict(val remoteContent: String, val remoteMd5: String?) : PublishState()
    /**
     * Preflight succeeded and the command is ready; nothing has been sent.
     * The caller must [EditSessionService.confirmPublish] or
     * [EditSessionService.cancelPublish]. Carries the diff and the named
     * target so the confirmation UI never re-derives them from selection.
     */
    data class AwaitingConfirmation(
        val diff: PublishDiff,
        val namedTarget: PublishNamedTarget
    ) : PublishState()
    object Publishing : PublishState()
    object Verifying : PublishState()
    object Verified : PublishState()
    object PermissionDenied : PublishState()
    object ServerStateUnknown : PublishState()
}

/**
 * Whether an environment profile permits the plugin to publish ordinary
 * configuration changes (ADR-0026, CONTEXT.md「写入开关」).
 *
 * This is the **one** implementation of that check. The edit action and the
 * save path both consult it, so starting an edit and saving one cannot
 * disagree: an edit that would fail at save time is refused before the user
 * types anything.
 */
sealed interface WriteIntent {
    /** The selected profile opted in to publishing. */
    object Granted : WriteIntent

    /** Publishing is not permitted. [cause] is what the user would have to change. */
    data class Withheld(val cause: Cause) : WriteIntent

    enum class Cause {
        /** No environment profile is selected for this project. */
        NO_PROFILE_SELECTED,

        /** A profile is selected and has not enabled publishing. */
        NOT_OPTED_IN
    }

    companion object {
        fun of(profile: EnvironmentProfile?): WriteIntent = when {
            profile == null -> Withheld(Cause.NO_PROFILE_SELECTED)
            !profile.writeIntent -> Withheld(Cause.NOT_OPTED_IN)
            else -> Granted
        }
    }
}

/**
 * What an edit session is bound to for its whole life (ADR-0027): the profile
 * it was started under, the access identity that profile resolved to, the
 * canonical namespace, and the configuration coordinate.
 *
 * It deliberately carries **no credential and no operation context**. Publishing
 * derives its target from this binding and captures fresh operation credentials
 * when it runs, so a draft can neither publish under a secret captured minutes
 * ago nor be retargeted by whatever the tool window happens to show now.
 */
data class EditBinding(
    val profileId: String,
    val identity: AccessIdentity,
    val namespaceId: String,
    val dataId: String,
    val group: String
) {
    val coordinate: ConfigurationCoordinate get() = ConfigurationCoordinate(dataId, group)

    /** True when this binding names the configuration at [namespaceId]/[dataId]/[group]. */
    fun names(namespaceId: String?, dataId: String?, group: String?): Boolean =
        dataId != null && group != null &&
            this.namespaceId == NamespaceInfo.canonicalId(namespaceId) &&
            this.dataId == dataId &&
            this.group == group

    companion object {
        fun of(
            profileId: String,
            identity: AccessIdentity,
            namespaceId: String?,
            dataId: String,
            group: String
        ): EditBinding = EditBinding(
            profileId = profileId,
            identity = identity,
            namespaceId = NamespaceInfo.canonicalId(namespaceId),
            dataId = dataId,
            group = group
        )
    }
}

/**
 * An in-memory draft of one configuration, bound to [binding] and to the
 * baseline it was started from. It is held by a project-level service and never
 * by a panel: a session that lives inside the tool-window content cannot block
 * that content from being destroyed (ADR-0046).
 *
 * P0 provides no draft shelf — this is never persisted anywhere.
 */
data class EditSession(
    val binding: EditBinding,
    val baselineContent: String,
    val baselineMd5: String?,
    val baselineType: String?,
    val baselineAppName: String?,
    val baselineDesc: String?,
    val baselineConfigTags: String?,
    val draftContent: String,
    /** Per-profile publish opt-in (ADR-0026), decided once when the edit started. */
    val writeIntent: WriteIntent
) {
    val isDirty: Boolean get() = draftContent != baselineContent

    fun toCommand(remoteDetail: NacosConfiguration): PublishCommand = PublishCommand(
        dataId = binding.dataId,
        group = binding.group,
        content = draftContent,
        type = remoteDetail.type ?: baselineType ?: "text",
        namespaceId = binding.namespaceId,
        appName = remoteDetail.appName ?: baselineAppName,
        desc = remoteDetail.desc ?: baselineDesc,
        configTags = remoteDetail.configTags ?: baselineConfigTags,
        casMd5 = baselineMd5 ?: remoteDetail.md5,
        encryptedDataKey = remoteDetail.encryptedDataKey
    )
}
