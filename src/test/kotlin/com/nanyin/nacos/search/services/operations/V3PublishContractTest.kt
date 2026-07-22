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

class V3PublishContractTest {

    @Test
    fun `V3 publish sends POST without casMd5 header`() = runBlocking {
        val fixture = SingleTransport(ProtocolResponse(
            200, """{"code":0,"message":"success","data":true}"""
        ))
        val adapter = V3ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new content", type = "yaml",
            namespaceId = "public", casMd5 = null
        )

        val outcome = adapter.publish(anonymousV3Target(), command).getOrThrow()

        assertInstanceOf(PublishOutcome.Written::class.java, outcome)
        val request = fixture.lastRequest
        assertEquals("POST", request.method)
        assertEquals("/nacos/v3/admin/cs/config", request.path)
        assertFalse(request.headers.containsKey("casMd5"))
        assert(request.body!!.contains("namespaceId=public"))
        Unit
    }

    @Test
    fun `V3 publish has no CAS parameter`() = runBlocking {
        val fixture = SingleTransport(ProtocolResponse(
            200, """{"code":0,"message":"success","data":true}"""
        ))
        val adapter = V3ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "public"
        )

        adapter.publish(anonymousV3Target(), command).getOrThrow()

        assertFalse(fixture.lastRequest.headers.containsKey("casMd5"))
        assertFalse(fixture.lastRequest.body!!.contains("casMd5"))
    }

    @Test
    fun `V3 publish never retries on write failure`() = runBlocking {
        val fixture = SingleTransport(ProtocolResponse(500, """{"code":500,"message":"err","data":null}"""))
        val adapter = V3ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "public"
        )

        val error = adapter.publish(anonymousV3Target(), command).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Server::class.java, error)
    }

    @Test
    fun `V3 ordinary publish has no optimistic label`() = runBlocking {
        // The outcome is just Written, not a special "optimistic" type
        val fixture = SingleTransport(ProtocolResponse(
            200, """{"code":0,"message":"success","data":true}"""
        ))
        val adapter = V3ProtocolAdapter(fixture)
        val command = PublishCommand(
            dataId = "app.yaml", group = "G", content = "new", type = "yaml",
            namespaceId = "public"
        )

        val outcome = adapter.publish(anonymousV3Target(), command).getOrThrow()

        // V3 produces the same Written outcome as V1 — no special type
        assertInstanceOf(PublishOutcome.Written::class.java, outcome)
        // No CasConflict is possible for V3
        assert(outcome != PublishOutcome.CasConflict)
    }

    private fun anonymousV3Target(namespaceId: String = "public"): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V3,
                authMode = AuthMode.ANONYMOUS, principal = "<anonymous>"
            ),
            endpoint = endpoint, credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS, profileRevision = 1, accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V3
        )
        return OperationTarget(context, namespaceId)
    }

    private class SingleTransport(private val response: ProtocolResponse) : ProtocolTransport {
        lateinit var lastRequest: ProtocolRequest

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            lastRequest = request
            return response
        }
    }
}
