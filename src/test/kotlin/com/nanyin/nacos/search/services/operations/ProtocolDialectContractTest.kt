package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Behaviour both protocol dialects must share at the generation-neutral
 * [ProtocolAdapter] seam over a scripted [ProtocolTransport]. Concrete suites
 * supply one dialect and its response envelopes; later slices extend this
 * harness with execution-behaviour cases (auth recovery, budgets, retries).
 *
 * Contract assertions cover the request that was built, the outcome returned,
 * and the transport request count. Diagnostics name only method/path/query
 * keys — never credentials, tokens, or configuration content.
 */
internal abstract class ProtocolDialectContractTest {

    protected abstract val generation: NacosApiGeneration

    protected abstract fun newAdapter(transport: ProtocolTransport): ProtocolAdapter

    /** Successful summary list whose single page item carries [rawTenant]. */
    protected abstract fun summaryBody(rawTenant: String?): String

    /** Successful detail whose tenant field is [rawTenant]. */
    protected abstract fun detailBody(rawTenant: String?): String

    /** Successful history list whose first item carries [rawTenant]. */
    protected abstract fun historyListBody(rawTenant: String?): String

    /** Successful history detail whose tenant field is [rawTenant]. */
    protected abstract fun historyDetailBody(rawTenant: String?): String

    /** Successful discovery list whose single entry has namespace id [rawNamespace]. */
    protected abstract fun discoveryBody(rawNamespace: String): String

    /** Assert this dialect's public-Namespace encoding on a summary request. */
    protected abstract fun assertPublicSummaryWire(request: ProtocolRequest)

    @Test
    fun `summary collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, summaryBody(rawTenant)))
            val page = newAdapter(transport)
                .listSummaries(anonymousTarget(), SummaryQuery(pageSize = 1))
                .getOrThrow()

            assertNull(page.items.single().tenantId) {
                "summary tenant for ${describe(rawTenant)} on $generation"
            }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(page.items.single().tenantId))
            assertEquals(1, transport.requests.size) {
                requestCountDiagnostic(transport)
            }
            assertNoSecretMaterial(transport.requests.single())
        }
    }

    @Test
    fun `summary trims surrounding whitespace on a non-public tenant`() = runBlocking {
        val transport = ScriptedTransport(ProtocolResponse(200, summaryBody(" team-a ")))
        val page = newAdapter(transport)
            .listSummaries(anonymousTarget(), SummaryQuery(pageSize = 1))
            .getOrThrow()

        assertEquals("team-a", page.items.single().tenantId)
        assertEquals("team-a", NamespaceInfo.canonicalId(page.items.single().tenantId))
        assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
    }

    @Test
    fun `detail collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, detailBody(rawTenant)))
            val detail = newAdapter(transport)
                .readDetail(anonymousTarget(), ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
                .getOrThrow()

            requireNotNull(detail)
            assertNull(detail.tenantId) { "detail tenant for ${describe(rawTenant)} on $generation" }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(detail.tenantId))
            assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
            assertNoSecretMaterial(transport.requests.single())
        }
    }

    @Test
    fun `history list collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, historyListBody(rawTenant)))
            val page = (newAdapter(transport) as HistoryCapability)
                .listHistory(anonymousTarget(), HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")))
                .getOrThrow()

            assertNull(page.items.single().tenantId) {
                "history-list tenant for ${describe(rawTenant)} on $generation"
            }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(page.items.single().tenantId))
            assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
        }
    }

    @Test
    fun `history detail collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, historyDetailBody(rawTenant)))
            val detail = (newAdapter(transport) as HistoryCapability)
                .readHistoryDetail(anonymousTarget(), "123")
                .getOrThrow()

            assertNull(detail.tenantId) {
                "history-detail tenant for ${describe(rawTenant)} on $generation"
            }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(detail.tenantId))
            assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
        }
    }

    @Test
    fun `namespace discovery collapses blank whitespace and public ids to public`() = runBlocking {
        for (rawNamespace in PUBLIC_SPELLINGS.filterNotNull()) {
            val transport = ScriptedTransport(ProtocolResponse(200, discoveryBody(rawNamespace)))
            val namespaces = (newAdapter(transport) as NamespaceDiscoveryCapability)
                .discoverNamespaces(anonymousTarget())
                .getOrThrow()

            assertEquals(NamespaceInfo.PUBLIC, namespaces.single().namespaceId) {
                "discovery id for ${describe(rawNamespace)} on $generation"
            }
            assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
        }
    }

    @Test
    fun `public Namespace target uses the dialect wire contract on summary`() = runBlocking {
        for (namespaceId in listOf("public", "", "   ", " public ")) {
            val transport = ScriptedTransport(
                ProtocolResponse(200, summaryBody(null))
            )
            newAdapter(transport)
                .listSummaries(anonymousTarget(namespaceId), SummaryQuery(pageSize = 1))
                .getOrThrow()

            assertEquals(1, transport.requests.size) { requestCountDiagnostic(transport) }
            assertPublicSummaryWire(transport.requests.single())
            assertNoSecretMaterial(transport.requests.single())
        }
    }

    protected fun anonymousTarget(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "dialect-contract",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = generation,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = generation
        )
        return OperationTarget(context, namespaceId)
    }

    protected class ScriptedTransport(
        private vararg val responses: ProtocolResponse
    ) : ProtocolTransport {
        val requests = mutableListOf<ProtocolRequest>()
        private var index = 0

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            requests += request
            val response = responses[index.coerceAtMost(responses.lastIndex)]
            index++
            return response
        }
    }

    private fun requestCountDiagnostic(transport: ScriptedTransport): String =
        transport.requests.joinToString(prefix = "requests=", separator = "; ") { request ->
            "${request.method} ${request.path} keys=${request.query.map { it.first }}"
        }

    private fun assertNoSecretMaterial(request: ProtocolRequest) {
        val haystack = buildString {
            append(request.path)
            request.query.forEach { (key, value) -> append(key).append(value) }
            request.headers.forEach { (key, value) -> append(key).append(value) }
            request.body?.let { append(it) }
        }
        assertFalse(haystack.contains("p@ss"), "credential leaked into request diagnostic surface")
        assertFalse(haystack.contains("accessToken="), "token leaked into request")
        assertFalse(haystack.contains("bearer-token"), "token leaked into request")
    }

    private fun describe(raw: String?): String = when (raw) {
        null -> "<null>"
        "" -> "<empty>"
        else -> "«$raw»"
    }

    private companion object {
        val PUBLIC_SPELLINGS = listOf(null, "", "   ", "public", " public ")
    }
}

