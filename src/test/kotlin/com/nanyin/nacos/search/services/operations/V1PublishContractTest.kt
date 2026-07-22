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
}
