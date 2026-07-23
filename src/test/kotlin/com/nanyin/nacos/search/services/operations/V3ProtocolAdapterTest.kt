package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V3ProtocolAdapterTest {

    @Test
    fun `anonymous V3 state probe sends the locked V3 wire contract`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, RAW_STATE_MAP))

        V3ProtocolAdapter(fixture).probe(anonymousPublicTarget()).getOrThrow()

        fixture.assertRequest("GET", "/nacos/v3/admin/core/state", emptyList())
        assertEquals("application/json", fixture.lastRequest.headers["Accept"])
        assertFalse(fixture.lastRequest.headers.containsKey("Authorization"))
    }

    @Test
    fun `V3 maps JSON envelope code one-zero-zero-zero-one to authorization failure`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(403, """{"code":10001,"message":"access denied","data":null}""")
        )

        val error = V3ProtocolAdapter(fixture)
            .listSummaries(anonymousPublicTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
    }

    @Test
    fun `V3 maps JSON envelope code two-zero-zero-zero-four to not found`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(404, """{"code":20004,"message":"resource not found","data":null}""")
        )

        val error = V3ProtocolAdapter(fixture)
            .readDetail(anonymousPublicTarget(), ConfigurationCoordinate("missing", "G"))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.NotFound::class.java, error)
    }

    @Test
    fun `anonymous public namespace summary request sends namespaceId equals public`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(
                200,
                """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","content":null,"type":"yaml"}]}}"""
            )
        )
        val adapter = V3ProtocolAdapter(fixture)

        val page = adapter.listSummaries(anonymousPublicTarget(), SummaryQuery(pageSize = 50)).getOrThrow()

        assertEquals(1, page.totalCount)
        assertEquals("app.yaml", page.items.single().dataId)
        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v3/admin/cs/config/list",
            query = listOf(
                "pageNo" to "1",
                "pageSize" to "50",
                "dataId" to "",
                "group" to "",
                "appName" to "",
                "config_tags" to "",
                "search" to "accurate",
                "namespaceId" to "public"
            )
        )
        assertFalse(fixture.lastRequest.query.any { it.first == "tenant" })
    }

    @Test
    fun `anonymous public namespace detail request parses the V3 detail contract`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(
                200,
                """{"code":0,"message":"success","data":{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","content":"enabled: true","type":"yaml","md5":"abc","tenant":"public"}}"""
            )
        )
        val adapter = V3ProtocolAdapter(fixture)

        val detail = adapter.readDetail(
            anonymousPublicTarget(),
            ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")
        ).getOrThrow()

        requireNotNull(detail)
        assertEquals("enabled: true", detail.content)
        assertEquals("abc", detail.md5)
        assertNull(detail.tenantId)
        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v3/admin/cs/config",
            query = listOf(
                "dataId" to "app.yaml",
                "group" to "DEFAULT_GROUP",
                "namespaceId" to "public"
            )
        )
    }

    @Test
    fun `manual Namespace encoding uses namespaceId in V3`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(
                200,
                """{"code":0,"message":"success","data":{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}}"""
            )
        )
        val target = anonymousPublicTarget("team-manual")

        V3ProtocolAdapter(fixture)
            .listSummaries(target, SummaryQuery())
            .getOrThrow()

        assertEquals("team-manual", fixture.lastRequest.query.single { it.first == "namespaceId" }.second)
        assertFalse(fixture.lastRequest.query.any { it.first == "tenant" })
    }

    @Test
    fun `V3 probe maps four-zero-four to generation unsupported`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(404, "not found"))

        val error = V3ProtocolAdapter(fixture)
            .probe(anonymousPublicTarget())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.GenerationUnsupported::class.java, error)
    }

    @Test
    fun `V3 probe four-zero-four with access-denied envelope does not trigger generation fallback`() = runBlocking {
        // A V3 server returning 404 with envelope code 10001 (access denied)
        // is still a V3 server. This must NOT become GenerationUnsupported.
        val fixture = RecordingTransport(ProtocolResponse(
            404, """{"code":10001,"message":"access denied","data":null}"""
        ))

        val error = V3ProtocolAdapter(fixture)
            .probe(anonymousPublicTarget())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
    }

    @Test
    fun `V3 probe success accepts a raw state map without envelope`() = runBlocking {
        val fixture = RecordingTransport(ProtocolResponse(200, RAW_STATE_MAP))

        val result = V3ProtocolAdapter(fixture).probe(anonymousPublicTarget())

        assertTrue(result.isSuccess)
    }

    @Test
    fun `V3 content search declares the documented capability`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(
                200,
                """{"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"db.yaml","group":"DB","content":"url: jdbc","type":"yaml"}]}}"""
            )
        )
        val adapter = V3ProtocolAdapter(fixture)

        val page = adapter.listSummaries(
            anonymousPublicTarget(),
            SummaryQuery(dataId = "db", search = "blur")
        ).getOrThrow()

        assertEquals("db.yaml", page.items.single().dataId)
        assertTrue(adapter.declaredCapabilities().contains(V3Capability.CONTENT_SEARCH))
    }

    @Test
    fun `V3 and V1 identities with the same profile cannot be equal`() = runBlocking {
        val v3Identity = AccessIdentity.ofProfile(
            profileId = "shared",
            accessRevision = 1,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = NacosApiGeneration.V3,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        val v1Identity = AccessIdentity.ofProfile(
            profileId = "shared",
            accessRevision = 1,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        assert(v3Identity != v1Identity) { "V3 and V1 identities must differ by generation" }
        Unit
    }

    @Test
    fun `V3 non-zero envelope code on two-hundred maps to error`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(200, """{"code":10001,"message":"forbidden","data":null}""")
        )

        val error = V3ProtocolAdapter(fixture)
            .listSummaries(anonymousPublicTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
    }

    @Test
    fun `V3 server error maps to server failure`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(500, """{"code":500,"message":"internal error","data":null}""")
        )

        val error = V3ProtocolAdapter(fixture)
            .listSummaries(anonymousPublicTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Server::class.java, error)
    }

    @Test
    fun `V3 rate limited maps to rate limited`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(429, """{"code":429,"message":"too many requests","data":null}""")
        )

        val error = V3ProtocolAdapter(fixture)
            .listSummaries(anonymousPublicTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.RateLimited::class.java, error)
    }

    @Test
    fun `V3 detail envelope code two-zero-zero-zero-four returns null instead of throwing`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(404, """{"code":20004,"message":"resource not found","data":null}""")
        )

        val result = V3ProtocolAdapter(fixture)
            .readDetail(anonymousPublicTarget(), ConfigurationCoordinate("missing", "G"))
            .getOrThrow()

        assertNull(result)
    }

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

    private class RecordingTransport(
        private val response: ProtocolResponse
    ) : ProtocolTransport {
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
        const val RAW_STATE_MAP = """{"functionMode":"All","naming":{"...":"..."},"config":{"...":"..."}}"""
    }
}