internal class V1ProtocolDialectContractTest : ProtocolDialectContractTest() {
    override val generation: NacosApiGeneration = NacosApiGeneration.V1

    override fun newAdapter(transport: ProtocolTransport): ProtocolAdapter = V1ProtocolAdapter(transport)

    override fun summaryBody(rawTenant: String?): String =
        """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","content":null,"type":"yaml","tenant":${tenantJson(rawTenant)}}]}"""

    override fun detailBody(rawTenant: String?): String =
        """{"dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"content":"k=v","type":"yaml","md5":"abc"}"""

    override fun historyListBody(rawTenant: String?): String =
        """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"md5":"abc","lastModified":1,"type":"yaml","opType":"PUBLISH"}]}"""

    override fun historyDetailBody(rawTenant: String?): String =
        """{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"content":"k=v","md5":"abc","lastModified":1,"type":"yaml","opType":"PUBLISH"}"""

    override fun discoveryBody(rawNamespace: String): String =
        """{"data":[{"namespace":"$rawNamespace","namespaceShowName":"Name","namespaceDesc":"","configCount":0}]}"""

    override fun assertPublicSummaryWire(request: ProtocolRequest) {
        assertEquals("GET", request.method)
        assertEquals("/nacos/v1/cs/configs", request.path)
        assertFalse(request.query.any { it.first == "tenant" }) {
            "V1 public must omit tenant; keys=${request.query.map { it.first }}"
        }
    }

    private fun tenantJson(rawTenant: String?): String =
        if (rawTenant == null) "null" else "\"$rawTenant\""
}

internal class V3ProtocolDialectContractTest : ProtocolDialectContractTest() {
    override val generation: NacosApiGeneration = NacosApiGeneration.V3

    override fun newAdapter(transport: ProtocolTransport): ProtocolAdapter = V3ProtocolAdapter(transport)

    override fun summaryBody(rawTenant: String?): String =
        """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","content":null,"type":"yaml","tenant":${tenantJson(rawTenant)}}]}}"""

    override fun detailBody(rawTenant: String?): String =
        """{"code":0,"message":"success","data":{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"content":"k=v","type":"yaml","md5":"abc"}}"""

    override fun historyListBody(rawTenant: String?): String =
        """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"md5":"abc","lastModified":1,"type":"yaml","opType":"PUBLISH"}]}}"""

    override fun historyDetailBody(rawTenant: String?): String =
        """{"code":0,"message":"success","data":{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":${tenantJson(rawTenant)},"content":"k=v","md5":"abc","lastModified":1,"type":"yaml","opType":"PUBLISH"}}"""

    override fun discoveryBody(rawNamespace: String): String =
        """{"code":0,"message":"success","data":[{"namespace":"$rawNamespace","namespaceShowName":"Name","namespaceDesc":"","configCount":0}]}"""

    override fun assertPublicSummaryWire(request: ProtocolRequest) {
        assertEquals("GET", request.method)
        assertEquals("/nacos/v3/admin/cs/config/list", request.path)
        assertEquals("public", request.query.single { it.first == "namespaceId" }.second) {
            "V3 public must send namespaceId=public; keys=${request.query.map { it.first }}"
        }
        assertFalse(request.query.any { it.first == "tenant" })
    }

    private fun tenantJson(rawTenant: String?): String =
        if (rawTenant == null) "null" else "\"$rawTenant\""
}
