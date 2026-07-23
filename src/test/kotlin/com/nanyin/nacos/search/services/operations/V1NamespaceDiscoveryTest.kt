package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class V1NamespaceDiscoveryTest {

    @Test
    fun `V1 discoverNamespaces parses console namespace list`() = runBlocking {
        val fixture = RecordingTransport(
            ProtocolResponse(
                200,
                """{"code":200,"message":null,"data":[{"namespace":"","namespaceShowName":"public","namespaceDesc":"Public"},{"namespace":"team-b","namespaceShowName":"Team B","namespaceDesc":"B","configCount":4}]}"""
            )
        )
        val namespaces = V1ProtocolAdapter(fixture).discoverNamespaces(anonymousTarget()).getOrThrow()
        assertEquals(2, namespaces.size)
        assertEquals("public", namespaces[0].namespaceId)
        assertEquals("team-b", namespaces[1].namespaceId)
        assertEquals(4L, namespaces[1].configCount)
        assertEquals("/nacos/v1/console/namespaces", fixture.lastRequest.path)
    }

    @Test
    fun `gateway discoverNamespaces dispatches to adapter capability`() = runBlocking {
        val adapter = V1ProtocolAdapter(
            RecordingTransport(
                ProtocolResponse(
                    200,
                    """{"data":[{"namespace":"ns-1","namespaceShowName":"One"}]}"""
                )
            )
        )
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val result = gateway.discoverNamespaces(anonymousTarget()).getOrThrow()
        assertEquals(listOf("ns-1"), result.map { it.namespaceId })
    }

    private fun anonymousTarget(): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "v1-ns",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, "public")
    }

    private class RecordingTransport(
        private val response: ProtocolResponse
    ) : ProtocolTransport {
        lateinit var lastRequest: ProtocolRequest

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            lastRequest = request
            return response
        }
    }
}
