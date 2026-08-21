package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * One 配置坐标 read for confirmation, gutter background refresh, and click
 * navigation (issues #235 / #236).
 *
 * Capture, target resolution (including AUTO → locked 访问身份), the gateway
 * read, and key-index publication live here. Background TTL bounding and
 * click join-in-flight are the two trigger policies; they share the flight.
 */
class ConfigurationCoordinateRead(
    private val gateway: OperationGateway,
    private val captureContext: suspend (profileId: String) -> NacosOperationContext?,
    private val resolveTarget: suspend (NacosOperationContext, String) -> Result<OperationTarget>,
    private val lookupCached: suspend (
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        allowStale: Boolean
    ) -> NacosConfiguration? = { _, _, _, _, _ -> null },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: () -> Long = { 0L },
    private val scope: CoroutineScope? = null,
    private val onRemoteFetched: (AccessIdentity) -> Unit = {}
) {
    private val flights = ConcurrentHashMap<FlightKey, Deferred<CoordinateReadResult>>()
    private val lastAttemptAt = ConcurrentHashMap<FlightKey, Long>()

    suspend fun readRemote(
        namespaceId: String,
        coordinate: ConfigurationCoordinate,
        forceRefresh: Boolean = false,
        useCache: Boolean = true,
        profileId: String = ""
    ): Result<RemoteCoordinateRead> {
        val context = captureContext(profileId)
            ?: return Result.failure(ConfigurationRequired(emptyList()))
        val target = resolveTarget(context, namespaceId).getOrElse { return Result.failure(it) }
        val observed = gateway.readDetail(
            target = target,
            coordinate = coordinate,
            forceRefresh = forceRefresh,
            useCache = useCache
        ).getOrElse { return Result.failure(it) }
        return Result.success(RemoteCoordinateRead(target, observed))
    }

    suspend fun read(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        allowStaleCache: Boolean,
        forceRefresh: Boolean = false
    ): CoordinateReadResult {
        val cached = lookupCached(identity, namespaceId, dataId, group, allowStaleCache)
        if (cached != null && cached.content.isNotBlank()) {
            return CoordinateReadResult.Hit(cached, identity)
        }
        val remoteNs = namespaceId?.takeIf { it.isNotBlank() } ?: "public"
        val remote = readRemote(
            namespaceId = remoteNs,
            coordinate = ConfigurationCoordinate(dataId, group),
            forceRefresh = forceRefresh,
            useCache = true,
            profileId = identity.profileId
        )
        return remote.fold(
            onSuccess = { read ->
                val body = read.observed.value
                val resolved = read.target.context.identity
                when {
                    body == null || body.content.isBlank() -> CoordinateReadResult.Missing(resolved)
                    else -> CoordinateReadResult.Fetched(body, resolved, read.observed.observation)
                }
            },
            onFailure = { error ->
                if (error is ConfigurationRequired) CoordinateReadResult.Incomplete(error.reasons)
                else CoordinateReadResult.Failed(error, identity)
            }
        )
    }

    /**
     * One attempt per coordinate per TTL. Concurrent callers after the claim
     * receive null unless they are joining a click that has not claimed the
     * window yet.
     */
    fun background(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        publish: (AccessIdentity) -> Unit = onRemoteFetched,
        prepare: (suspend () -> PreparedCoordinate)? = null
    ): Deferred<CoordinateReadResult>? {
        val workScope = scope ?: return null
        val key = FlightKey(identity, namespaceId.orEmpty(), dataId, group)
        val now = nowMillis()
        val inflight = flights[key]?.takeIf { it.isActive }
        if (inflight != null) {
            val previous = lastAttemptAt[key]
            if (previous != null && now - previous < ttlMillis()) return null
            lastAttemptAt[key] = now
            return inflight
        }
        val previous = lastAttemptAt[key]
        if (previous != null && now - previous < ttlMillis()) return null
        if (lastAttemptAt.put(key, now) != previous) return null
        return startFlight(workScope, key, allowStaleCache = false, publish = publish, prepare = prepare)
    }

    /**
     * Joins an equivalent in-flight read when one exists; never discarded by
     * the background TTL window.
     */
    fun navigation(
        identity: AccessIdentity,
        namespaceId: String?,
        dataId: String,
        group: String,
        publish: (AccessIdentity) -> Unit = onRemoteFetched
    ): Deferred<CoordinateReadResult> {
        val workScope = requireNotNull(scope) { "navigation requires a CoroutineScope" }
        val key = FlightKey(identity, namespaceId.orEmpty(), dataId, group)
        flights[key]?.takeIf { it.isActive }?.let { return it }
        return startFlight(workScope, key, allowStaleCache = true, publish = publish, prepare = null)
    }

    private fun startFlight(
        workScope: CoroutineScope,
        key: FlightKey,
        allowStaleCache: Boolean,
        publish: (AccessIdentity) -> Unit,
        prepare: (suspend () -> PreparedCoordinate)?
    ): Deferred<CoordinateReadResult> {
        val placeholder = CompletableDeferred<CoordinateReadResult>()
        val existing = flights.putIfAbsent(key, placeholder)
        if (existing != null) {
            return existing
        }
        workScope.async {
            try {
                val coord = prepare?.invoke() ?: PreparedCoordinate(
                    dataId = key.dataId,
                    group = key.group,
                    namespaceId = key.namespaceId.ifEmpty { null }
                )
                val result = read(
                    identity = key.identity,
                    namespaceId = coord.namespaceId,
                    dataId = coord.dataId,
                    group = coord.group,
                    allowStaleCache = allowStaleCache
                )
                if (result is CoordinateReadResult.Fetched) publish(result.identity)
                placeholder.complete(result)
            } catch (e: Exception) {
                placeholder.complete(CoordinateReadResult.Failed(e, key.identity))
            } finally {
                if (!placeholder.isCompleted) placeholder.cancel()
                flights.remove(key, placeholder)
            }
        }
        return placeholder
    }

    fun clearAttempts() {
        lastAttemptAt.clear()
        flights.values.forEach { it.cancel() }
        flights.clear()
    }

    private data class FlightKey(
        val identity: AccessIdentity,
        val namespaceId: String,
        val dataId: String,
        val group: String
    )
}

data class PreparedCoordinate(
    val dataId: String,
    val group: String,
    val namespaceId: String?
)

data class RemoteCoordinateRead(
    val target: OperationTarget,
    val observed: Observed<NacosConfiguration?>
)

sealed class CoordinateReadResult {
    data class Hit(
        val configuration: NacosConfiguration,
        val identity: AccessIdentity
    ) : CoordinateReadResult()

    data class Fetched(
        val configuration: NacosConfiguration,
        val identity: AccessIdentity,
        val observation: Long
    ) : CoordinateReadResult()

    data class Missing(
        val identity: AccessIdentity? = null
    ) : CoordinateReadResult()

    data class Failed(
        val error: Throwable,
        val identity: AccessIdentity? = null
    ) : CoordinateReadResult()

    data class Incomplete(
        val reasons: List<String> = emptyList()
    ) : CoordinateReadResult()

    fun body(): NacosConfiguration? = when (this) {
        is Hit -> configuration
        is Fetched -> configuration
        else -> null
    }
}
