package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.PublishResult
import com.nanyin.nacos.search.services.operations.PublishState

/**
 * Non-Swing controller for the configuration detail view (issue #81).
 *
 * Takes the [OperationGateway] — the same seam the history browser and publish
 * machine use — and maps gateway / edit / publish outcomes to the closed
 * [DetailViewState] set. Whether a completed read may still update the panel is
 * the same judgement every other view uses (ADR-0047).
 *
 * The dedicated [ConfigurationDetailLoader] test seam is gone: tests substitute
 * a protocol adapter beneath a real gateway.
 */
class DetailController(
    private val gateway: OperationGateway,
    private val presentation: PresentationGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAuthoritativeNotFound: (suspend (
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        observation: Long
    ) -> Unit)? = null
) {
    /**
     * How to present a newly selected configuration given what the cache already
     * knows. Pure relative to [cached]: the panel paints [immediate] (if any)
     * and only starts a remote load when [shouldLoad] is true.
     */
    data class SelectionPlan(
        val immediate: DetailViewState.Body?,
        val shouldLoad: Boolean,
        val forceRefresh: Boolean,
        val keepCachedVisible: Boolean
    )

    fun planSelection(cached: CacheService.CachedConfiguration?): SelectionPlan {
        if (cached == null) {
            return SelectionPlan(
                immediate = null,
                shouldLoad = true,
                forceRefresh = false,
                keepCachedVisible = false
            )
        }
        val confidence = DetailPresentation.confidenceFromCache(cached.freshness)
        return when (cached.freshness) {
            // Paint the fresh cache immediately. A full Loading card here made
            // first selection after prefetch flash (and could race) even though
            // the body was already local; confirm with a quiet background load.
            CacheService.DetailFreshness.FRESH -> SelectionPlan(
                immediate = DetailPresentation.body(cached.configuration, confidence),
                shouldLoad = true,
                forceRefresh = false,
                keepCachedVisible = true
            )
            // Past TTL: paint the cached body, then confirm this one coordinate.
            // Opening (or jumping to) a configuration is the ask to see it, and
            // a stale body silently shown is how an amber gutter icon turns blue
            // beside content nobody re-read. No forceRefresh: the gateway's
            // detail cache already treats a stale entry as a miss, so the read
            // goes remote for this coordinate alone and never re-pages the
            // Namespace.
            CacheService.DetailFreshness.STALE -> SelectionPlan(
                immediate = DetailPresentation.body(
                    cached.configuration,
                    confidence,
                    overlay = DetailOverlay.Refreshing
                ),
                shouldLoad = true,
                forceRefresh = false,
                keepCachedVisible = true
            )
            CacheService.DetailFreshness.DEEP_STALE -> SelectionPlan(
                immediate = DetailPresentation.body(
                    cached.configuration,
                    confidence,
                    overlay = DetailOverlay.Refreshing
                ),
                shouldLoad = true,
                forceRefresh = true,
                keepCachedVisible = true
            )
        }
    }

    /**
     * Loads one configuration detail through the gateway and maps the outcome
     * into the closed set. A result the presentation gate rejects becomes
     * [DetailViewState.Stale] rather than a silent drop.
     *
     * @param cachedBody when [keepCachedVisible], the body already shown so a
     * not-found or refresh failure can keep it with an overlay instead of
     * swapping to Failed.
     */
    suspend fun load(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate,
        sessionEpoch: Long,
        forceRefresh: Boolean = false,
        useCache: Boolean = true,
        keepCachedVisible: Boolean = false,
        cachedBody: DetailViewState.Body? = null
    ): DetailViewState {
        val result = gateway.readDetail(
            target = target,
            coordinate = coordinate,
            forceRefresh = forceRefresh,
            useCache = useCache
        )
        val presented = PresentedResult(
            sessionEpoch = sessionEpoch,
            coordinate = PresentedCoordinate.of(
                target.namespaceId,
                coordinate.dataId,
                coordinate.group
            ),
            observation = result.getOrNull()?.observation ?: Observed.NO_OBSERVATION
        )
        if (!presentation.admitAndRecord(presented)) {
            return DetailViewState.Stale
        }
        return result.fold(
            onSuccess = { observed ->
                val detail = observed.value
                when {
                    detail != null -> DetailPresentation.body(
                        configuration = detail,
                        confidence = if (observed.observation == Observed.NO_OBSERVATION) {
                            cachedBody?.confidence
                                ?: DetailPresentation.confidenceFromCache(
                                    CacheService.DetailFreshness.FRESH
                                )
                        } else {
                            DetailPresentation.confidenceFromRemote(clock())
                        },
                        observation = observed.observation
                    )
                    keepCachedVisible && cachedBody != null -> {
                        onAuthoritativeNotFound?.invoke(
                            target.context.identity,
                            target.namespaceId,
                            coordinate.dataId,
                            coordinate.group,
                            observed.observation
                        )
                        DetailPresentation.fromAuthoritativeNotFound(
                            cachedBody.configuration,
                            cachedBody.confidence
                        )
                    }
                    else -> DetailPresentation.fromFailure(
                        error = null,
                        fallbackMessage = "Configuration not found"
                    )
                }
            },
            onFailure = { error ->
                if (keepCachedVisible && cachedBody != null) {
                    DetailPresentation.fromRefreshFailure(
                        cachedBody.configuration,
                        cachedBody.confidence
                    )
                } else {
                    DetailPresentation.fromFailure(
                        error = error,
                        fallbackMessage = error.message ?: "Failed to load configuration"
                    )
                }
            }
        )
    }

    fun fromPublishState(
        state: PublishState,
        draftContent: String = "",
        verifiedDetail: NacosConfiguration? = null
    ): DetailViewState = DetailPresentation.fromPublishState(state, draftContent, verifiedDetail)

    fun fromPublishResult(result: PublishResult, draftContent: String): DetailViewState =
        DetailPresentation.fromPublishResult(result, draftContent)

    fun empty(): DetailViewState = DetailPresentation.empty()

    fun loading(): DetailViewState = DetailPresentation.loading()
}
