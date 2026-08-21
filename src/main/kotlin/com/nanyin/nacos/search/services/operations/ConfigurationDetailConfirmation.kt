package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext

/**
 * Cache consultation, context capture, target resolution, coordinate read, and
 * authoritative not-found recording for 配置详情 confirmation (issue #235).
 *
 * Returns a typed [DetailReadResult]; 展示门禁 and rendering stay in the view.
 */
class ConfigurationDetailConfirmation(
    private val gateway: OperationGateway,
    private val captureContext: suspend () -> NacosOperationContext?,
    private val resolveTarget: suspend (NacosOperationContext, String) -> Result<OperationTarget>,
    private val recordMissing: (suspend (
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        observation: Long
    ) -> Unit)? = null,
    private val lookupCached: (
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String
    ) -> CacheService.CachedConfiguration? = { _, _, _, _ -> null }
) {
    private val coordinateRead = ConfigurationCoordinateRead(
        gateway = gateway,
        captureContext = { captureContext() },
        resolveTarget = resolveTarget
    )

    fun consult(
        identity: AccessIdentity,
        cacheNamespaceId: String?,
        dataId: String,
        group: String
    ): DetailCachePlan = plan(lookupCached(identity, cacheNamespaceId, dataId, group))

    suspend fun confirm(
        namespaceId: String,
        coordinate: ConfigurationCoordinate,
        forceRefresh: Boolean = false,
        useCache: Boolean = true,
        keepCachedVisible: Boolean = false,
        retained: CacheService.CachedConfiguration? = null
    ): DetailReadResult {
        val remote = coordinateRead.readRemote(
            namespaceId = namespaceId,
            coordinate = coordinate,
            forceRefresh = forceRefresh,
            useCache = useCache
        )
        return remote.fold(
            onSuccess = { read ->
                val detail = read.observed.value
                when {
                    detail != null -> DetailReadResult.Present(
                        configuration = detail,
                        observation = read.observed.observation,
                        servedFromCache = read.observed.observation == Observed.NO_OBSERVATION
                    )
                    keepCachedVisible && retained != null -> {
                        recordMissing?.invoke(
                            read.target.context.identity,
                            read.target.namespaceId,
                            coordinate.dataId,
                            coordinate.group,
                            read.observed.observation
                        )
                        DetailReadResult.AuthoritativeNotFound(
                            retained = retained,
                            observation = read.observed.observation
                        )
                    }
                    else -> DetailReadResult.Failed(
                        error = null,
                        fallbackMessage = "Configuration not found",
                        observation = read.observed.observation
                    )
                }
            },
            onFailure = { error ->
                if (error is ConfigurationRequired && !keepCachedVisible) {
                    DetailReadResult.ConfigurationIncomplete(error.reasons)
                } else {
                    retainedFailure(error, keepCachedVisible, retained)
                }
            }
        )
    }

    /**
     * Capture and resolve a target for a sibling read (history) so the detail
     * panel never holds a credential-capturing lambda of its own.
     */
    suspend fun targetFor(namespaceId: String): Result<OperationTarget> {
        val context = captureContext()
            ?: return Result.failure(ConfigurationRequired(listOf("Select a Nacos environment profile")))
        return resolveTarget(context, namespaceId)
    }

    private fun retainedFailure(
        error: Throwable,
        keepCachedVisible: Boolean,
        retained: CacheService.CachedConfiguration?
    ): DetailReadResult =
        if (keepCachedVisible && retained != null) {
            DetailReadResult.RefreshFailed(retained = retained, error = error)
        } else {
            DetailReadResult.Failed(
                error = error,
                fallbackMessage = error.message ?: "Failed to load configuration"
            )
        }

    companion object {
        fun plan(cached: CacheService.CachedConfiguration?): DetailCachePlan {
            if (cached == null) {
                return DetailCachePlan(
                    cached = null,
                    shouldConfirm = true,
                    forceRefresh = false,
                    keepCachedVisible = false
                )
            }
            return when (cached.freshness) {
                CacheService.DetailFreshness.FRESH -> DetailCachePlan(
                    cached = cached,
                    shouldConfirm = true,
                    forceRefresh = false,
                    keepCachedVisible = true
                )
                CacheService.DetailFreshness.STALE -> DetailCachePlan(
                    cached = cached,
                    shouldConfirm = true,
                    forceRefresh = false,
                    keepCachedVisible = true
                )
                CacheService.DetailFreshness.DEEP_STALE -> DetailCachePlan(
                    cached = cached,
                    shouldConfirm = true,
                    forceRefresh = true,
                    keepCachedVisible = true
                )
            }
        }
    }
}

data class DetailCachePlan(
    val cached: CacheService.CachedConfiguration?,
    val shouldConfirm: Boolean,
    val forceRefresh: Boolean,
    val keepCachedVisible: Boolean
)

sealed class DetailReadResult {
    open val observation: Long = Observed.NO_OBSERVATION

    data class Present(
        val configuration: NacosConfiguration,
        override val observation: Long,
        val servedFromCache: Boolean
    ) : DetailReadResult()

    data class AuthoritativeNotFound(
        val retained: CacheService.CachedConfiguration,
        override val observation: Long
    ) : DetailReadResult()

    data class RefreshFailed(
        val retained: CacheService.CachedConfiguration,
        val error: Throwable,
        override val observation: Long = Observed.NO_OBSERVATION
    ) : DetailReadResult()

    data class Failed(
        val error: Throwable?,
        val fallbackMessage: String,
        override val observation: Long = Observed.NO_OBSERVATION
    ) : DetailReadResult()

    data class ConfigurationIncomplete(
        val reasons: List<String> = emptyList(),
        override val observation: Long = Observed.NO_OBSERVATION
    ) : DetailReadResult()
}
