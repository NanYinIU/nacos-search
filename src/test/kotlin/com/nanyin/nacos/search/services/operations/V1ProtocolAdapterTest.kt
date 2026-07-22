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
import org.junit.jupiter.api.Test

class V1ProtocolAdapterTest {

    @Test
    fun `anonymous V1 probe has its own local wire contract`() = runBlocking {
        val fixture = RecordingProtocolTransport(ProtocolResponse(200, "{}"))

        V1ProtocolAdapter(fixture).probe(anonymousPublicTarget()).getOrThrow()

        fixture.assertRequest("GET", "/nacos/v1/console/namespaces", emptyList())
        assertEquals("application/json", fixture.lastRequest.headers["Accept"])
        assertFalse(fixture.lastRequest.headers.containsKey("Authorization"))
    }

    @Test
    fun `V1 maps protocol status failures to typed remote errors`() = runBlocking {
        val fixture = RecordingProtocolTransport(ProtocolResponse(401, "not authorized"))

        val error = V1ProtocolAdapter(fixture)
            .listSummaries(anonymousPublicTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authentication::class.java, error)
        Unit
    }

    @Test
    fun `anonymous public namespace summary request has the locked V1 wire contract`() = runBlocking {
        val fixture = RecordingProtocolTransport(
            ProtocolResponse(
                200,
                """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","content":null,"type":"yaml","tenant":""}]}"""
            )
        )
        val adapter = V1ProtocolAdapter(fixture)

        val page = adapter.listSummaries(anonymousPublicTarget(), SummaryQuery(pageSize = 50)).getOrThrow()

        assertEquals(1, page.totalCount)
        assertEquals("app.yaml", page.items.single().dataId)
        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v1/cs/configs",
            query = listOf(
                "pageNo" to "1",
                "pageSize" to "50",
                "dataId" to "",
                "group" to "",
                "appName" to "",
                "config_tags" to "",
                "search" to "accurate"
            )
        )
        assertEquals("application/json", fixture.lastRequest.headers["Accept"])
        assertFalse(fixture.lastRequest.headers.containsKey("Authorization"))
        assertFalse(fixture.lastRequest.query.any { it.first == "tenant" })
    }

    @Test
    fun `anonymous public namespace detail request parses the V1 detail contract`() = runBlocking {
        val fixture = RecordingProtocolTransport(
            ProtocolResponse(
                200,
                """{"dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"","content":"enabled: true","type":"yaml","md5":"abc"}"""
            )
        )
        val adapter = V1ProtocolAdapter(fixture)

        val detail = adapter.readDetail(
            anonymousPublicTarget(),
            ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")
        ).getOrThrow()

        requireNotNull(detail)
        assertEquals("enabled: true", detail.content)
        assertNull(detail.tenantId)
        fixture.assertRequest(
            method = "GET",
            path = "/nacos/v1/cs/configs",
            query = listOf(
                "dataId" to "app.yaml",
                "group" to "DEFAULT_GROUP",
                "show" to "all"
            )
        )
    }

    @Test
    fun `manual Namespace encoding is decided by the V1 adapter`() = runBlocking {
        val fixture = RecordingProtocolTransport(
            ProtocolResponse(200, """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}""")
        )
        val target = anonymousPublicTarget(" team-manual ")

        V1ProtocolAdapter(fixture)
            .listSummaries(target, SummaryQuery())
            .getOrThrow()

        assertEquals(" team-manual ", target.namespaceId)
        assertEquals("team-manual", fixture.lastRequest.query.single { it.first == "tenant" }.second)
    }

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

    private class RecordingProtocolTransport(
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
}
