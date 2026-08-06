package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.AuthenticationSessionRegistry
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
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

    @Test
    fun `invalid Nacos password token relogins once and replays the idempotent V1 read`() = runBlocking {
        // Drive the same evidence the production transport carries: a typed
        // Authentication error with a sanitized body (not a hand-built
        // ProtocolResponse the gateway used to empty out — issue #39).
        // Login itself now crosses the same transport (issue #96).
        val http = SequencingHttpTransport(
            { """{"accessToken":"stale","tokenTtl":18000}""" },
            { throw NacosRequestError.Authentication(403, """{"code":403,"message":"token is invalid"}""") },
            { """{"accessToken":"fresh","tokenTtl":18000}""" },
            { """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}""" }
        )
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        val page = V1ProtocolAdapter(transport, sessions)
            .listSummaries(target, SummaryQuery())
            .getOrThrow()

        assertEquals(0, page.totalCount)
        assertEquals(4, http.calls)
        assertEquals("fresh", sessions.completedToken(target.context.identity)?.value)
    }

    @Test
    fun `permission denial does not invalidate or replay a Nacos password read`() = runBlocking {
        val http = SequencingHttpTransport(
            { """{"accessToken":"current","tokenTtl":18000}""" },
            { throw NacosRequestError.Authentication(403, """{"code":403,"message":"permission denied"}""") }
        )
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        val error = V1ProtocolAdapter(transport, sessions)
            .listSummaries(target, SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(2, http.calls)
        assertEquals("current", sessions.completedToken(target.context.identity)?.value)
    }

    @Test
    fun `unmapped gateway token prose does not relogin or replay a Nacos password read`() = runBlocking {
        val transport = QueuedTransport(
            ProtocolResponse(200, """{"accessToken":"current","tokenTtl":18000}"""),
            ProtocolResponse(403, "upstream says token is invalid")
        )
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        val error = V1ProtocolAdapter(transport, sessions)
            .listSummaries(target, SummaryQuery())
            .exceptionOrNull()

        // Unrecognized prose on a 403 is Forbidden, not unauthenticated
        // (issue #125): it must never form an identity-wide authentication
        // block. Recovery behavior (no relogin, no replay) is unchanged.
        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(2, transport.requests.size)
        assertEquals(1, transport.requests.count { it.path == "/nacos/v1/auth/login" })
        assertEquals("current", sessions.completedToken(target.context.identity)?.value)
    }

    @Test
    fun `Basic and Bearer strategies use only their own headers and never replay`() = runBlocking {
        val basicTransport = QueuedTransport(ProtocolResponse(401, "not authorized"))
        val bearerTransport = QueuedTransport(ProtocolResponse(401, "not authorized"))

        val basicError = V1ProtocolAdapter(basicTransport)
            .listSummaries(httpBasicTarget(), SummaryQuery())
            .exceptionOrNull()
        val bearerError = V1ProtocolAdapter(bearerTransport)
            .listSummaries(bearerTarget(), SummaryQuery())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authentication::class.java, basicError)
        assertInstanceOf(RemoteOperationError.Authentication::class.java, bearerError)
        assertEquals("Basic YWxpY2U6cEBzcw==", basicTransport.requests.single().headers["Authorization"])
        assertEquals("Bearer bearer-token", bearerTransport.requests.single().headers["Authorization"])
        assertEquals(0, basicTransport.requests.count { it.path == "/nacos/v1/auth/login" })
        assertEquals(0, bearerTransport.requests.count { it.path == "/nacos/v1/auth/login" })
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

    private fun passwordTarget(namespaceId: String = "public"): OperationTarget {
        return strategyTarget(
            authMode = AuthMode.TOKEN,
            principal = "alice",
            secret = "p@ss&word",
            namespaceId = namespaceId
        )
    }

    private fun httpBasicTarget(): OperationTarget = strategyTarget(
        authMode = AuthMode.HTTP_BASIC,
        principal = "alice",
        secret = "p@ss"
    )

    private fun bearerTarget(): OperationTarget = strategyTarget(
        authMode = AuthMode.BEARER_TOKEN,
        principal = "",
        secret = "bearer-token"
    )

    private fun strategyTarget(
        authMode: AuthMode,
        principal: String,
        secret: String,
        namespaceId: String = "public"
    ): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "password-v1",
                accessRevision = 4,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = authMode,
                principal = principal
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(secret),
            authMode = authMode,
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

    private class QueuedTransport(vararg responses: ProtocolResponse) : ProtocolTransport {
        private val queuedResponses = ArrayDeque(responses.toList())
        val requests = mutableListOf<ProtocolRequest>()

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            requests += request
            return queuedResponses.removeFirst()
        }
    }

    /**
     * Production-shaped HTTP seam: throws [NacosRequestError] the way
     * [DefaultHttpTransport] does, so recovery is exercised through the real
     * [NacosRequestExecutorProtocolTransport] rematerialisation.
     */
    private class SequencingHttpTransport(
        private vararg val steps: () -> String
    ) : NacosRequestExecutor.HttpTransport {
        private val remaining = ArrayDeque(steps.toList())
        var calls = 0

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            calls += 1
            return remaining.removeFirst().invoke()
        }

        override fun post(request: NacosRequestExecutor.TransportRequest): String {
            calls += 1
            return remaining.removeFirst().invoke()
        }
    }
}
