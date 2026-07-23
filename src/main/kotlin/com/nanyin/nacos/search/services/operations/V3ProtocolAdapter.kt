package com.nanyin.nacos.search.services.operations

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.CancellationException

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
) : ProtocolAdapter, HistoryCapability {

    override suspend fun probe(target: OperationTarget): Result<Unit> = runCatching {
        validate(target)
        try {
            val response = transport.execute(stateRequest(target))
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
            val response = transport.execute(listRequest(target, query))
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
            val response = transport.execute(detailRequest(target, coordinate))
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
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val request = ProtocolRequest(
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
        val response = transport.execute(request)
        // V3 publish response data is a boolean, not a JSON object.
        verifyV3EnvelopeSuccess(response, "publish")
        // V3 has no CAS parameter; ordinary publish success only.
        PublishOutcome.Written(response.body)
    }

    /** V3 declares the documented content-search capability. V1 does not. */
    override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> = runCatching {
        validate(target)
        try {
            val response = transport.execute(historyListRequest(target, query))
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
            val response = transport.execute(historyDetailRequest(target, historyId))
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
        if (response.status !in 200..299) throw mapStatusFailure(response)
        val parsed = gson.fromJson(response.body, V3Envelope::class.java)
            ?: throw RemoteOperationError.Protocol("V3 $operation response was empty")
        // The V3 envelope uses code=0 for success. A non-zero code on a 2xx
        // status is still a typed failure.
        if (parsed.code != 0) throw mapEnvelopeCode(parsed.code, parsed.message)
        val data = gson.toJsonTree(parsed.data)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw RemoteOperationError.Protocol("V3 $operation response data is missing or not an object")
        return data
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
                    lastModified = item.lastModified ?: 0L,
                    opType = item.opType
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
            lastModified = detail.lastModified ?: 0L,
            opType = detail.opType
        )
    }

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
        in 400..499 -> RemoteOperationError.Client(code)
        in 500..599 -> RemoteOperationError.Server(code)
        else -> RemoteOperationError.Protocol("Unexpected V3 envelope code $code: ${message.orEmpty()}")
    }

    // ---- helpers ----

    private fun validate(target: OperationTarget) {
        require(target.context.resolvedGeneration == NacosApiGeneration.V3) {
            throw RemoteOperationError.Unsupported("V3 adapter requires a V3-locked target")
        }
    }

    private fun normalizeTenant(tenant: String?): String? =
        tenant?.trim()?.takeUnless { it.isEmpty() || it == "public" }

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
        val opType: String? = null
    )

    private companion object {
        const val STATE_PATH = "/nacos/v3/admin/core/state"
        const val LIST_PATH = "/nacos/v3/admin/cs/config/list"
        const val DETAIL_PATH = "/nacos/v3/admin/cs/config"
        const val HISTORY_LIST_PATH = "/nacos/v3/admin/cs/history/list"
        const val HISTORY_DETAIL_PATH = "/nacos/v3/admin/cs/history"
        const val CODE_SUCCESS = 0
        const val CODE_ACCESS_DENIED = 10001
        const val CODE_NOT_FOUND = 20004
        const val CODE_INVALID_TOKEN = 401
    }
}
