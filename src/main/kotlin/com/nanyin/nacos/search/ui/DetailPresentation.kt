package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.EditStart
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.PublishResult
import com.nanyin.nacos.search.services.operations.PublishState
import com.nanyin.nacos.search.services.operations.WriteIntent
import com.nanyin.nacos.search.settings.ConfigurationRequired

/**
 * Maps load, edit-start, and publish outcomes to the detail view's closed
 * [DetailViewState] (issue #81).
 *
 * Lives outside [ConfigDetailPanel]: the panel renders exhaustively and makes
 * no decision. Classification is typed — never by localised exception text —
 * so a permission problem or a write-intent refusal cannot collapse into a
 * generic failure catch-all.
 *
 * Pure: no Swing, no services, no bundle resolution. Resolved wording is
 * produced later by [DetailCopy] at render time (and on language change).
 */
object DetailPresentation {

    const val LOAD_FAILED_TITLE_KEY = "config.detail.failed"
    const val SAVE_FAILED_TITLE_KEY = "error.config.save.failed"

    fun body(
        configuration: NacosConfiguration,
        confidence: CacheConfidence? = null,
        overlay: DetailOverlay = DetailOverlay.None,
        dirty: Boolean = false,
        editing: Boolean = false,
        observation: Long = Observed.NO_OBSERVATION
    ): DetailViewState.Body = DetailViewState.Body(
        configuration = configuration,
        confidence = confidence,
        overlay = overlay,
        dirty = dirty,
        editing = editing,
        observation = observation
    )

    /**
     * Confidence for a body shown from the cache snapshot (ADR-0036). Source is
     * always CACHE; age follows [CacheService.DetailFreshness]; confirmation is
     * UNCONFIRMED until a remote read proves otherwise.
     */
    fun confidenceFromCache(
        freshness: CacheService.DetailFreshness,
        fetchedAtMillis: Long? = null
    ): CacheConfidence = CacheConfidence(
        source = DataSource.CACHE,
        confirmation = DatasetConfirmation.UNCONFIRMED,
        age = when (freshness) {
            CacheService.DetailFreshness.FRESH -> CacheAge.WITHIN_TTL
            CacheService.DetailFreshness.STALE -> CacheAge.STALE
            CacheService.DetailFreshness.DEEP_STALE -> CacheAge.DEEP_STALE
        },
        completeness = DatasetCompleteness.COMPLETE,
        fetchedAtMillis = fetchedAtMillis
    )

    /** Confidence for a body just confirmed by a remote read. */
    fun confidenceFromRemote(nowMillis: Long): CacheConfidence =
        CacheConfidence.remoteConfirmed(nowMillis, DatasetCompleteness.COMPLETE)

    fun fromEditStart(start: EditStart): DetailViewState = when (start) {
        is EditStart.WritesWithheld ->
            DetailViewState.WriteIntentDisabled(start.cause)
        is EditStart.Unavailable ->
            fromFailure(start.error, start.error.message ?: "Unavailable")
        is EditStart.Started ->
            body(
                configuration = NacosConfiguration(
                    dataId = start.session.binding.dataId,
                    group = start.session.binding.group,
                    tenantId = start.session.binding.namespaceId,
                    content = start.session.draftContent,
                    type = start.session.baselineType,
                    md5 = start.session.baselineMd5
                ),
                dirty = start.session.isDirty,
                editing = true
            )
    }

    /**
     * Maps a live [PublishState] (including in-flight phases) into the closed
     * set. Exhaustive — there is no catch-all.
     *
     * @param draftContent required when [state] is a conflict so the view can
     * show remote-vs-draft without re-reading the session.
     * @param verifiedDetail carried on [DetailViewState.Verified] when known.
     */
    fun fromPublishState(
        state: PublishState,
        draftContent: String = "",
        verifiedDetail: NacosConfiguration? = null
    ): DetailViewState = when (state) {
        is PublishState.AwaitingConfirmation ->
            DetailViewState.AwaitingConfirmation(state.diff, state.namedTarget)
        is PublishState.Publishing ->
            DetailViewState.Publishing
        is PublishState.Verifying ->
            DetailViewState.Verifying
        is PublishState.Verified ->
            DetailViewState.Verified(verifiedDetail)
        is PublishState.RemoteConflict ->
            DetailViewState.Conflict(state.remoteContent, draftContent)
        is PublishState.ServerStateUnknown ->
            DetailViewState.ServerStateUnknown
        is PublishState.TargetDeleted ->
            DetailViewState.Failed(
                detailKey = "config.detail.publish.deleted",
                asErrorCard = false
            )
        is PublishState.PermissionDenied ->
            DetailViewState.Failed(
                detailKey = "config.detail.publish.permission",
                asErrorCard = false
            )
        is PublishState.ReadOnly ->
            DetailViewState.Failed(
                detailKey = "config.detail.publish.readonly",
                detailParams = listOf(state.reason),
                asErrorCard = false
            )
        is PublishState.Dirty ->
            DetailViewState.Failed(
                detailKey = "config.detail.publish.not.verified",
                asErrorCard = false
            )
        is PublishState.Preflight ->
            DetailViewState.Loading
    }

