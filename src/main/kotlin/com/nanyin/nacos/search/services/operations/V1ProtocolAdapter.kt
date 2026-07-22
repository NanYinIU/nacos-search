package com.nanyin.nacos.search.services.operations

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
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
    class Authorization(val status: Int) : RemoteOperationError("Authorization failed")
    class InvalidOrExpiredNacosPasswordToken(val status: Int) :
        RemoteOperationError("Nacos password token is invalid or expired")
    class NotFound : RemoteOperationError("Configuration was not found")
    class Client(val status: Int) : RemoteOperationError("Client error $status")
    class Server(val status: Int) : RemoteOperationError("Server error $status")
    class RateLimited : RemoteOperationError("Rate limited")
    class Connection(cause: Throwable) : RemoteOperationError("Connection failed", cause)
    class Protocol(message: String, cause: Throwable? = null) : RemoteOperationError(message, cause)
    class Unsupported(message: String) : RemoteOperationError(message)
    class GenerationUnsupported(message: String) : RemoteOperationError(message)
    class CapabilityUnsupported(message: String) : RemoteOperationError(message)
    class Cancelled(message: String = "Operation cancelled") : RemoteOperationError(message)
    class Redirected(val sanitizedEndpoint: String) : RemoteOperationError("Endpoint redirected to $sanitizedEndpoint")
}

/** V1 obtains Nacos-password tokens from this application-memory boundary. */
interface V1Authenticator {
    suspend fun accessToken(context: NacosOperationContext): String?
    fun invalidate(context: NacosOperationContext)
}

private object UnavailableV1Authenticator : V1Authenticator {
    override suspend fun accessToken(context: NacosOperationContext): String? = null
    override fun invalidate(context: NacosOperationContext) = Unit
}

/**
 * Owns every V1 wire decision for the read path: method, path,
 * query, public-namespace encoding, headers, parsing, and error mapping.
 */
