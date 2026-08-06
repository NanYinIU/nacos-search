package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.AuthenticationSessionRegistry
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour both protocol dialects must share at the generation-neutral
 * [ProtocolAdapter] seam over a scripted [ProtocolTransport]. Concrete suites
 * supply one dialect and its response envelopes. Execution behaviour for
 * probing (issue #97) and configuration browsing (issue #98) — auth recovery,
 * shared budget, cancellation, classified outcomes — runs through [ProtocolCore].
 *
 * Seams under test:
 * - [ProtocolAdapter] over scripted [ProtocolTransport] (request, outcome,
 *   request count — including login)
 * - [AuthenticationSessionRegistry] as the one completed-token store both
 *   dialects must use (issue #96)
 * - [ProtocolCore] execution rules for probe and browsing reads (issues #97 / #98)
 *
 * Contract assertions cover the request that was built, the outcome returned,
 * and the transport request count. Failure diagnostics name only method/path/
 * query keys — never credentials, tokens, or configuration content.
 */
internal abstract class ProtocolDialectContractTest {

    protected abstract val generation: NacosApiGeneration
    protected abstract val expectedCapabilities: ProtocolCapabilities
    protected abstract val summaryPath: String
    protected abstract val detailPath: String
    protected abstract val historyListPath: String
    protected abstract val historyDetailPath: String
    protected abstract val discoveryPath: String
    protected abstract val loginPath: String
    protected abstract val probePath: String

    protected abstract fun newAdapter(
        transport: ProtocolTransport,
        sessions: AuthenticationSessionRegistry = AuthenticationSessionRegistry(),
        budgetMillis: Long = ProtocolCore.DEFAULT_READ_BUDGET_MILLIS,
        clock: () -> Long = System::currentTimeMillis
    ): ProtocolAdapter

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

    /** Successful generation probe body (after optional login). */
    protected abstract fun probeBody(): String

    /** Dialect-shaped login success carrying [token]. */
    protected abstract fun loginBody(token: String): String

    /** Dialect-shaped refused-token response for an idempotent detail read. */
    protected abstract fun refusedTokenDetailResponse(): ProtocolResponse

    /** Dialect-shaped refused-token response for a generation probe. */
    protected abstract fun refusedTokenProbeResponse(): ProtocolResponse

    /** Dialect-shaped permission-denied response for a generation probe. */
    protected abstract fun permissionDeniedProbeResponse(): ProtocolResponse

    /** Dialect-shaped bare authentication failure for a generation probe. */
    protected abstract fun authenticationFailureProbeResponse(): ProtocolResponse

    /** Dialect-shaped protocol failure for a generation probe. */
    protected abstract fun protocolFailureProbeResponse(): ProtocolResponse

    /**
     * Dialect-shaped unsupported-generation probe response, or null when this
     * dialect never classifies generation absence on probe (V1).
     */
    protected abstract fun unsupportedGenerationProbeResponse(): ProtocolResponse?

    /**
     * Assert this dialect's public-Namespace wire encoding on a request that
     * carries a Namespace selector (summary / detail / history-list).
     */
    protected abstract fun assertPublicNamespaceWire(request: ProtocolRequest)

    @Test
    fun `dialect declares graded Namespace-discovery and content-search coverage`() {
        val caps = newAdapter(ScriptedTransport(ProtocolResponse(200, "{}"))).capabilities
        assertEquals(expectedCapabilities, caps)
        assertEquals(CapabilityCoverage.COMPLETE, caps.history)
        assertEquals(CapabilityCoverage.COMPLETE, caps.namespaceDiscovery)
    }

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
            assertBuilt(transport, "GET", summaryPath)
            assertPublicNamespaceWire(transport.requests.single())
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
        assertBuilt(transport, "GET", summaryPath)
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
            assertBuilt(transport, "GET", detailPath)
            assertPublicNamespaceWire(transport.requests.single())
        }
    }

    @Test
    fun `history list collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, historyListBody(rawTenant)))
            val page = newAdapter(transport)
                .listHistory(anonymousTarget(), HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")))
                .getOrThrow()

            assertNull(page.items.single().tenantId) {
                "history-list tenant for ${describe(rawTenant)} on $generation"
            }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(page.items.single().tenantId))
            assertBuilt(transport, "GET", historyListPath)
            assertPublicNamespaceWire(transport.requests.single())
        }
    }

    @Test
    fun `history detail collapses blank whitespace and public tenants to one coordinate`() = runBlocking {
        for (rawTenant in PUBLIC_SPELLINGS) {
            val transport = ScriptedTransport(ProtocolResponse(200, historyDetailBody(rawTenant)))
            val detail = newAdapter(transport)
                .readHistoryDetail(anonymousTarget(), "123")
                .getOrThrow()

            assertNull(detail.tenantId) {
                "history-detail tenant for ${describe(rawTenant)} on $generation"
            }
            assertEquals(NamespaceInfo.PUBLIC, NamespaceInfo.canonicalId(detail.tenantId))
            assertBuilt(transport, "GET", historyDetailPath)
        }
    }

    @Test
    fun `namespace discovery collapses blank whitespace and public ids to public`() = runBlocking {
        for (rawNamespace in PUBLIC_SPELLINGS.filterNotNull()) {
            val transport = ScriptedTransport(ProtocolResponse(200, discoveryBody(rawNamespace)))
            val namespaces = newAdapter(transport)
                .discoverNamespaces(anonymousTarget())
                .getOrThrow()

            assertEquals(NamespaceInfo.PUBLIC, namespaces.single().namespaceId) {
                "discovery id for ${describe(rawNamespace)} on $generation"
            }
            assertBuilt(transport, "GET", discoveryPath)
        }
    }

    @Test
    fun `public Namespace target uses the dialect wire contract on summary detail and history list`() =
        runBlocking {
            for (namespaceId in listOf("public", "", "   ", " public ")) {
                val target = anonymousTarget(namespaceId)

                val summaryTransport = ScriptedTransport(ProtocolResponse(200, summaryBody(null)))
                newAdapter(summaryTransport)
                    .listSummaries(target, SummaryQuery(pageSize = 1))
                    .getOrThrow()
                assertBuilt(summaryTransport, "GET", summaryPath)
                assertPublicNamespaceWire(summaryTransport.requests.single())

                val detailTransport = ScriptedTransport(ProtocolResponse(200, detailBody(null)))
                newAdapter(detailTransport)
                    .readDetail(target, ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
                    .getOrThrow()
                assertBuilt(detailTransport, "GET", detailPath)
                assertPublicNamespaceWire(detailTransport.requests.single())

                val historyTransport = ScriptedTransport(ProtocolResponse(200, historyListBody(null)))
                newAdapter(historyTransport)
                    .listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")))
                    .getOrThrow()
                assertBuilt(historyTransport, "GET", historyListPath)
                assertPublicNamespaceWire(historyTransport.requests.single())
            }
        }

    @Test
    fun `NACOS_PASSWORD probe logs in over the transport then probes once`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("shared-token")),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()

        newAdapter(transport, sessions).probe(passwordTarget()).getOrThrow()

        assertEquals(2, transport.requests.size, requestCountDiagnostic(transport))
        assertEquals("POST", transport.requests[0].method)
        assertEquals(loginPath, transport.requests[0].path)
        assertEquals("GET", transport.requests[1].method)
        assertEquals(probePath, transport.requests[1].path)
        assertTrue(transport.requests[1].query.any { it == "accessToken" to "shared-token" })
        assertEquals(setOf(passwordTarget().context.identity), sessions.trackedIdentities())
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `concurrent cold NACOS_PASSWORD probes share one login flight`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("once")),
            ProtocolResponse(200, probeBody()),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()
        val adapter = newAdapter(transport, sessions)
        val target = passwordTarget()

        val first = async { adapter.probe(target) }
        val second = async { adapter.probe(target) }
        first.await().getOrThrow()
        second.await().getOrThrow()

        val logins = transport.requests.filter { it.path == loginPath }
        assertEquals(1, logins.size, requestCountDiagnostic(transport))
        assertEquals(2, transport.requests.count { it.path == probePath })
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `two adapters sharing one registry reuse one authentication session`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("reuse-me")),
            ProtocolResponse(200, probeBody()),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        newAdapter(transport, sessions).probe(target).getOrThrow()
        newAdapter(transport, sessions).probe(target).getOrThrow()

        assertEquals(1, transport.requests.count { it.path == loginPath }, requestCountDiagnostic(transport))
        assertEquals(2, transport.requests.count { it.path == probePath })
    }

    @Test
    fun `identities that differ in principal never share a completed token`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("alice-token")),
            ProtocolResponse(200, probeBody()),
            ProtocolResponse(200, loginBody("bob-token")),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()
        val adapter = newAdapter(transport, sessions)

        adapter.probe(passwordTarget(principal = "alice")).getOrThrow()
        adapter.probe(passwordTarget(principal = "bob")).getOrThrow()

        assertEquals(2, transport.requests.count { it.path == loginPath }, requestCountDiagnostic(transport))
        assertTrue(transport.requests[1].query.any { it == "accessToken" to "alice-token" })
        assertTrue(transport.requests[3].query.any { it == "accessToken" to "bob-token" })
    }

    @Test
    fun `invalidating the access identity forces the next operation to log in again`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("first")),
            ProtocolResponse(200, probeBody()),
            ProtocolResponse(200, loginBody("second")),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()
        val adapter = newAdapter(transport, sessions)
        val target = passwordTarget()

        adapter.probe(target).getOrThrow()
        sessions.invalidate(target.context.identity)
        adapter.probe(target).getOrThrow()

        assertEquals(2, transport.requests.count { it.path == loginPath }, requestCountDiagnostic(transport))
        assertTrue(transport.requests[3].query.any { it == "accessToken" to "second" })
    }

    @Test
    fun `a refused token is evicted so the next idempotent read logs in again`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("stale")),
            refusedTokenDetailResponse(),
            ProtocolResponse(200, loginBody("fresh")),
            ProtocolResponse(200, detailBody(null))
        )
        val sessions = AuthenticationSessionRegistry()
        val adapter = newAdapter(transport, sessions)
        val target = passwordTarget()

        adapter.readDetail(target, ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")).getOrThrow()

        val logins = transport.requests.filter { it.path == loginPath }
        assertEquals(2, logins.size, requestCountDiagnostic(transport))
        val details = transport.requests.filter { it.path == detailPath }
        assertEquals(2, details.size, requestCountDiagnostic(transport))
        assertTrue(details[1].query.any { it == "accessToken" to "fresh" })
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `NACOS_PASSWORD probe recovers once from a refused token with a fixed request sequence`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("stale")),
            refusedTokenProbeResponse(),
            ProtocolResponse(200, loginBody("fresh")),
            ProtocolResponse(200, probeBody())
        )
        val sessions = AuthenticationSessionRegistry()

        newAdapter(transport, sessions).probe(passwordTarget()).getOrThrow()

        assertEquals(4, transport.requests.size, requestCountDiagnostic(transport))
        assertEquals(loginPath, transport.requests[0].path)
        assertEquals(probePath, transport.requests[1].path)
        assertEquals(loginPath, transport.requests[2].path)
        assertEquals(probePath, transport.requests[3].path)
        assertTrue(transport.requests[3].query.any { it == "accessToken" to "fresh" })
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `permission denial on probe is not recovered and stays Authorization`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("current")),
            permissionDeniedProbeResponse()
        )
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        val error = newAdapter(transport, sessions).probe(target).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(2, transport.requests.size, requestCountDiagnostic(transport))
        assertEquals("current", sessions.completedToken(target.context.identity)?.value)
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `authentication failure on probe is not recovered`() = runBlocking {
        // Anonymous: no NACOS_PASSWORD recovery path. Dialect-shaped 401 must
        // surface as Authentication on both generations (issue #97 parity).
        val transport = ScriptedTransport(authenticationFailureProbeResponse())

        val error = newAdapter(transport).probe(anonymousTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authentication::class.java, error)
        assertEquals(1, transport.requests.size, requestCountDiagnostic(transport))
    }

    @Test
    fun `protocol failure on probe is Protocol and is not retried`() = runBlocking {
        val transport = ScriptedTransport(protocolFailureProbeResponse())
        val error = newAdapter(transport).probe(anonymousTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Protocol::class.java, error)
        assertEquals(1, transport.requests.size, requestCountDiagnostic(transport))
    }

    @Test
    fun `network failure on probe is Connection and is not retried by the core`() = runBlocking {
        val transport = ProtocolTransport {
            throw RemoteOperationError.Connection(RuntimeException("boom"))
        }
        val error = newAdapter(transport).probe(anonymousTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Connection::class.java, error)
    }

    @Test
    fun `unsupported-generation probe outcome is GenerationUnsupported when the dialect classifies it`() =
        runBlocking {
            val response = unsupportedGenerationProbeResponse() ?: return@runBlocking
            val error = newAdapter(ScriptedTransport(response))
                .probe(anonymousTarget())
                .exceptionOrNull()

            assertInstanceOf(RemoteOperationError.GenerationUnsupported::class.java, error)
        }

    @Test
    fun `authentication recovery shares the original request budget rather than resetting it`() =
        runBlocking {
            val clock = java.util.concurrent.atomic.AtomicLong(0L)
            val transport = object : ProtocolTransport {
                val requests = mutableListOf<ProtocolRequest>()
                private val script = ArrayDeque(
                    listOf(
                        ProtocolResponse(200, loginBody("stale")),
                        refusedTokenProbeResponse(),
                        ProtocolResponse(200, loginBody("fresh")),
                        ProtocolResponse(200, probeBody())
                    )
                )

                override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
                    requests += request
                    clock.addAndGet(400)
                    return script.removeFirst()
                }
            }
            // Shared 1000ms budget: four 400ms steps cannot complete without a reset.
            val error = newAdapter(
                transport = transport,
                budgetMillis = 1_000L,
                clock = { clock.get() }
            ).probe(passwordTarget()).exceptionOrNull()

            assertInstanceOf(RemoteOperationError.Protocol::class.java, error)
            assertTrue(
                error!!.message!!.contains("budget", ignoreCase = true),
                "expected budget exhaustion, got: ${error.message}"
            )
            assertTrue(
                transport.requests.size < 4,
                "shared budget must stop recovery before a fourth request; ${transport.requests.size}"
            )
            assertNoSecrets(
                transport.requests.joinToString(prefix = "requests=", separator = "; ") {
                    "${it.method} ${it.path} keys=${it.query.map { q -> q.first }}"
                }
            )
        }

    @Test
    fun `cancellation during probe propagates and is not remapped to connection or protocol`() =
        runBlocking {
            val transport = ProtocolTransport {
                throw kotlinx.coroutines.CancellationException("probe cancelled")
            }
            try {
                newAdapter(transport).probe(anonymousTarget()).getOrThrow()
                org.junit.jupiter.api.Assertions.fail("expected CancellationException")
            } catch (error: kotlinx.coroutines.CancellationException) {
                assertEquals("probe cancelled", error.message)
            }
        }

    @Test
    fun `NACOS_PASSWORD detail recovers once from a refused token with a fixed request sequence`() =
        runBlocking {
            val transport = ScriptedTransport(
                ProtocolResponse(200, loginBody("stale")),
                refusedTokenDetailResponse(),
                ProtocolResponse(200, loginBody("fresh")),
                ProtocolResponse(200, detailBody(null))
            )
            val sessions = AuthenticationSessionRegistry()

            newAdapter(transport, sessions)
                .readDetail(passwordTarget(), ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
                .getOrThrow()

            assertEquals(4, transport.requests.size, requestCountDiagnostic(transport))
            assertEquals(loginPath, transport.requests[0].path)
            assertEquals(detailPath, transport.requests[1].path)
            assertEquals(loginPath, transport.requests[2].path)
            assertEquals(detailPath, transport.requests[3].path)
            assertTrue(transport.requests[3].query.any { it == "accessToken" to "fresh" })
            assertNoSecrets(requestCountDiagnostic(transport))
        }

    @Test
    fun `permission denial on history list is not recovered and stays Authorization`() = runBlocking {
        val transport = ScriptedTransport(
            ProtocolResponse(200, loginBody("current")),
            permissionDeniedProbeResponse()
        )
        val sessions = AuthenticationSessionRegistry()
        val target = passwordTarget()

        val error = newAdapter(transport, sessions)
            .listHistory(target, HistoryQuery(ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP")))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(2, transport.requests.size, requestCountDiagnostic(transport))
        assertEquals(historyListPath, transport.requests[1].path)
        assertEquals("current", sessions.completedToken(target.context.identity)?.value)
        assertNoSecrets(requestCountDiagnostic(transport))
    }

    @Test
    fun `permission denial on namespace discovery is not recovered and stays Authorization`() =
        runBlocking {
            val transport = ScriptedTransport(
                ProtocolResponse(200, loginBody("current")),
                permissionDeniedProbeResponse()
            )
            val sessions = AuthenticationSessionRegistry()
            val target = passwordTarget()

            val error = newAdapter(transport, sessions)
                .discoverNamespaces(target)
                .exceptionOrNull()

            assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
            assertEquals(2, transport.requests.size, requestCountDiagnostic(transport))
            assertEquals(discoveryPath, transport.requests[1].path)
            assertEquals("current", sessions.completedToken(target.context.identity)?.value)
            assertNoSecrets(requestCountDiagnostic(transport))
        }

    @Test
    fun `detail authentication recovery shares the original request budget`() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val transport = object : ProtocolTransport {
            val requests = mutableListOf<ProtocolRequest>()
            private val script = ArrayDeque(
                listOf(
                    ProtocolResponse(200, loginBody("stale")),
                    refusedTokenDetailResponse(),
                    ProtocolResponse(200, loginBody("fresh")),
                    ProtocolResponse(200, detailBody(null))
                )
            )

            override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
                requests += request
                clock.addAndGet(400)
                return script.removeFirst()
            }
        }
        val error = newAdapter(
            transport = transport,
            budgetMillis = 1_000L,
            clock = { clock.get() }
        ).readDetail(passwordTarget(), ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Protocol::class.java, error)
        assertTrue(
            error!!.message!!.contains("budget", ignoreCase = true),
            "expected budget exhaustion, got: ${error.message}"
        )
        assertTrue(
            transport.requests.size < 4,
            "shared budget must stop detail recovery before a fourth request; ${transport.requests.size}"
        )
        assertNoSecrets(
            transport.requests.joinToString(prefix = "requests=", separator = "; ") {
                "${it.method} ${it.path} keys=${it.query.map { q -> q.first }}"
            }
        )
    }

    @Test
    fun `authentication failure on detail is not recovered`() = runBlocking {
        val transport = ScriptedTransport(authenticationFailureProbeResponse())

        val error = newAdapter(transport)
            .readDetail(anonymousTarget(), ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authentication::class.java, error)
        assertEquals(1, transport.requests.size, requestCountDiagnostic(transport))
    }

    @Test
    fun `protocol failure on summary is Protocol and is not retried`() = runBlocking {
        val transport = ScriptedTransport(protocolFailureProbeResponse())
        val error = newAdapter(transport)
            .listSummaries(anonymousTarget(), SummaryQuery(pageSize = 1))
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Protocol::class.java, error)
        assertEquals(1, transport.requests.size, requestCountDiagnostic(transport))
    }

    @Test
    fun `network failure on namespace discovery is Connection and is not retried by the core`() =
        runBlocking {
            val transport = ProtocolTransport {
                throw RemoteOperationError.Connection(RuntimeException("boom"))
            }
            val error = newAdapter(transport).discoverNamespaces(anonymousTarget()).exceptionOrNull()

            assertInstanceOf(RemoteOperationError.Connection::class.java, error)
        }

    @Test
    fun `cancellation during detail propagates and is not remapped to connection or protocol`() =
        runBlocking {
            val transport = ProtocolTransport {
                throw kotlinx.coroutines.CancellationException("detail cancelled")
            }
            try {
                newAdapter(transport)
                    .readDetail(anonymousTarget(), ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))
                    .getOrThrow()
                org.junit.jupiter.api.Assertions.fail("expected CancellationException")
            } catch (error: kotlinx.coroutines.CancellationException) {
                assertEquals("detail cancelled", error.message)
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

    protected fun passwordTarget(
        principal: String = "alice",
        secret: String = "p@ss",
        profileId: String = "dialect-auth"
    ): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = generation,
                authMode = AuthMode.NACOS_PASSWORD,
                principal = principal
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(secret),
            authMode = AuthMode.NACOS_PASSWORD,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = generation
        )
        return OperationTarget(context, "public")
    }

    protected fun tenantJson(rawTenant: String?): String =
        if (rawTenant == null) "null" else "\"$rawTenant\""

    protected class ScriptedTransport(
        private vararg val responses: ProtocolResponse
    ) : ProtocolTransport {
        val requests = mutableListOf<ProtocolRequest>()
        private var index = 0

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            synchronized(this) {
                requests += request
                val response = responses[index.coerceAtMost(responses.lastIndex)]
                index++
                return response
            }
        }
    }

    private fun assertBuilt(transport: ScriptedTransport, method: String, path: String) {
        val diagnostic = requestCountDiagnostic(transport)
        assertEquals(1, transport.requests.size) { diagnostic }
        val request = transport.requests.single()
        assertEquals(method, request.method) { diagnostic }
        assertEquals(path, request.path) { diagnostic }
        // Anonymous contract paths must not attach a token query key.
        assertFalse(diagnostic.contains("accessToken")) { diagnostic }
        assertNoSecrets(diagnostic)
    }

    private fun assertNoSecrets(diagnostic: String) {
        assertFalse(diagnostic.contains("k=v")) { diagnostic }
        assertFalse(diagnostic.contains("p@ss")) { diagnostic }
        assertFalse(diagnostic.contains("bearer-token")) { diagnostic }
        assertFalse(diagnostic.contains("shared-token")) { diagnostic }
        assertFalse(diagnostic.contains("alice-token")) { diagnostic }
        assertFalse(diagnostic.contains("bob-token")) { diagnostic }
        assertFalse(diagnostic.contains("reuse-me")) { diagnostic }
        assertFalse(diagnostic.contains("stale")) { diagnostic }
        assertFalse(diagnostic.contains("fresh")) { diagnostic }
    }

    private fun requestCountDiagnostic(transport: ScriptedTransport): String =
        transport.requests.joinToString(prefix = "requests=", separator = "; ") { request ->
            "${request.method} ${request.path} keys=${request.query.map { it.first }}"
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
    override val expectedCapabilities: ProtocolCapabilities = ProtocolCapabilities.V1
    override val summaryPath: String = "/nacos/v1/cs/configs"
    override val detailPath: String = "/nacos/v1/cs/configs"
    override val historyListPath: String = "/nacos/v1/cs/history"
    override val historyDetailPath: String = "/nacos/v1/cs/history"
    override val discoveryPath: String = "/nacos/v1/console/namespaces"
    override val loginPath: String = "/nacos/v1/auth/login"
    override val probePath: String = "/nacos/v1/console/namespaces"

    override fun newAdapter(
        transport: ProtocolTransport,
        sessions: AuthenticationSessionRegistry,
        budgetMillis: Long,
        clock: () -> Long
    ): ProtocolAdapter = V1ProtocolAdapter(
        transport = transport,
        sessions = sessions,
        readBudgetMillis = budgetMillis,
        clock = clock,
        gson = com.google.gson.Gson()
    )

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

    override fun probeBody(): String =
        """{"data":[{"namespace":"public","namespaceShowName":"public","namespaceDesc":"","configCount":0}]}"""

    override fun loginBody(token: String): String =
        """{"accessToken":"$token","tokenTtl":18000,"globalAdmin":true}"""

    override fun refusedTokenDetailResponse(): ProtocolResponse =
        ProtocolResponse(403, """{"code":403,"message":"token is invalid"}""")

    override fun refusedTokenProbeResponse(): ProtocolResponse =
        ProtocolResponse(403, """{"code":403,"message":"token is invalid"}""")

    override fun permissionDeniedProbeResponse(): ProtocolResponse =
        ProtocolResponse(403, """{"code":403,"message":"permission denied"}""")

    override fun authenticationFailureProbeResponse(): ProtocolResponse =
        ProtocolResponse(401, """{"code":401,"message":"user not found"}""")

    override fun protocolFailureProbeResponse(): ProtocolResponse =
        ProtocolResponse(399, "unexpected status")

    override fun unsupportedGenerationProbeResponse(): ProtocolResponse? = null

    override fun assertPublicNamespaceWire(request: ProtocolRequest) {
        assertFalse(request.query.any { it.first == "tenant" }) {
            "V1 public must omit tenant; keys=${request.query.map { it.first }}"
        }
    }
}

internal class V3ProtocolDialectContractTest : ProtocolDialectContractTest() {
    override val generation: NacosApiGeneration = NacosApiGeneration.V3
    override val expectedCapabilities: ProtocolCapabilities = ProtocolCapabilities.V3
    override val summaryPath: String = "/nacos/v3/admin/cs/config/list"
    override val detailPath: String = "/nacos/v3/admin/cs/config"
    override val historyListPath: String = "/nacos/v3/admin/cs/history/list"
    override val historyDetailPath: String = "/nacos/v3/admin/cs/history"
    override val discoveryPath: String = "/nacos/v3/admin/core/namespace/list"
    override val loginPath: String = "/nacos/v3/auth/user/login"
    override val probePath: String = "/nacos/v3/admin/core/state"

    override fun newAdapter(
        transport: ProtocolTransport,
        sessions: AuthenticationSessionRegistry,
        budgetMillis: Long,
        clock: () -> Long
    ): ProtocolAdapter = V3ProtocolAdapter(
        transport = transport,
        sessions = sessions,
        budgetMillis = budgetMillis,
        clock = clock,
        gson = com.google.gson.Gson()
    )

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

    override fun probeBody(): String =
        """{"functionMode":"All","naming":{},"config":{}}"""

    override fun loginBody(token: String): String =
        """{"accessToken":"$token","tokenTtl":18000}"""

    override fun refusedTokenDetailResponse(): ProtocolResponse =
        ProtocolResponse(401, """{"code":401,"message":"token invalid","data":null}""")

    override fun refusedTokenProbeResponse(): ProtocolResponse =
        ProtocolResponse(401, """{"code":401,"message":"token invalid","data":null}""")

    override fun permissionDeniedProbeResponse(): ProtocolResponse =
        ProtocolResponse(403, """{"code":10001,"message":"access denied","data":null}""")

    override fun authenticationFailureProbeResponse(): ProtocolResponse =
        ProtocolResponse(401, "not a json envelope")

    override fun protocolFailureProbeResponse(): ProtocolResponse =
        ProtocolResponse(600, "unexpected status")

    override fun unsupportedGenerationProbeResponse(): ProtocolResponse =
        ProtocolResponse(404, "")

    override fun assertPublicNamespaceWire(request: ProtocolRequest) {
        assertEquals("public", request.query.single { it.first == "namespaceId" }.second) {
            "V3 public must send namespaceId=public; keys=${request.query.map { it.first }}"
        }
        assertFalse(request.query.any { it.first == "tenant" })
    }
}
