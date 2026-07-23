package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * The immutable command derived from preflight: replaces content,
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
}

/** The adapter-level result of a publish attempt. */
sealed class PublishOutcome {
    /** V1: server confirmed the CAS write succeeded. V3: server accepted the POST. */
    data class Written(val body: String) : PublishOutcome()
    /** V1 CAS returned false: a concurrent update was detected. */
    data object CasConflict : PublishOutcome()
}

/** State of a controlled publishing session. */
sealed class PublishState {
    data class ReadOnly(val reason: String) : PublishState()
    data object Dirty : PublishState()
    data object Preflight : PublishState()
    data object TargetDeleted : PublishState()
    data class RemoteConflict(val remoteContent: String, val remoteMd5: String?) : PublishState()
    data object AwaitingConfirmation : PublishState()
    data object Publishing : PublishState()
    data object Verifying : PublishState()
    data object Verified : PublishState()
    data object PermissionDenied : PublishState()
    data object ServerStateUnknown : PublishState()
}

/**
 * An edit session bound to its original access identity, namespace,
 * configuration coordinate, baseline content, baseline MD5, and known
 * metadata. The publish target can only be derived from this binding;
 * it never reads current UI selection.
 */
data class EditSession(
    val target: OperationTarget,
    val dataId: String,
    val group: String,
    val namespaceId: String,
    val baselineContent: String,
    val baselineMd5: String?,
    val baselineType: String?,
    val baselineAppName: String?,
    val baselineDesc: String?,
    val baselineConfigTags: String?,
    val draftContent: String,
    /** Per-profile publish opt-in (ADR 0026). Must be true for any write. */
    val writesEnabled: Boolean = false
) {
    val isDirty: Boolean get() = draftContent != baselineContent

    fun toCommand(remoteDetail: NacosConfiguration): PublishCommand = PublishCommand(
        dataId = dataId,
        group = group,
        content = draftContent,
        type = remoteDetail.type ?: baselineType ?: "text",
        namespaceId = namespaceId,
        appName = remoteDetail.appName ?: baselineAppName,
        desc = remoteDetail.desc ?: baselineDesc,
        configTags = remoteDetail.configTags ?: baselineConfigTags,
        casMd5 = baselineMd5 ?: remoteDetail.md5,
        encryptedDataKey = remoteDetail.encryptedDataKey
    )
}
