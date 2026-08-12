package com.nanyin.nacos.search.services.operations

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.AuthenticationSessionRegistry
import com.nanyin.nacos.search.services.AuthenticationToken
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Owns every V3 wire decision for the read and write path: method, path,
 * query, namespaceId encoding, authentication placement, raw state-map
 * handling, JSON envelope codes, and typed error mapping.
 *
 * Upper layers must not branch on API generation. All V3-specific wire
 * shape lives here and behind the [ProtocolAdapter] contract.
 *
 * Completed Nacos-password tokens live only in [sessions] (ADR-0012 /
 * issue #96). This dialect supplies the login request and response shape;
 * it does not own a private completed-token store.
 *
 * Probing, configuration browsing, and publishing all run through
 * [ProtocolCore] (ADR-0048 / issues #97–#99). Publish uses
 * [ProtocolCore.executeOnce] so a refused token is never replayed.
 */
class V3ProtocolAdapter(
    private val transport: ProtocolTransport,
    private val sessions: AuthenticationSessionRegistry = AuthenticationSessionRegistry(),
    private val gson: Gson = Gson()
) : ProtocolAdapter {
    private var clock: () -> Long = System::currentTimeMillis
    private var core: ProtocolCore = ProtocolCore(transport, sessions)

    override val capabilities: ProtocolCapabilities = ProtocolCapabilities.V3

    internal constructor(
        transport: ProtocolTransport,
        sessions: AuthenticationSessionRegistry,
        budgetMillis: Long,
        clock: () -> Long,
        gson: Gson
    ) : this(transport, sessions, gson) {
        this.clock = clock
        this.core = ProtocolCore(transport, sessions, budgetMillis, clock)
    }

    override suspend fun probe(target: OperationTarget): Result<Unit> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(stateRequest(target), auth) },
            classify = { response -> classifyProbeResponse(target, response) },
            parse = { response -> parseRawStateMap(response.body) },
            login = { performLogin(it) }
        )
    }

    private fun classifyProbeResponse(
        target: OperationTarget,
        response: ProtocolResponse
    ): ClassifiedResponse {
        if (rejectedNacosPasswordToken(target, response)) {
            return ClassifiedResponse.RecoverableTokenRefusal(response.status)
        }
        return when (response.status) {
            in 200..299 -> ClassifiedResponse.Success(response)
            404 -> try {
                classifyStateNotFound(response)
                error("classifyStateNotFound must throw")
            } catch (error: RemoteOperationError) {
                ClassifiedResponse.Failure(error)
            }
            else -> ClassifiedResponse.Failure(mapStatusFailure(response))
        }
    }

    /** Shared browsing classification: recover refused tokens once; map other HTTP failures. */
    private fun classifyBrowsingResponse(
        target: OperationTarget,
        response: ProtocolResponse
    ): ClassifiedResponse {
        if (rejectedNacosPasswordToken(target, response)) {
            return ClassifiedResponse.RecoverableTokenRefusal(response.status)
        }
        return when (response.status) {
            in 200..299 -> ClassifiedResponse.Success(response)
            else -> ClassifiedResponse.Failure(mapStatusFailure(response))
        }
    }

    /**
     * Detail treats some 404 envelopes as successful absence (null). Classification
     * therefore admits 404 into [ClassifiedResponse.Success] for [unwrapEnvelopeOrNull].
     */
    private fun classifyDetailResponse(
        target: OperationTarget,
        response: ProtocolResponse
    ): ClassifiedResponse {
        if (rejectedNacosPasswordToken(target, response)) {
            return ClassifiedResponse.RecoverableTokenRefusal(response.status)
        }
        return when (response.status) {
            in 200..299, 404 -> ClassifiedResponse.Success(response)
            else -> ClassifiedResponse.Failure(mapStatusFailure(response))
        }
    }

    override suspend fun listSummaries(
        target: OperationTarget,
        query: SummaryQuery
    ): Result<SummaryPage> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(listRequest(target, query), auth) },
            classify = { classifyBrowsingResponse(target, it) },
            parse = { response ->
                parseV3("summary list") {
                    parseSummaryPage(unwrapEnvelope(response, "summary list"))
                }
            },
            login = { performLogin(it) }
        )
    }

    override suspend fun readDetail(
        target: OperationTarget,
        coordinate: ConfigurationCoordinate
    ): Result<NacosConfiguration?> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(detailRequest(target, coordinate), auth) },
            classify = { classifyDetailResponse(target, it) },
            parse = { response ->
                parseV3("detail") {
                    val data = unwrapEnvelopeOrNull(response, "detail")
                    if (data == null || data.isJsonNull) null else parseDetail(data)
                }
            },
            login = { performLogin(it) }
        )
    }

    override suspend fun publish(
        target: OperationTarget,
        command: PublishCommand
    ): Result<PublishOutcome> {
        validate(target)
        // V3 admin CS APIs take groupName (not V1's group). commonFormFields()
        // is generation-neutral and still spells "group"; remap on this dialect.
        val params = command.commonFormFields().map { (key, value) ->
            if (key == "group") "groupName" to value else key to value
        } + listOf(
            "namespaceId" to NamespaceInfo.canonicalId(command.namespaceId)
        )
        val formData = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8.name())
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
        return core.executeOnce(
            target = target,
            build = { auth -> applyAuth(baseRequest, auth) },
            classify = { classifyPublishResponse(target, it) },
            parse = { response ->
                // V3 publish response data is a boolean, not a JSON object.
                verifyV3EnvelopeSuccess(response, "publish")
                // V3 has no CAS parameter; ordinary publish success only.
                PublishOutcome.Written(response.body)
            },
            login = { performLogin(it) }
        )
    }

    private fun classifyPublishResponse(
        target: OperationTarget,
        response: ProtocolResponse
    ): ClassifiedResponse {
        if (rejectedNacosPasswordToken(target, response)) {
            return ClassifiedResponse.RecoverableTokenRefusal(response.status)
        }
        return when (response.status) {
            in 200..299 -> ClassifiedResponse.Success(response)
            else -> ClassifiedResponse.Failure(mapStatusFailure(response))
        }
    }

    /** V3 declares the documented content-search capability. V1 does not. */
    override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(historyListRequest(target, query), auth) },
            classify = { classifyBrowsingResponse(target, it) },
            parse = { response ->
                parseV3("history list") {
                    parseHistoryPage(unwrapEnvelope(response, "history list"))
                }
            },
            login = { performLogin(it) }
        )
    }

    override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(historyDetailRequest(target, historyId), auth) },
            classify = { classifyBrowsingResponse(target, it) },
            parse = { response ->
                parseV3("history detail") {
                    parseHistoryDetail(unwrapEnvelope(response, "history detail"))
                }
            },
            login = { performLogin(it) }
        )
    }

    override suspend fun discoverNamespaces(target: OperationTarget): Result<List<DiscoveredNamespace>> {
        validate(target)
        return core.executeIdempotent(
            target = target,
            build = { auth -> applyAuth(namespaceListRequest(target), auth) },
            classify = { classifyBrowsingResponse(target, it) },
            parse = { response ->
                parseV3("namespace list") {
                    parseNamespaceList(unwrapEnvelopeElement(response, "namespace list"))
                }
            },
            login = { performLogin(it) }
        )
    }

    private inline fun <T> parseV3(operation: String, block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: RemoteOperationError) {
        throw error
    } catch (error: JsonParseException) {
        throw RemoteOperationError.Protocol("Invalid V3 $operation response", error)
    } catch (error: Throwable) {
        throw RemoteOperationError.Connection(error)
    }

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
            "groupName" to query.group,
            "appName" to query.appName,
            // Admin list takes camelCase configTags (not V1's config_tags) and
            // accepts an empty configDetail content filter (docs mark it Yes).
            "configTags" to query.configTags,
            "configDetail" to "",
            "search" to query.search,
            "namespaceId" to NamespaceInfo.canonicalId(target.namespaceId)
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
            "groupName" to coordinate.group,
            "namespaceId" to NamespaceInfo.canonicalId(target.namespaceId)
        ),
        headers = mapOf("Accept" to "application/json")
    )


    private fun historyListRequest(target: OperationTarget, query: HistoryQuery): ProtocolRequest = ProtocolRequest(
        method = "GET",
        endpoint = target.context.endpoint.value,
        path = HISTORY_LIST_PATH,
        query = listOf(
            "dataId" to query.coordinate.dataId,
            "groupName" to query.coordinate.group,
            "namespaceId" to NamespaceInfo.canonicalId(target.namespaceId),
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

    private fun rejectedNacosPasswordToken(target: OperationTarget, response: ProtocolResponse): Boolean =
        target.context.authenticationStrategy == V1AuthenticationStrategy.NACOS_PASSWORD &&
            isInvalidOrExpiredToken(response)

    private fun applyAuth(request: ProtocolRequest, auth: RequestAuthentication): ProtocolRequest =
        request.copy(
            query = request.query + auth.query,
            headers = request.headers + auth.headers
        )

    /**
     * Dialect-owned V3 login wire. Credentials stay on the request body only —
     * never in logs, equality, or the session key (ADR-0009).
     */
    private suspend fun performLogin(target: OperationTarget): AuthenticationToken? {
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
        return AuthenticationToken(
            value = accessToken,
            expiresAtMillis = clock() +
                ((ttlSeconds - TOKEN_EXPIRY_SKEW_SECONDS) * 1000L)
        )
    }

    private fun extractLoginTokenObject(body: String): JsonObject? {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
        if (root.has("accessToken")) return root
        if (root.has("code")) {
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                throw mapEnvelopeCode(code, root.get("message")?.asString, stringDetail(root.get("data")))
            }
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
            throw mapEnvelopeCode(envelope.code, envelope.message, envelopeDetail(envelope.data))
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
        val data = root.get("data")
        if (code != 0) throw mapEnvelopeCode(code, message, stringDetail(data))
        return data ?: throw RemoteOperationError.Protocol("V3 $operation response data is missing")
    }

    private fun unwrapEnvelopeOrNull(response: ProtocolResponse, operation: String): JsonObject? {
        return unwrapEnvelopeOrNullInternal(response, operation)
    }

    /** Checks the V3 envelope success code without requiring data to be a JSON object. */
    private fun verifyV3EnvelopeSuccess(response: ProtocolResponse, operation: String) {
        if (response.status !in 200..299) throw mapStatusFailure(response)
        val parsed = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
            ?: throw RemoteOperationError.Protocol("V3 $operation response was empty")
        if (parsed.code != 0) {
            throw mapEnvelopeCode(parsed.code, parsed.message, envelopeDetail(parsed.data))
        }
    }

    private fun unwrapEnvelopeOrNullInternal(response: ProtocolResponse, operation: String): JsonObject? {
        if (response.status == 404) {
            // Detail 404: check envelope code to distinguish not-found from generation-unsupported.
            val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
            if (envelope != null && envelope.code == CODE_NOT_FOUND) return null
            throw mapEnvelopeCode(
                envelope?.code ?: -1,
                envelope?.message,
                envelopeDetail(envelope?.data)
            )
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
                ConfigurationSummary(
                    item.dataId,
                    item.resolvedGroup(),
                    NamespaceInfo.canonicalTenantId(item.namespaceFromResponse()),
                    item.content,
                    item.type
                )
            }
        )
    }

    private fun parseDetail(data: JsonObject): NacosConfiguration? {
        val detail = gson.fromJson(data, V3ConfigDetailData::class.java)
            ?: throw RemoteOperationError.Protocol("Invalid V3 detail data")
        val group = detail.resolvedGroup()
        if (detail.dataId.isBlank() || group.isBlank()) return null
        return NacosConfiguration(
            dataId = detail.dataId,
            group = group,
            tenantId = NamespaceInfo.canonicalTenantId(detail.namespaceFromResponse()),
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
                    group = item.resolvedGroup(),
                    tenantId = NamespaceInfo.canonicalTenantId(item.namespaceFromResponse()),
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
            group = detail.resolvedGroup(),
            tenantId = NamespaceInfo.canonicalTenantId(detail.namespaceFromResponse()),
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
            // Prefer the namespace id field even when blank — blank/public spellings
            // collapse via canonicalId. Fall back to show-name only when the id
            // field is absent, never when it is an empty public Namespace.
            val namespaceField = obj.get("namespace")
                ?.takeIf { it.isJsonPrimitive && !it.isJsonNull }
                ?.asString
            val namespaceId = namespaceField
                ?: firstPresent(
                    obj.get("namespaceId")?.asString,
                    obj.get("namespaceShowName")?.asString,
                    obj.get("namespaceName")?.asString
                )
                ?: return@mapNotNull null
            val normalizedId = NamespaceInfo.canonicalId(namespaceId)
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

    /**
     * V3 configuration list/detail/history envelopes may spell the Namespace as
     * the legacy `tenant` field or as the request parameter `namespaceId`
     * (issue #191). Prefer whichever is present and non-blank; when both are
     * present, prefer the legacy tenant spelling that production servers
     * historically returned.
     */
    private fun namespaceFromResponseFields(tenant: String?, namespaceId: String?): String? =
        firstPresent(tenant, namespaceId)

    private fun V3ConfigItem.namespaceFromResponse(): String? =
        namespaceFromResponseFields(tenant, namespaceId)

    private fun V3ConfigDetailData.namespaceFromResponse(): String? =
        namespaceFromResponseFields(tenant, namespaceId)

    private fun V3HistoryItem.namespaceFromResponse(): String? =
        namespaceFromResponseFields(tenant, namespaceId)

    private fun V3HistoryDetailData.namespaceFromResponse(): String? =
        namespaceFromResponseFields(tenant, namespaceId)

    private fun mapStatusFailure(response: ProtocolResponse): RemoteOperationError {
        val envelope = runCatching { gson.fromJson(response.body, V3Envelope::class.java) }.getOrNull()
        val code = envelope?.code
        return when {
            code != null && code != 0 ->
                mapEnvelopeCode(code, envelope?.message, envelopeDetail(envelope?.data))
            // Bare status without a usable envelope: keep authentication vs
            // authorization distinct so V1 and V3 agree on product meaning
            // for the same classified outcome (ADR-0048 / issue #97).
            response.status == 401 -> RemoteOperationError.Authentication(response.status)
            response.status == 403 -> RemoteOperationError.Authorization(response.status)
            response.status == 404 -> RemoteOperationError.NotFound()
            response.status == 429 -> RemoteOperationError.RateLimited()
            response.status in 400..499 -> RemoteOperationError.Client(response.status)
            response.status in 500..599 -> RemoteOperationError.Server(response.status)
            else -> RemoteOperationError.Protocol("Unexpected HTTP status ${response.status}")
        }
    }

    /**
     * Nacos V3 error envelopes put a short category in [message] (e.g.
     * "parameter missing") and the actionable text in [detail] / `data`
     * (e.g. "Required parameter 'groupName' type String is not present").
     * Prefer the detail so the tool window does not show only an opaque code.
     */
    private fun mapEnvelopeCode(
        code: Int,
        message: String?,
        detail: String? = null
    ): RemoteOperationError {
        val hint = firstPresent(detail, message).orEmpty()
        return when (code) {
            CODE_SUCCESS -> throw IllegalStateException("mapEnvelopeCode called with success code")
            CODE_PARAMETER_MISSING -> RemoteOperationError.Protocol(
                if (hint.isBlank()) "V3 parameter missing" else "V3 parameter missing: $hint"
            )
            CODE_ACCESS_DENIED -> RemoteOperationError.Authorization(403)
            CODE_NOT_FOUND -> RemoteOperationError.NotFound()
            CODE_INVALID_TOKEN -> RemoteOperationError.Authentication(401)
            // Nacos (and non-V3 frontends) commonly answer unknown V3 routes with
            // {code:-1,message:"No message available"}. Treat like classifyStateNotFound:
            // this is generation absence for AUTO, not a locked-V3 auth failure.
            -1 -> RemoteOperationError.GenerationUnsupported(
                "V3 envelope code -1: ${hint.ifBlank { "not a V3 response" }}"
            )
            in 400..499 -> RemoteOperationError.Client(code)
            in 500..599 -> RemoteOperationError.Server(code)
            else -> RemoteOperationError.Protocol(
                "Unexpected V3 envelope code $code" + if (hint.isBlank()) "" else ": $hint"
            )
        }
    }

    private fun envelopeDetail(data: Any?): String? = when (data) {
        is String -> data.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun stringDetail(data: JsonElement?): String? =
        data?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotBlank() }

    // ---- helpers ----

    private fun validate(target: OperationTarget) {
        require(target.context.resolvedGeneration == NacosApiGeneration.V3) {
            throw RemoteOperationError.Unsupported("V3 adapter requires a V3-locked target")
        }
        validateOperationAuthentication(target, "V3")
    }

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
        // Real 3.x admin responses spell groupName; accept legacy "group" too.
        val groupName: String = "",
        val group: String = "",
        val content: String? = null,
        val type: String? = null,
        val tenant: String? = null,
        // V3 request parameter spelling; some 3.x builds echo this instead of tenant.
        val namespaceId: String? = null
    ) {
        fun resolvedGroup(): String = groupName.ifBlank { group }
    }

    private data class V3ConfigDetailData(
        val id: String? = null,
        val dataId: String = "",
        val groupName: String = "",
        val group: String = "",
        val tenant: String? = null,
        val namespaceId: String? = null,
        val content: String? = null,
        val type: String? = null,
        val md5: String? = null
    ) {
        fun resolvedGroup(): String = groupName.ifBlank { group }
    }

    private data class V3HistoryListData(
        val totalCount: Int = 0,
        val pageNumber: Int = 0,
        val pagesAvailable: Int = 0,
        val pageItems: List<V3HistoryItem> = emptyList()
    )

    private data class V3HistoryItem(
        val id: String? = null,
        val dataId: String = "",
        val groupName: String = "",
        val group: String = "",
        val type: String? = null,
        val md5: String? = null,
        val tenant: String? = null,
        val namespaceId: String? = null,
        val lastModified: Long? = null,
        val modifyTime: Long? = null,
        val createTime: Long? = null,
        val lastModifiedTime: String? = null,
        val createdTime: String? = null,
        val opType: String? = null
    ) {
        fun resolvedGroup(): String = groupName.ifBlank { group }
    }

    private data class V3HistoryDetailData(
        val id: String? = null,
        val dataId: String = "",
        val groupName: String = "",
        val group: String = "",
        val tenant: String? = null,
        val namespaceId: String? = null,
        val content: String? = null,
        val type: String? = null,
        val md5: String? = null,
        val lastModified: Long? = null,
        val modifyTime: Long? = null,
        val createTime: Long? = null,
        val lastModifiedTime: String? = null,
        val createdTime: String? = null,
        val opType: String? = null
    ) {
        fun resolvedGroup(): String = groupName.ifBlank { group }
    }

    private companion object {
        const val STATE_PATH = "/nacos/v3/admin/core/state"
        const val AUTH_LOGIN_PATH = "/nacos/v3/auth/user/login"
        const val NAMESPACE_LIST_PATH = "/nacos/v3/admin/core/namespace/list"
        const val LIST_PATH = "/nacos/v3/admin/cs/config/list"
        const val DETAIL_PATH = "/nacos/v3/admin/cs/config"
        const val HISTORY_LIST_PATH = "/nacos/v3/admin/cs/history/list"
        const val HISTORY_DETAIL_PATH = "/nacos/v3/admin/cs/history"
        const val CODE_SUCCESS = 0
        const val CODE_PARAMETER_MISSING = 10000
        const val CODE_ACCESS_DENIED = 10001
        const val CODE_NOT_FOUND = 20004
        const val CODE_INVALID_TOKEN = 401
        const val DEFAULT_TOKEN_TTL_SECONDS = 18_000L
        const val TOKEN_EXPIRY_SKEW_SECONDS = 30L
    }
}
