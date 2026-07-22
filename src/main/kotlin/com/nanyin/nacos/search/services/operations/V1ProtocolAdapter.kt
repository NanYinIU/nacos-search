package com.nanyin.nacos.search.services.operations

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosOperationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Immutable target for one remote operation. Namespace values may be entered manually. */
data class OperationTarget(
    val context: NacosOperationContext,
    val namespaceId: String = "public"
)

data class SummaryQuery(
    val pageNo: Int = 1,
    val pageSize: Int = 200,
    val dataId: String = "",
    val group: String = "",
    val appName: String = "",
    val configTags: String = "",
    val search: String = "accurate"
) {
    fun cacheKey(): String = listOf(
        "pageNo=$pageNo",
        "pageSize=$pageSize",
        "dataId=$dataId",
        "group=$group",
        "appName=$appName",
        "config_tags=$configTags",
        "search=$search"
    ).joinToString("|")
}

data class ConfigurationCoordinate(val dataId: String, val group: String)

data class ConfigurationSummary(
    val dataId: String,
    val group: String,
    val tenantId: String?,
    val content: String?,
    val type: String?
)

data class SummaryPage(
    val totalCount: Int,
    val pageNumber: Int,
    val pagesAvailable: Int,
    val items: List<ConfigurationSummary>
)

data class ProtocolRequest(
    val method: String,
    val endpoint: String,
    val path: String,
    val query: List<Pair<String, String>>,
    val headers: Map<String, String>
) {
    val url: String
        get() = if (query.isEmpty()) "$endpoint$path" else "$endpoint$path?" + query.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
}

data class ProtocolResponse(val status: Int, val body: String)

fun interface ProtocolTransport {
    suspend fun execute(request: ProtocolRequest): ProtocolResponse
}

/** Generation-neutral protocol contract. The gateway decides which adapter receives a captured target. */
interface ProtocolAdapter {
    suspend fun probe(target: OperationTarget): Result<Unit>
    suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage>
    suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?>
}

sealed class RemoteOperationError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Authentication(val status: Int) : RemoteOperationError("Authentication failed")
    class NotFound : RemoteOperationError("Configuration was not found")
    class Client(val status: Int) : RemoteOperationError("Client error $status")
    class Server(val status: Int) : RemoteOperationError("Server error $status")
    class RateLimited : RemoteOperationError("Rate limited")
    class Connection(cause: Throwable) : RemoteOperationError("Connection failed", cause)
    class Protocol(message: String, cause: Throwable? = null) : RemoteOperationError(message, cause)
    class Unsupported(message: String) : RemoteOperationError(message)
}

/**
 * Owns every V1 wire decision for the anonymous read path: method, path,
 * query, public-namespace encoding, headers, parsing, and error mapping.
 */
