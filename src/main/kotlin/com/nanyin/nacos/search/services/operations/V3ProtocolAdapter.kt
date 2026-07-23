package com.nanyin.nacos.search.services.operations

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Optional capabilities a V3 adapter may declare for the current identity. */
enum class V3Capability {
    CONTENT_SEARCH,
    CONFIG_SUMMARY_LIST,
    CONFIG_DETAIL,
    NAMESPACE_DISCOVERY
}

/**
 * Owns every V3 wire decision for the read path: method, path,
 * query, namespaceId encoding, authentication placement, raw state-map
 * handling, JSON envelope codes, and typed error mapping.
 *
 * Upper layers must not branch on API generation. All V3-specific wire
 * shape lives here and behind the [ProtocolAdapter] contract.
 */
class V3ProtocolAdapter(
    private val transport: ProtocolTransport,
    private val gson: Gson = Gson()
) : ProtocolAdapter, HistoryCapability, NamespaceDiscoveryCapability {

    private val tokenCache = ConcurrentHashMap<String, CachedAccessToken>()

    override suspend fun probe(target: OperationTarget): Result<Unit> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(stateRequest(target), auth) }
            // V3 state is a raw map — not wrapped in the {code,message,data} envelope.
            // A 404 on this path means V3 is not available; a non-JSON or envelope
            // response means we are not talking to a V3 server.
            when (response.status) {
                in 200..299 -> parseRawStateMap(response.body)
                404 -> classifyStateNotFound(response)
                else -> throw mapStatusFailure(response)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    override suspend fun listSummaries(
        target: OperationTarget,
        query: SummaryQuery
    ): Result<SummaryPage> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(listRequest(target, query), auth) }
            val data = unwrapEnvelope(response, "summary list")
            parseSummaryPage(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: JsonParseException) {
            throw RemoteOperationError.Protocol("Invalid V3 summary response", error)
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    override suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(detailRequest(target, coordinate), auth) }
            val data = unwrapEnvelopeOrNull(response, "detail")
            if (data == null || data.isJsonNull) return@runCatching null
            parseDetail(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: JsonParseException) {
            throw RemoteOperationError.Protocol("Invalid V3 detail response", error)
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    override suspend fun publish(
        target: OperationTarget,
        command: PublishCommand
    ): Result<PublishOutcome> = runCatching {
        validate(target)
        val params = buildList {
            add("dataId" to command.dataId)
            add("group" to command.group)
            add("content" to command.content)
            add("type" to command.type)
            command.appName?.let { add("appName" to it) }
            command.desc?.let { add("desc" to it) }
            command.configTags?.let { add("config_tags" to it) }
            add("namespaceId" to command.namespaceId.trim().ifBlank { "public" })
        }
        val formData = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val baseRequest = ProtocolRequest(
            method = "POST",
            endpoint = target.context.endpoint.value,
            path = DETAIL_PATH,
            query = emptyList(),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            body = formData
        )
        val response = executeAuthenticated(target) { auth -> applyAuth(baseRequest, auth) }
        // V3 publish response data is a boolean, not a JSON object.
        verifyV3EnvelopeSuccess(response, "publish")
        // V3 has no CAS parameter; ordinary publish success only.
        PublishOutcome.Written(response.body)
    }

    /** V3 declares the documented content-search capability. V1 does not. */
    override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(historyListRequest(target, query), auth) }
            val data = unwrapEnvelope(response, "history list")
            parseHistoryPage(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: JsonParseException) {
            throw RemoteOperationError.Protocol("Invalid V3 history list response", error)
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(historyDetailRequest(target, historyId), auth) }
            val data = unwrapEnvelope(response, "history detail")
            parseHistoryDetail(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: JsonParseException) {
            throw RemoteOperationError.Protocol("Invalid V3 history detail response", error)
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    override suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>> = runCatching {
        validate(target)
        try {
            val response = executeAuthenticated(target) { auth -> applyAuth(namespaceListRequest(target), auth) }
            val data = unwrapEnvelopeElement(response, "namespace list")
            parseNamespaceList(data)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: JsonParseException) {
            throw RemoteOperationError.Protocol("Invalid V3 namespace list response", error)
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    fun declaredCapabilities(): Set<V3Capability> = setOf(
        V3Capability.CONTENT_SEARCH,
        V3Capability.CONFIG_SUMMARY_LIST,
        V3Capability.CONFIG_DETAIL,
        V3Capability.NAMESPACE_DISCOVERY
    )

    // ---- request builders ----

    private fun stateRequest(target: OperationTarget): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = STATE_PATH,
        query = emptyList(),
        headers = mapOf("Accept" to "application/json")
    )

    private fun listRequest(target: OperationTarget, query: SummaryQuery): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = LIST_PATH,
        query = listOf(
            "pageNo" to query.pageNo.toString(),
            "pageSize" to query.pageSize.toString(),
            "dataId" to query.dataId,
            "group" to query.group,
            "appName" to query.appName,
            "config_tags" to query.configTags,
            "search" to query.search,
            "namespaceId" to target.namespaceId.trim().ifBlank { "public" }
        ),
        headers = mapOf("Accept" to "application/json")
    )

    private fun detailRequest(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = DETAIL_PATH,
        query = listOf(
            "dataId" to coordinate.dataId,
            "group" to coordinate.group,
            "namespaceId" to target.namespaceId.trim().ifBlank { "public" }
        ),
        headers = mapOf("Accept" to "application/json")
    )


    private fun historyListRequest(target: OperationTarget, query: HistoryQuery): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = HISTORY_LIST_PATH,
        query = listOf(
            "dataId" to query.coordinate.dataId,
            "group" to query.coordinate.group,
            "namespaceId" to target.namespaceId.trim().ifBlank { "public" },
            "pageNo" to query.pageNo.toString(),
            "pageSize" to query.pageSize.toString()
        ),
        headers = mapOf("Accept" to "application/json")
    )

    private fun historyDetailRequest(target: OperationTarget, historyId: String): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = HISTORY_DETAIL_PATH,
        query = listOf("nid" to historyId),
        headers = mapOf("Accept" to "application/json")
    )

    private fun namespaceListRequest(target: OperationTarget): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = NAMESPACE_LIST_PATH,
        query = emptyList(),
        headers = mapOf("Accept" to "application/json")
    )

    // ---- authentication ----

    private suspend fun executeAuthenticated(
        target: OperationTarget,
        build: (RequestAuthentication) -> ProtocolRequest
    ): ProtocolResponse {
        val firstAuth = authenticationFor(target, forceRefresh = false)
        val firstResponse = transport.execute(build(firstAuth))
        if (
            target.context.authenticationStrategy == V1AuthenticationStrategy.NACOS_PASSWORD &&
            isInvalidOrExpiredToken(firstResponse)
        ) {
            val refreshed = authenticationFor(target, forceRefresh = true)
            return transport.execute(build(refreshed))
        }
        return firstResponse
    }

    private fun applyAuth(request: ProtocolRequest, auth: RequestAuthentication): ProtocolRequest =
        request.copy(
            query = request.query + auth.query,
            headers = request.headers + auth.headers
        )

    private suspend fun authenticationFor(
        target: OperationTarget,
        forceRefresh: Boolean
    ): RequestAuthentication = when (target.context.authenticationStrategy) {
        V1AuthenticationStrategy.ANONYMOUS -> RequestAuthentication()
        V1AuthenticationStrategy.NACOS_PASSWORD -> {
            val token = loginAccessToken(target, forceRefresh)
                ?: throw RemoteOperationError.Authentication(401)
            RequestAuthentication(query = listOf("accessToken" to token))
        }
        V1AuthenticationStrategy.HTTP_BASIC -> {
            val credentials = "${target.context.identity.principal}:${target.context.credential.secret}"
            val encoded = java.util.Base64.getEncoder()
                .encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
            RequestAuthentication(headers = mapOf("Authorization" to "Basic $encoded"))
        }
        V1AuthenticationStrategy.BEARER_TOKEN -> RequestAuthentication(
            headers = mapOf("Authorization" to "Bearer ${target.context.credential.secret}")
        )
    }

    private suspend fun loginAccessToken(target: OperationTarget, forceRefresh: Boolean): String? {
        val cacheKey = listOf(
            target.context.endpoint.value,
            target.context.identity.principal,
            target.context.accessRevision.toString()
        ).joinToString("|")
        if (!forceRefresh) {
            tokenCache[cacheKey]?.takeIf { it.expiresAtEpochMs > System.currentTimeMillis() }?.let {
                return it.accessToken
            }
        } else {
            tokenCache.remove(cacheKey)
        }
        val username = target.context.identity.principal.takeUnless { it == "<anonymous>" }.orEmpty()
        val password = target.context.credential.secret
        if (username.isBlank() || password.isBlank()) return null
        val formData =
            "username=${URLEncoder.encode(username, StandardCharsets.UTF_8.name())}" +
                "&password=${URLEncoder.encode(password, StandardCharsets.UTF_8.name())}"
        val request = ProtocolRequest(
            method = "POST",
            endpoint = target.context.endpoint.value,
            path = AUTH_LOGIN_PATH,
            query = emptyList(),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            body = formData
        )
        val response = transport.execute(request)
        if (response.status !in 200..299) {
            throw mapStatusFailure(response)
        }
        // V3 login may return either a bare token object or a {code,message,data} envelope.
        val tokenObject = extractLoginTokenObject(response.body)
            ?: throw RemoteOperationError.Protocol("V3 login response missing accessToken")
        val accessToken = tokenObject.get("accessToken")?.asString?.takeIf { it.isNotBlank() }
            ?: throw RemoteOperationError.Protocol("V3 login response missing accessToken")
        val ttlSeconds = tokenObject.get("tokenTtl")?.asLong ?: DEFAULT_TOKEN_TTL_SECONDS
        tokenCache[cacheKey] = CachedAccessToken(
            accessToken = accessToken,
            expiresAtEpochMs = System.currentTimeMillis() +
                ((ttlSeconds - TOKEN_EXPIRY_SKEW_SECONDS) * 1000L)
        )
        return accessToken
    }

    private fun extractLoginTokenObject(body: String): JsonObject? {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
        if (root.has("accessToken")) return root
        if (root.has("code")) {
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) throw mapEnvelopeCode(code, root.get("message")?.asString)
            val data = root.get("data")
            if (data != null && data.isJsonObject) return data.asJsonObject
        }
        return null
    }

    private fun isInvalidOrExpiredToken(response: ProtocolResponse): Boolean {
        if (response.status !in setOf(401, 403)) {
            val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
            return envelope?.code == CODE_INVALID_TOKEN
        }
        val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
        return envelope?.code == CODE_INVALID_TOKEN || response.status == 401
    }

    // ---- response parsing ----

    private fun parseRawStateMap(body: String) {
        val parsed = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
            ?: throw RemoteOperationError.GenerationUnsupported("V3 state response was not a JSON object")
        if (!parsed.isJsonObject) {
            throw RemoteOperationError.GenerationUnsupported("V3 state response was not a JSON object")
        }
    }

    /**
     * A 404 on the state endpoint can mean two different things:
     * - The server genuinely does not have this route (no V3, or a wrong
     *   context path) -> GenerationUnsupported, authorising a V1 fallback.
     * - The server IS V3 but returned a 404 with an envelope code (e.g.
     *   resource not found, access denied) -> map the envelope code; this
     *   must NOT trigger a generation fallback.
     */
    private fun classifyStateNotFound(response: ProtocolResponse) {
        val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
        if (envelope != null && envelope.code != -1) {
            throw mapEnvelopeCode(envelope.code, envelope.message)
        }
        throw RemoteOperationError.GenerationUnsupported("V3 state endpoint returned 404")
    }

    private fun unwrapEnvelope(response: ProtocolResponse, operation: String): JsonObject {
        val element = unwrapEnvelopeElement(response, operation)
        return element.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw RemoteOperationError.Protocol("V3 $operation response data is missing or not an object")
    }

    private fun unwrapEnvelopeElement(response: ProtocolResponse, operation: String): JsonElement {
        if (response.status !in 200..299) throw mapStatusFailure(response)
        val root = runCatching { gson.fromJson(response.body, JsonObject::class.java) }.getOrNull()
            ?: throw RemoteOperationError.Protocol("V3 $operation response was empty")
        val code = root.get("code")?.asInt ?: -1
        val message = root.get("message")?.asString
        if (code != 0) throw mapEnvelopeCode(code, message)
        return root.get("data")
            ?: throw RemoteOperationError.Protocol("V3 $operation response data is missing")
    }

    private fun unwrapEnvelopeOrNull(response: ProtocolResponse, operation: String): JsonObject? {
        return unwrapEnvelopeOrNullInternal(response, operation)
    }

    /** Checks the V3 envelope success code without requiring data to be a JSON object. */
    private fun verifyV3EnvelopeSuccess(response: ProtocolResponse, operation: String) {
        if (response.status !in 200..299) throw mapStatusFailure(response)
        val parsed = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
            ?: throw RemoteOperationError.Protocol("V3 $operation response was empty")
        if (parsed.code != 0) throw mapEnvelopeCode(parsed.code, parsed.message)
    }

    private fun unwrapEnvelopeOrNullInternal(response: ProtocolResponse, operation: String): JsonObject? {
        if (response.status == 404) {
            // Detail 404: check envelope code to distinguish not-found from generation-unsupported.
            val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
            if (envelope != null && envelope.code == CODE_NOT_FOUND) return null
            throw mapEnvelopeCode(envelope?.code ?: -1, envelope?.message)
        }
        return unwrapEnvelope(response, operation)
    }

    private fun parseSummaryPage(data: JsonObject): SummaryPage {
        val page = gson.fromJson(data, V3ConfigListData::class.java)
            ?: throw RemoteOperationError.Protocol("Invalid V3 summary data")
        return SummaryPage(
            totalCount = page.totalCount,
            pageNumber = page.pageNumber,
            pagesAvailable = page.pagesAvailable,
            items = page.pageItems.map { item ->
                ConfigurationSummary(item.dataId, item.group, normalizeTenant(item.tenant), item.content, item.type)
            }
        )
    }

    private fun parseDetail(data: JsonObject): NacosConfiguration? {
        val detail = gson.fromJson(data, V3ConfigDetailData::class.java)
            ?: throw RemoteOperationError.Protocol("Invalid V3 detail data")
        if (detail.dataId.isBlank() || detail.group.isBlank()) return null
        return NacosConfiguration(
            dataId = detail.dataId,
            group = detail.group,
            tenantId = normalizeTenant(detail.tenant),
            content = detail.content ?: "",
            type = detail.type,
            md5 = detail.md5
        )
    }

    // ---- error mapping ----


    private fun parseHistoryPage(data: JsonObject): HistoryPage {
        val page = gson.fromJson(data, V3HistoryListData::class.java)
            ?: throw RemoteOperationError.Protocol("Invalid V3 history data")
        return HistoryPage(
            totalCount = page.totalCount,
            pageNumber = page.pageNumber,
            pagesAvailable = page.pagesAvailable,
            items = page.pageItems.map { item ->
                HistoryEntry(
                    id = item.id ?: "",
                    dataId = item.dataId,
                    group = item.group,
                    tenantId = normalizeTenant(item.tenant),
                    type = item.type,
                    md5 = item.md5,
                    lastModified = HistoryTimestamps.resolveMillis(
                        lastModified = item.lastModified,
                        modifyTime = item.modifyTime,
                        createTime = item.createTime,
                        lastModifiedTime = item.lastModifiedTime,
                        createdTime = item.createdTime
                    ),
                    opType = item.opType?.trim()
                )
            }
        )
    }

    private fun parseHistoryDetail(data: JsonObject): HistoryDetail {
        val detail = gson.fromJson(data, V3HistoryDetailData::class.java)
            ?: throw RemoteOperationError.Protocol("Invalid V3 history detail data")
        if (detail.id.isNullOrBlank()) {
            throw RemoteOperationError.Protocol("V3 history detail data is missing its id")
        }
        return HistoryDetail(
            id = detail.id,
            dataId = detail.dataId,
            group = detail.group,
            tenantId = normalizeTenant(detail.tenant),
            content = detail.content ?: "",
            type = detail.type,
            md5 = detail.md5,
            lastModified = HistoryTimestamps.resolveMillis(
                lastModified = detail.lastModified,
                modifyTime = detail.modifyTime,
                createTime = detail.createTime,
                lastModifiedTime = detail.lastModifiedTime,
                createdTime = detail.createdTime
            ),
            opType = detail.opType?.trim()
        )
    }

    private fun parseNamespaceList(data: JsonElement): List<DiscoveredNamespace> {
        val items: JsonArray = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> {
                val obj = data.asJsonObject
                obj.getAsJsonArray("data")
                    ?: obj.getAsJsonArray("pageItems")
                    ?: JsonArray()
            }
            else -> JsonArray()
        }
        return items.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val namespaceId = firstPresent(
                obj.get("namespace")?.asString,
                obj.get("namespaceShowName")?.asString,
                obj.get("namespaceName")?.asString
            ) ?: return@mapNotNull null
            // V3 public namespace commonly uses empty string as id.
            val normalizedId = namespaceId.trim().ifBlank { "public" }
            DiscoveredNamespace(
                namespaceId = normalizedId,
                displayName = firstPresent(
                    obj.get("namespaceShowName")?.asString,
                    obj.get("namespaceName")?.asString,
                    normalizedId
                ) ?: normalizedId,
                description = obj.get("namespaceDesc")?.asString,
                configCount = obj.get("configCount")?.asLong
            )
        }
    }

    private fun firstPresent(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun mapStatusFailure(response: ProtocolResponse): RemoteOperationError {
        val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
        val code = envelope?.code
        return when {
            code != null && code != 0 -> mapEnvelopeCode(code, envelope?.message)
            response.status in setOf(401, 403) -> RemoteOperationError.Authorization(response.status)
            response.status == 404 -> RemoteOperationError.NotFound()
            response.status == 429 -> RemoteOperationError.RateLimited()
            response.status in 400..499 -> RemoteOperationError.Client(response.status)
            response.status in 500..599 -> RemoteOperationError.Server(response.status)
            else -> RemoteOperationError.Protocol("Unexpected HTTP status ${response.status}")
        }
    }

    private fun mapEnvelopeCode(code: Int, message: String?): RemoteOperationError = when (code) {
        CODE_SUCCESS -> throw IllegalStateException("mapEnvelopeCode called with success code")
        CODE_ACCESS_DENIED -> RemoteOperationError.Authorization(403)
        CODE_NOT_FOUND -> RemoteOperationError.NotFound()
        CODE_INVALID_TOKEN -> RemoteOperationError.Authentication(401)
        // Nacos (and non-V3 frontends) commonly answer unknown V3 routes with
        // {code:-1,message:"No message available"}. Treat like classifyStateNotFound:
        // this is generation absence for AUTO, not a locked-V3 auth failure.
        -1 -> RemoteOperationError.GenerationUnsupported(
            "V3 envelope code -1: ${message.orEmpty().ifBlank { "not a V3 response" }}"
        )
        in 400..499 -> RemoteOperationError.Client(code)
        in 500..599 -> RemoteOperationError.Server(code)
        else -> RemoteOperationError.Protocol("Unexpected V3 envelope code $code: ${message.orEmpty()}")
    }

    // ---- helpers ----

    private fun validate(target: OperationTarget) {
        require(target.context.resolvedGeneration == NacosApiGeneration.V3) {
            throw RemoteOperationError.Unsupported("V3 adapter requires a V3-locked target")
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
                throw RemoteOperationError.Unsupported("Password and principal are required for V3 authentication")
            }
            V1AuthenticationStrategy.BEARER_TOKEN -> require(target.context.credential.secret.isNotBlank()) {
                throw RemoteOperationError.Unsupported("Bearer token is required for V3 authentication")
            }
        }
    }

    private fun normalizeTenant(tenant: String?): String? =
        tenant?.trim()?.takeUnless { it.isEmpty() || it == "public" }

    private data class RequestAuthentication(
        val query: List<Pair<String, String>> = emptyList(),
        val headers: Map<String, String> = emptyMap()
    )

    private data class CachedAccessToken(
        val accessToken: String,
        val expiresAtEpochMs: Long
    )

    private data class V3Envelope(
        val code: Int = -1,
        val message: String? = null,
        val data: Any? = null
    )

    private data class V3ConfigListData(
        val totalCount: Int = 0,
        val pageNumber: Int = 0,
        val pagesAvailable: Int = 0,
        val pageItems: List<V3ConfigItem> = emptyList()
    )

    private data class V3ConfigItem(
        val id: String? = null,
        val dataId: String = "",
        val group: String = "",
        val content: String? = null,
        val type: String? = null,
        val tenant: String? = null
    )

    private data class V3ConfigDetailData(
        val id: String? = null,
        val dataId: String = "",
        val group: String = "",
        val tenant: String? = null,
        val content: String? = null,
        val type: String? = null,
        val md5: String? = null
    )

    private data class V3HistoryListData(
        val totalCount: Int = 0,
        val pageNumber: Int = 0,
        val pagesAvailable: Int = 0,
        val pageItems: List<V3HistoryItem> = emptyList()
    )

    private data class V3HistoryItem(
        val id: String? = null,
        val dataId: String = "",
        val group: String = "",
        val type: String? = null,
        val md5: String? = null,
        val tenant: String? = null,
        val lastModified: Long? = null,
        val modifyTime: Long? = null,
        val createTime: Long? = null,
        val lastModifiedTime: String? = null,
        val createdTime: String? = null,
        val opType: String? = null
    )

    private data class V3HistoryDetailData(
        val id: String? = null,
        val dataId: String = "",
        val group: String = "",
        val tenant: String? = null,
        val content: String? = null,
        val type: String? = null,
        val md5: String? = null,
        val lastModified: Long? = null,
        val modifyTime: Long? = null,
        val createTime: Long? = null,
        val lastModifiedTime: String? = null,
        val createdTime: String? = null,
        val opType: String? = null
    )

    private companion object {
        const val STATE_PATH = "/nacos/v3/admin/core/state"
        const val AUTH_LOGIN_PATH = "/nacos/v3/auth/user/login"
        const val NAMESPACE_LIST_PATH = "/nacos/v3/admin/core/namespace/list"
        const val LIST_PATH = "/nacos/v3/admin/cs/config/list"
        const val DETAIL_PATH = "/nacos/v3/admin/cs/config"
        const val HISTORY_LIST_PATH = "/nacos/v3/admin/cs/history/list"
        const val HISTORY_DETAIL_PATH = "/nacos/v3/admin/cs/history"
        const val CODE_SUCCESS = 0
        const val CODE_ACCESS_DENIED = 10001
        const val CODE_NOT_FOUND = 20004
        const val CODE_INVALID_TOKEN = 401
        const val DEFAULT_TOKEN_TTL_SECONDS = 18_000L
        const val TOKEN_EXPIRY_SKEW_SECONDS = 30L
    }
}
