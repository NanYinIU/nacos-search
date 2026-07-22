package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class V1HistoryAdapterTest {

    @Test
    fun `V1 history list sends the locked wire contract and parses the response`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, V1_HISTORY_LIST_JSON))
        val adapter = V1ProtocolAdapter(fixture)

        val page = adapter.listHistory(
            anonymousPublicTarget(),
            HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
        ).getOrThrow()

        assertEquals(2, page.totalCount)
        assertEquals(1, page.pageNumber)
        assertEquals(1, page.pagesAvailable)
        val entry = page.items.first()
        assertEquals("123", entry.id)
        assertEquals("app.yaml", entry.dataId)
        assertEquals("DEFAULT_GROUP", entry.group)
        assertNull(entry.tenantId)
        assertEquals("yaml", entry.type)
        assertEquals("abc123", entry.md5)
        assertEquals(1700000000000L, entry.lastModified)
        assertEquals("PUBLISH", entry.opType)

        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v1/cs/history",
            query = listOf(
                "search" to "accurate",
                "dataId" to "app.yaml",
                "group" to "DEFAULT_GROUP",
                "pageNo" to "1",
                "pageSize" to "100"
            )
        )
    }

    @Test
    fun `V1 history list with manual namespace includes the tenant parameter`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}""")
        )
        V1ProtocolAdapter(fixture).listHistory(
            anonymousPublicTarget("dev-ns"),
            HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
        ).getOrThrow()

        assertEquals("dev-ns", fixture.lastRequest.query.single { it.first == "tenant" }.second)
    }

    @Test
    fun `V1 history list empty result is a valid page with zero items`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}""")
        )
        val page = V1ProtocolAdapter(fixture)
            .listHistory(anonymousPublicTarget(), HistoryQuery(ConfigurationCoordinate("x", "G")))
            .getOrThrow()

        assertEquals(0, page.totalCount)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `V1 history list maps 403 to authorization error`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(403, """{"code":"403","message":"forbidden"}"""))
        val error = V1ProtocolAdapter(fixture)
            .listHistory(anonymousPublicTarget(), HistoryQuery(ConfigurationCoordinate("x", "G")))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        Unit
    }

    @Test
    fun `V1 history detail fetches by nid and parses content`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, V1_HISTORY_DETAIL_JSON))
        val adapter = V1ProtocolAdapter(fixture)

        val detail = adapter.readHistoryDetail(anonymousPublicTarget(), "123").getOrThrow()

        assertEquals("123", detail.id)
        assertEquals("app.yaml", detail.dataId)
        assertEquals("enabled: true", detail.content)
        assertEquals("abc123", detail.md5)
        assertEquals(1700000000000L, detail.lastModified)
        assertEquals("PUBLISH", detail.opType)

        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v1/cs/history",
            query = listOf(
                "nid" to "123"
            )
        )
    }

    @Test
    fun `V1 history detail 404 maps to not-found error`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(404, "not found"))
        val error = V1ProtocolAdapter(fixture)
            .readHistoryDetail(anonymousPublicTarget(), "999")
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.NotFound::class.java, error)
        Unit
    }

    @Test
    fun `V1 adapter implements HistoryCapability`() {
        val adapter = V1ProtocolAdapter(RecordingTransport(ProtocolResponse(200, "{}")))
        assertInstanceOf(HistoryCapability::class.java, adapter)
    }

    // ---- helpers ----

    private fun anonymousPublicTarget(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "public-read",
                accessRevision = 4,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 4,
            accessRevision = 4,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, namespaceId)
    }

    private class RecordingTransport(private val response: ProtocolResponse) : ProtocolTransport {
        lateinit var lastRequest: ProtocolRequest
        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            lastRequest = request
            return response
        }

        fun assertRequest(method: String, path: String, query: List<Pair<String, String>>) {
            assertEquals(method, lastRequest.method)
            assertEquals("https://nacos.example", lastRequest.endpoint)
            assertEquals(path, lastRequest.path)
            assertEquals(query, lastRequest.query)
        }
    }

    private companion object {
        const val V1_HISTORY_LIST_JSON = """{"totalCount":2,"pageNumber":1,"pagesAvailable":1,"pageItems":[""" +
            """{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","md5":"abc123","lastModified":1700000000000,"type":"yaml","opType":"PUBLISH"},""" +
            """{"id":"122","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","md5":"def456","lastModified":1699999000000,"type":"yaml","opType":"I"}]}"""

        const val V1_HISTORY_DETAIL_JSON = """{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","content":"enabled: true","md5":"abc123","lastModified":1700000000000,"type":"yaml","opType":"PUBLISH","srcUser":"alice","srcIp":"10.0.0.1"}"""
    }
}
