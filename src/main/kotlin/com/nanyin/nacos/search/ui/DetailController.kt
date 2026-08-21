package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.ConfigurationDetailConfirmation
import com.nanyin.nacos.search.services.operations.DetailReadResult
import com.nanyin.nacos.search.services.operations.Observed

/**
 * Maps operation-layer 配置详情 confirmation into the closed [DetailViewState]
 * set and applies 展示门禁 (issue #81 / #235). Capture, target resolution, the
 * coordinate read, and not-found recording live in
 * [ConfigurationDetailConfirmation].
 */
class DetailController(
    private val presentation: PresentationGate,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class SelectionPlan(
        val immediate: DetailViewState.Body?,
        val shouldLoad: Boolean,
        val forceRefresh: Boolean,
        val keepCachedVisible: Boolean
    )

    fun planSelection(cached: CacheService.CachedConfiguration?): SelectionPlan {
        val plan = ConfigurationDetailConfirmation.plan(cached)
        val immediate = plan.cached?.let { retained ->
            val confidence = DetailPresentation.confidenceFromCache(retained.freshness)
            val overlay = when (retained.freshness) {
                CacheService.DetailFreshness.FRESH -> DetailOverlay.None
                CacheService.DetailFreshness.STALE,
                CacheService.DetailFreshness.DEEP_STALE -> DetailOverlay.Refreshing
            }
            DetailPresentation.body(retained.configuration, confidence, overlay)
        }
        return SelectionPlan(
            immediate = immediate,
            shouldLoad = plan.shouldConfirm,
            forceRefresh = plan.forceRefresh,
            keepCachedVisible = plan.keepCachedVisible
        )
    }

    fun present(
        result: DetailReadResult,
        issued: PresentedResult,
        retainedConfidence: CacheConfidence? = null
    ): DetailViewState {
        val offered = issued.copy(observation = result.observation)
        if (!presentation.admitAndRecord(offered)) {
            return DetailViewState.Stale
        }
        return when (result) {
            is DetailReadResult.Present -> DetailPresentation.body(
                configuration = result.configuration,
                confidence = if (result.servedFromCache) {
                    retainedConfidence
                        ?: DetailPresentation.confidenceFromCache(CacheService.DetailFreshness.FRESH)
                } else {
                    DetailPresentation.confidenceFromRemote(clock())
                },
                observation = result.observation
            )
            is DetailReadResult.AuthoritativeNotFound ->
                DetailPresentation.fromAuthoritativeNotFound(
                    result.retained.configuration,
                    DetailPresentation.confidenceFromCache(result.retained.freshness)
                )
            is DetailReadResult.RefreshFailed ->
                DetailPresentation.fromRefreshFailure(
                    result.retained.configuration,
                    DetailPresentation.confidenceFromCache(result.retained.freshness)
                )
            is DetailReadResult.Failed -> DetailPresentation.fromFailure(
                error = result.error,
                fallbackMessage = result.fallbackMessage
            )
            is DetailReadResult.ConfigurationIncomplete -> {
                val required = result.reasons.firstOrNull()?.takeIf { it.isNotBlank() }
                DetailViewState.ConfigurationRequired(required)
            }
        }
    }
}
