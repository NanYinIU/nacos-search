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

class V3HistoryAdapterTest {

    @Test
    fun `V3 history list sends the locked wire contract and unwraps the envelope`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, V3_HISTORY_LIST_JSON))
        val adapter = V3ProtocolAdapter(fixture)

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
            path = "/nacos/v3/admin/cs/history/list",
            query = listOf(
                "dataId" to "app.yaml",
                "group" to "DEFAULT_GROUP",
                "namespaceId" to "public",
                "pageNo" to "1",
                "pageSize" to "100"
            )
        )
    }

    @Test
    fun `V3 history list with manual namespace encodes namespaceId`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, v3Envelope("""{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""))
        )
        V3ProtocolAdapter(fixture).listHistory(
            anonymousPublicTarget("dev-ns"),
            HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
        ).getOrThrow()

        assertEquals("dev-ns", fixture.lastRequest.query.single { it.first == "namespaceId" }.second)
    }

    @Test
    fun `V3 history list empty result is a valid page with zero items`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, v3Envelope("""{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""))
        )
        val page = V3ProtocolAdapter(fixture)
            .listHistory(anonymousPublicTarget(), HistoryQuery(ConfigurationCoordinate("x", "G")))
            .getOrThrow()

        assertEquals(0, page.totalCount)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `V3 history list maps envelope access-denied code to authorization error`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, """{"code":10001,"message":"access denied","data":null}""")
        )
        val error = V3ProtocolAdapter(fixture)
            .listHistory(anonymousPublicTarget(), HistoryQuery(ConfigurationCoordinate("x", "G")))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        Unit
    }

    @Test
    fun `V3 history detail fetches by nid and unwraps the envelope`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, V3_HISTORY_DETAIL_JSON))
        val adapter = V3ProtocolAdapter(fixture)

        val detail = adapter.readHistoryDetail(anonymousPublicTarget(), "123").getOrThrow()

        assertEquals("123", detail.id)
        assertEquals("app.yaml", detail.dataId)
        assertEquals("enabled: true", detail.content)
        assertEquals("abc123", detail.md5)
        assertEquals(1700000000000L, detail.lastModified)
        assertEquals("PUBLISH", detail.opType)

        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v3/admin/cs/history",
            query = listOf("nid" to "123")
        )
    }

    @Test
    fun `V3 history detail 404 with not-found code maps to not-found error`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(404, """{"code":20004,"message":"not found","data":null}""")
        )
        val error = V3ProtocolAdapter(fixture)
            .readHistoryDetail(anonymousPublicTarget(), "999")
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.NotFound::class.java, error)
        Unit
    }

    @Test
    fun `V3 adapter implements HistoryCapability`() {
        val adapter = V3ProtocolAdapter(RecordingTransport(ProtocolResponse(200, RAW_STATE_MAP)))
        assertInstanceOf(HistoryCapability::class.java, adapter)
    }

    // ---- helpers ----

    private fun v3Envelope(dataJson: String): String =
        """{"code":0,"message":"success","data":$dataJson}"""

    private fun anonymousPublicTarget(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "v3-read",
                accessRevision = 4,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V3,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 4,
            accessRevision = 4,
            resolvedGeneration = NacosApiGeneration.V3
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
        const val RAW_STATE_MAP = """{"functionMode":"All"}"""

        val V3_HISTORY_LIST_JSON = """{"code":0,"message":"success","data":{"totalCount":2,"pageNumber":1,"pagesAvailable":1,"pageItems":[""" +
            """{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","md5":"abc123","lastModified":1700000000000,"type":"yaml","opType":"PUBLISH"},""" +
            """{"id":"122","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","md5":"def456","lastModified":1699999000000,"type":"yaml","opType":"I"}]}}"""

        val V3_HISTORY_DETAIL_JSON = """{"code":0,"message":"success","data":{"id":"123","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","content":"enabled: true","md5":"abc123","lastModified":1700000000000,"type":"yaml","opType":"PUBLISH","srcUser":"alice","srcIp":"10.0.0.1"}}"""
    }
}