class V1ProtocolAdapter(
    private val transport: ProtocolTransport,
    private val gson: Gson = Gson()
) : ProtocolAdapter {
    private var authenticator: V1Authenticator = UnavailableV1Authenticator
    private var readBudgetMillis: Long = DEFAULT_READ_BUDGET_MILLIS
    private var clock: () -> Long = System::currentTimeMillis

    constructor(transport: ProtocolTransport, authenticator: V1Authenticator) : this(transport) {
        this.authenticator = authenticator
    }

    internal constructor(
        transport: ProtocolTransport,
        authenticator: V1Authenticator,
        readBudgetMillis: Long,
        clock: () -> Long,
        gson: Gson
    ) : this(transport, gson) {
        this.authenticator = authenticator
        this.readBudgetMillis = readBudgetMillis
        this.clock = clock
    }

    override suspend fun probe(target: OperationTarget): Result<Unit> = execute(target) {
        request(target, NAMESPACES_PATH, authentication = it)
    }.mapCatching { response ->
        ensureSuccess(response)
        Unit
    }

    override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
        execute(target) { authentication ->
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
                ),
                authentication
            )
        }.mapCatching { response ->
            parseSummaryPage(ensureSuccess(response))
        }

    override suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> = execute(target) { authentication ->
        request(
            target,
            CONFIGS_PATH,
            listOf(
                "dataId" to coordinate.dataId,
                "group" to coordinate.group,
                "show" to "all"
            ),
            authentication
        )
    }.mapCatching { response ->
        if (response.status == 404) return@mapCatching null
        parseDetail(ensureSuccess(response))
    }

    private suspend fun execute(
        target: OperationTarget,
        build: (RequestAuthentication) -> ProtocolRequest
    ): Result<ProtocolResponse> = runCatching {
        validate(target)
        try {
            val deadline = clock() + readBudgetMillis
            val firstResponse = executeWithinBudget(build(authenticationFor(target, deadline)), deadline)
            if (recoverableNacosPasswordTokenFailure(target, firstResponse) != null) {
                authenticator.invalidate(target.context)
                executeWithinBudget(build(authenticationFor(target, deadline)), deadline)
            } else {
                firstResponse
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw mapFailure(error)
        }
    }

    private fun request(
        target: OperationTarget,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        authentication: RequestAuthentication
    ): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = path,
        query = query + authentication.query + listOfNotNull(v1TenantQuery(target.namespaceId)),
        headers = mapOf("Accept" to "application/json") + authentication.headers
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
        when (target.context.authenticationStrategy) {
            V1AuthenticationStrategy.ANONYMOUS -> require(
                target.context.identity.principal == "<anonymous>" && target.context.credential.secret.isBlank()
            ) {
                throw RemoteOperationError.Unsupported("Anonymous target must not carry credentials")
            }
            V1AuthenticationStrategy.NACOS_PASSWORD,
            V1AuthenticationStrategy.HTTP_BASIC -> require(
                target.context.identity.principal != "<anonymous>" && target.context.credential.secret.isNotBlank()
            ) {
                throw RemoteOperationError.Unsupported("Password and principal are required for V1 authentication")
            }
            V1AuthenticationStrategy.BEARER_TOKEN -> require(target.context.credential.secret.isNotBlank()) {
                throw RemoteOperationError.Unsupported("Bearer token is required for V1 authentication")
            }
        }
    }

    private fun ensureSuccess(response: ProtocolResponse): String = when (response.status) {
        in 200..299 -> response.body
        401, 403 -> when (val failure = mapAuthenticationFailure(response)) {
            null -> throw RemoteOperationError.Authentication(response.status)
            else -> throw failure
        }
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

    private suspend fun authenticationFor(target: OperationTarget, deadline: Long): RequestAuthentication =
        when (target.context.authenticationStrategy) {
            V1AuthenticationStrategy.ANONYMOUS -> RequestAuthentication()
            V1AuthenticationStrategy.NACOS_PASSWORD -> {
                val token = withinBudget(deadline) { authenticator.accessToken(target.context) }
                    ?: throw RemoteOperationError.Authentication(401)
                RequestAuthentication(query = listOf("accessToken" to token))
            }
            V1AuthenticationStrategy.HTTP_BASIC -> {
                val credentials = "${target.context.identity.principal}:${target.context.credential.secret}"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
                RequestAuthentication(headers = mapOf("Authorization" to "Basic $encoded"))
            }
            V1AuthenticationStrategy.BEARER_TOKEN -> RequestAuthentication(
                headers = mapOf("Authorization" to "Bearer ${target.context.credential.secret}")
            )
        }

    private suspend fun executeWithinBudget(request: ProtocolRequest, deadline: Long): ProtocolResponse =
        withinBudget(deadline) { transport.execute(request) }

    private suspend fun <T> withinBudget(deadline: Long, operation: suspend () -> T): T {
        val remaining = deadline - clock()
        if (remaining <= 0) throw RemoteOperationError.Protocol("V1 read budget exhausted")
        return withTimeout(remaining) { operation() }
    }

    /**
     * Recovery is intentionally driven by this adapter-mapped error alone.
     * Plain proxy prose is not a Nacos error envelope and must never cause a
     * credential refresh or a second request.
     */
    private fun recoverableNacosPasswordTokenFailure(
        target: OperationTarget,
        response: ProtocolResponse
    ): RemoteOperationError.InvalidOrExpiredNacosPasswordToken? =
        if (target.context.authenticationStrategy == V1AuthenticationStrategy.NACOS_PASSWORD) {
            mapAuthenticationFailure(response) as? RemoteOperationError.InvalidOrExpiredNacosPasswordToken
        } else {
            null
        }

    private fun mapAuthenticationFailure(response: ProtocolResponse): RemoteOperationError? {
        if (response.status !in setOf(401, 403)) return null
        val envelope = runCatching {
            gson.fromJson(response.body, V1ErrorEnvelope::class.java)
        }.getOrNull() ?: return null
        if (envelope.code?.toIntOrNull() != response.status) return null
        return when {
            isInvalidOrExpiredNacosPasswordMessage(envelope.message) ->
                RemoteOperationError.InvalidOrExpiredNacosPasswordToken(response.status)
            isPermissionDenied(envelope.message) -> RemoteOperationError.Authorization(response.status)
            else -> null
        }
    }

    private fun isInvalidOrExpiredNacosPasswordMessage(message: String?): Boolean =
        message.orEmpty()
            .trim()
            .trimEnd('.', '!', ':')
            .lowercase() in setOf(
            "token invalid",
            "token is invalid",
            "token expired",
            "token is expired",
            "access token invalid",
            "access token is invalid",
            "access token expired",
            "access token is expired"
        )

    private fun isPermissionDenied(message: String?): Boolean =
        Regex("(?i)permission|forbidden|denied").containsMatchIn(message.orEmpty())

    private data class RequestAuthentication(
        val query: List<Pair<String, String>> = emptyList(),
        val headers: Map<String, String> = emptyMap()
    )

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

    private data class V1ErrorEnvelope(
        val code: String? = null,
        val message: String? = null
    )

    private companion object {
        const val CONFIGS_PATH = "/nacos/v1/cs/configs"
        const val NAMESPACES_PATH = "/nacos/v1/console/namespaces"
        const val DEFAULT_READ_BUDGET_MILLIS = 30_000L
    }
}