    fun fromPublishResult(result: PublishResult, draftContent: String): DetailViewState =
        fromPublishState(result.state, draftContent, result.verifiedDetail)

    /**
     * Classifies a typed load/edit failure into
     * [DetailViewState.ConfigurationRequired] or [DetailViewState.Failed].
     */
    fun fromFailure(
        error: Throwable?,
        fallbackMessage: String,
        titleKey: String? = LOAD_FAILED_TITLE_KEY
    ): DetailViewState {
        val root = FailureCopy.unwrap(error)
        return when {
            root is ConfigurationRequired -> DetailViewState.ConfigurationRequired(
                detail = root.reasons.firstOrNull()?.takeIf { it.isNotBlank() }
            )
            else -> DetailViewState.Failed(
                titleKey = titleKey,
                detail = failureDetail(root, fallbackMessage)
            )
        }
    }

    fun fromAuthoritativeNotFound(
        cached: NacosConfiguration,
        confidence: CacheConfidence?
    ): DetailViewState.Body = body(
        configuration = cached,
        confidence = confidence?.copy(confirmation = DatasetConfirmation.REFRESH_FAILED)
            ?: CacheConfidence.refreshFailed(
                completeness = DatasetCompleteness.COMPLETE,
                age = CacheAge.DEEP_STALE,
                fetchedAt = null
            ),
        overlay = DetailOverlay.Deleted
    )

    fun fromRefreshFailure(
        cached: NacosConfiguration,
        confidence: CacheConfidence?
    ): DetailViewState.Body = body(
        configuration = cached,
        confidence = confidence?.copy(confirmation = DatasetConfirmation.REFRESH_FAILED)
            ?: CacheConfidence.refreshFailed(
                completeness = DatasetCompleteness.COMPLETE,
                age = CacheAge.DEEP_STALE,
                fetchedAt = null
            ),
        overlay = DetailOverlay.RefreshFailed
    )

    internal fun failureDetail(error: Throwable?, fallback: String): String {
        return when {
            error != null && !error.message.isNullOrBlank() -> error.message!!.trim()
            else -> fallback.trim()
        }
    }
}

/**
 * Resolves [DetailViewState] copy for display. Pure given a message resolver —
 * panel and tests share one path (issue #81).
 */
object DetailCopy {

    fun overlayMessage(
        overlay: DetailOverlay,
        message: (key: String, params: Array<out Any>) -> String
    ): String? = when (overlay) {
        DetailOverlay.None -> null
        DetailOverlay.Refreshing -> message("config.detail.cache.refreshing", emptyArray())
        DetailOverlay.RefreshFailed -> message("config.detail.cache.refresh.failed", emptyArray())
        DetailOverlay.Deleted -> message("config.detail.cache.deleted", emptyArray())
    }

    fun writeIntentMessage(
        cause: WriteIntent.Cause,
        message: (key: String, params: Array<out Any>) -> String
    ): String = when (cause) {
        WriteIntent.Cause.NO_PROFILE_SELECTED ->
            message("config.detail.publish.no.profile", emptyArray())
        WriteIntent.Cause.NOT_OPTED_IN ->
            message("config.detail.publish.writes.disabled", emptyArray())
    }

    fun failedBody(
        state: DetailViewState.Failed,
        message: (key: String, params: Array<out Any>) -> String
    ): String {
        val title = state.titleKey?.let { message(it, emptyArray()) }.orEmpty()
        val structured = state.detailKey?.let {
            if (state.detailParams.isEmpty()) message(it, emptyArray())
            else message(it, state.detailParams.toTypedArray())
        }.orEmpty()
        val detail = structured.ifEmpty { state.detail.orEmpty() }
        return FailureCopy.combine(
            title,
            detail,
            message(DetailPresentation.LOAD_FAILED_TITLE_KEY, emptyArray())
        )
    }
}
