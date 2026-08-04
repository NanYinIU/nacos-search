package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class V1PublishContractTest {

    @Test
    fun `V1 publish sends POST with casMd5 header and form data`() = runBlocking {
        val fixture = QueuedTransport(ProtocolResponse(200, "true"))
        val adapter = V1ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new content", type = "yaml",
            namespaceId = "public", casMd5 = "base-md5"
        )

        val outcome = adapter.publish(anonymousV1Target(), command).getOrThrow()

        assertInstanceOf(PublishOutcome.Written::class.java, outcome)
        val request = fixture.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/nacos/v1/cs/configs", request.path)
        assertEquals("base-md5", request.headers["casMd5"])
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assert(request.body!!.contains("dataId=app.yaml"))
        assert(request.body!!.contains("content=new+content"))
        Unit
    }

    @Test
    fun `V1 CAS false response maps to CasConflict`() = runBlocking {
        val fixture = QueuedTransport(ProtocolResponse(200, "false"))
        val adapter = V1ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "public", casMd5 = "base-md5"
        )

        val outcome = adapter.publish(anonymousV1Target(), command).getOrThrow()

        assertEquals(PublishOutcome.CasConflict, outcome)
    }

    @Test
    fun `V1 publish never retries on write failure`() = runBlocking {
        val fixture = QueuedTransport(ProtocolResponse(500, "server error"))
        val adapter = V1ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "public", casMd5 = "base-md5"
        )

        val error = adapter.publish(anonymousV1Target(), command).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Server::class.java, error)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `V1 publish is never replayed after an invalid token response`() = runBlocking {
        // Production path: typed Authentication error with sanitized body (issue #39).
        val http = SequencingHttpTransport(
            { throw NacosRequestError.Authentication(403, """{"code":403,"message":"token is invalid"}""") },
            // A replaying adapter would consume this by POSTing the same content again.
            { "true" }
        )
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val authenticator = TokenCachingV1Authenticator(listOf("stale-token", "fresh-token"))

        val error = V1ProtocolAdapter(transport, authenticator)
            .publish(passwordTarget(), publishCommand())
            .exceptionOrNull()

        assertEquals(1, http.calls, "publish must reach the wire exactly once")
        assertInstanceOf(RemoteOperationError.InvalidOrExpiredNacosPasswordToken::class.java, error)
    }

    @Test
    fun `V1 publish invalidates the rejected token so the next attempt logs in again`() = runBlocking {
        val http = SequencingHttpTransport(
            { throw NacosRequestError.Authentication(403, """{"code":403,"message":"token is invalid"}""") },
            { "true" }
        )
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val authenticator = TokenCachingV1Authenticator(listOf("stale-token", "fresh-token"))
        val adapter = V1ProtocolAdapter(transport, authenticator)
        val target = passwordTarget()

        adapter.publish(target, publishCommand()).exceptionOrNull()
        adapter.publish(target, publishCommand()).getOrThrow()

        assertEquals(1, authenticator.invalidations)
        assertEquals(2, authenticator.logins, "a rejected token must not be reused by the next write")
        assertEquals(2, http.calls)
    }

    @Test
    fun `V1 publish keeps the token when the server denies permission`() = runBlocking {
        val http = SequencingHttpTransport(
            { throw NacosRequestError.Authentication(403, """{"code":403,"message":"permission denied"}""") }
        )
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val authenticator = TokenCachingV1Authenticator(listOf("valid-token"))

        val error = V1ProtocolAdapter(transport, authenticator)
            .publish(passwordTarget(), publishCommand())
            .exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(0, authenticator.invalidations, "a permission denial says nothing about the token")
    }

    @Test
    fun `V1 publish encodes manual namespace as tenant in form data`() = runBlocking {
        val fixture = QueuedTransport(ProtocolResponse(200, "true"))
        val adapter = V1ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "team-manual", casMd5 = "base-md5"
        )

        adapter.publish(anonymousV1Target("team-manual"), command).getOrThrow()

        assert(fixture.requests.single().body!!.contains("tenant=team-manual"))
        Unit
    }

    private fun publishCommand(): PublishCommand = PublishCommand(
        dataId = "app.yaml", group = "G", content = "new", type = "yaml",
        namespaceId = "public", casMd5 = "base-md5"
    )

    private fun passwordTarget(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "password-v1", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.TOKEN, principal = "alice"
            ),
            endpoint = endpoint, credential = CredentialSnapshot("p@ss&word"),
            authMode = AuthMode.TOKEN, profileRevision = 1, accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, namespaceId)
    }

    private fun anonymousV1Target(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS, principal = "<anonymous>"
            ),
            endpoint = endpoint, credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS, profileRevision = 1, accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, namespaceId)
    }

    private class QueuedTransport(vararg responses: ProtocolResponse) : ProtocolTransport {
        private val queued = ArrayDeque(responses.toList())
        val requests = mutableListOf<ProtocolRequest>()

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            requests += request
            return queued.removeFirst()
        }
    }

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

    /**
     * Models the caching the real authenticator performs: a token is reused until
     * something invalidates it. An adapter that fails to invalidate a rejected
     * token therefore keeps presenting it, which is what these tests detect.
     */
    private class TokenCachingV1Authenticator(tokens: List<String>) : V1Authenticator {
        private val tokenSequence = ArrayDeque(tokens)
        private var cached: String? = null
        var logins = 0
        var invalidations = 0

        override suspend fun accessToken(context: NacosOperationContext): String? {
            cached?.let { return it }
            logins += 1
            return tokenSequence.removeFirst().also { cached = it }
        }

        override fun invalidate(context: NacosOperationContext) {
            invalidations += 1
            cached = null
        }
    }
}