class V1ProtocolAdapter(
    private val transport: ProtocolTransport,
    private val gson: Gson = Gson()
) : ProtocolAdapter {

    override suspend fun probe(target: OperationTarget): Result<Unit> = execute(target) {
        request(target, NAMESPACES_PATH)
    }.mapCatching { response ->
        ensureSuccess(response)
        Unit
    }

    override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
        execute(target) {
            request(
                target,
                CONFIGS_PATH,
                listOf(
                    "pageNo" to query.pageNo.toString(),
                    "pageSize" to query.pageSize.toString(),
                    "dataId" to query.dataId,
                    "group" to query.group,
                    "appName" to query.appName,
                    "config_tags" to query.configTags,
                    "search" to query.search
                )
            )
        }.mapCatching { response ->
            parseSummaryPage(ensureSuccess(response))
        }

    override suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> = execute(target) {
        request(
            target,
            CONFIGS_PATH,
            listOf(
                "dataId" to coordinate.dataId,
                "group" to coordinate.group,
                "show" to "all"
            )
        )
    }.mapCatching { response ->
        if (response.status == 404) return@mapCatching null
        parseDetail(ensureSuccess(response))
    }

    private suspend fun execute(
        target: OperationTarget,
        build: () -> ProtocolRequest
    ): Result<ProtocolResponse> = runCatching {
        validate(target)
        try {
            transport.execute(build())
        } catch (error: Throwable) {
            throw mapFailure(error)
        }
    }

    private fun request(
        target: OperationTarget,
        path: String,
        query: List<Pair<String, String>> = emptyList()
    ): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = path,
        query = query + listOfNotNull(v1TenantQuery(target.namespaceId)),
        headers = mapOf("Accept" to "application/json")
    )

    /** V1 alone decides how its public Namespace is represented on the wire. */
    private fun v1TenantQuery(namespaceId: String): Pair<String, String>? =
        namespaceId.trim()
            .takeUnless { it.isEmpty() || it == "public" }
            ?.let { "tenant" to it }

    private fun validate(target: OperationTarget) {
        require(target.context.resolvedGeneration == NacosApiGeneration.V1) {
            throw RemoteOperationError.Unsupported("V1 adapter requires a V1-locked target")
        }
        require(target.context.authMode == AuthMode.ANONYMOUS) {
            throw RemoteOperationError.Unsupported("V1 anonymous adapter requires anonymous authentication")
        }
        require(target.context.identity.principal == "<anonymous>" && target.context.credential.secret.isBlank()) {
            throw RemoteOperationError.Unsupported("Anonymous target must not carry credentials")
        }
    }

    private fun ensureSuccess(response: ProtocolResponse): String = when (response.status) {
        in 200..299 -> response.body
        401, 403 -> throw RemoteOperationError.Authentication(response.status)
        404 -> throw RemoteOperationError.NotFound()
        429 -> throw RemoteOperationError.RateLimited()
        in 400..499 -> throw RemoteOperationError.Client(response.status)
        in 500..599 -> throw RemoteOperationError.Server(response.status)
        else -> throw RemoteOperationError.Protocol("Unexpected HTTP status ${response.status}")
    }

    private fun parseSummaryPage(body: String): SummaryPage = try {
        val raw = gson.fromJson(body, V1ConfigListEnvelope::class.java)
            ?: throw RemoteOperationError.Protocol("V1 summary response was empty")
        SummaryPage(
            totalCount = raw.totalCount,
            pageNumber = raw.pageNumber,
            pagesAvailable = raw.pagesAvailable,
            items = raw.pageItems.map { item ->
                ConfigurationSummary(item.dataId, item.group, normalizeTenant(item.tenant), item.content, item.type)
            }
        )
    } catch (error: RemoteOperationError) {
        throw error
    } catch (error: JsonParseException) {
        throw RemoteOperationError.Protocol("Invalid V1 summary response", error)
    } catch (error: Exception) {
        throw RemoteOperationError.Protocol("Invalid V1 summary response", error)
    }

    private fun parseDetail(body: String): NacosConfiguration = try {
        val raw = gson.fromJson(body, V1ConfigDetailEnvelope::class.java)
            ?: throw RemoteOperationError.Protocol("V1 detail response was empty")
        if (raw.dataId.isBlank() || raw.group.isBlank()) {
            throw RemoteOperationError.Protocol("V1 detail response is missing its coordinate")
        }
        NacosConfiguration(
            dataId = raw.dataId,
            group = raw.group,
            tenantId = normalizeTenant(raw.tenant),
            content = raw.content ?: "",
            type = raw.type,
            md5 = raw.md5
        )
    } catch (error: RemoteOperationError) {
        throw error
    } catch (error: JsonParseException) {
        throw RemoteOperationError.Protocol("Invalid V1 detail response", error)
    } catch (error: Exception) {
        throw RemoteOperationError.Protocol("Invalid V1 detail response", error)
    }

    private fun mapFailure(error: Throwable): RemoteOperationError = when (error) {
        is RemoteOperationError -> error
        is NacosRequestError.Authentication -> RemoteOperationError.Authentication(error.status)
        is NacosRequestError.RateLimited -> RemoteOperationError.RateLimited()
        is NacosRequestError.Client -> if (error.status == 404) RemoteOperationError.NotFound() else RemoteOperationError.Client(error.status)
        is NacosRequestError.Server -> RemoteOperationError.Server(error.status)
        is NacosRequestError.Protocol -> RemoteOperationError.Protocol(error.message ?: "Protocol failure", error)
        is NacosRequestError -> RemoteOperationError.Connection(error)
        else -> RemoteOperationError.Connection(error)
    }

    private fun normalizeTenant(tenant: String?): String? = tenant?.takeUnless { it.isBlank() || it == "public" }

    private data class V1ConfigListEnvelope(
        val totalCount: Int = 0,
        val pageNumber: Int = 0,
        val pagesAvailable: Int = 0,
        val pageItems: List<V1ConfigSummaryEnvelope> = emptyList()
    )

    private data class V1ConfigSummaryEnvelope(
        val dataId: String = "",
        val group: String = "",
        val content: String? = null,
        val type: String? = null,
        val tenant: String? = null
    )

    private data class V1ConfigDetailEnvelope(
        val dataId: String = "",
        val group: String = "",
        val tenant: String? = null,
        val content: String? = null,
        val type: String? = null,
        val md5: String? = null
    )

    private companion object {
        const val CONFIGS_PATH = "/nacos/v1/cs/configs"
        const val NAMESPACES_PATH = "/nacos/v1/console/namespaces"
    }
}
