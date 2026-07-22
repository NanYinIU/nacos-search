package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.operations.InMemoryOperationCache
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.ProtocolRequest
import com.nanyin.nacos.search.services.operations.ProtocolResponse
import com.nanyin.nacos.search.services.operations.ProtocolTransport
import com.nanyin.nacos.search.services.operations.V1ProtocolAdapter
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.ConfigurationRequired
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

@TestApplication
class NacosApiServiceV1AnonymousTest {

    @Test
    fun `existing browser service reads a manually entered namespace through the locked V1 gateway`() = runBlocking {
        val fixture = V1ReadFixture()
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to V1ProtocolAdapter(fixture)),
            InMemoryOperationCache()
        )
        val api = NacosApiService(gateway)
        val context = anonymousContext()

        val summaries = api.listConfigurations(
            namespaceId = "team-manual",
            pageSize = 25,
            operationContext = context
        ).getOrThrow()
        val detail = api.getConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = "team-manual",
            operationContext = context
        ).getOrThrow()

        assertEquals("team-manual", summaries.pageItems.single().tenant)
        assertEquals("enabled: true", detail?.content)
        assertEquals(listOf("GET", "GET"), fixture.requests.map { it.method })
        assertEquals(listOf("/nacos/v1/cs/configs", "/nacos/v1/cs/configs"), fixture.requests.map { it.path })
        assertEquals("tenant" to "team-manual", fixture.requests[0].query.last())
        assertEquals("tenant" to "team-manual", fixture.requests[1].query.last())
        assertFalse(fixture.requests.any { it.headers.containsKey("Authorization") })
    }

    @Test
    fun `V1 anonymous service refuses publish without constructing a write request`() = runBlocking {
        val api = NacosApiService(OperationGateway(emptyMap(), InMemoryOperationCache()))

        val result = api.publishConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            content = "enabled: true",
            operationContext = anonymousContext()
        )

        assertEquals(ConfigurationRequired::class.java, result.exceptionOrNull()?.javaClass)
    }

    @Test
    fun `V1 authenticated service refuses publish before authentication or transport`() = runBlocking {
        val api = NacosApiService(OperationGateway(emptyMap(), InMemoryOperationCache()))

        val result = api.publishConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            content = "enabled: true",
            operationContext = passwordContext()
        )

        assertEquals(ConfigurationRequired::class.java, result.exceptionOrNull()?.javaClass)
    }

    private fun anonymousContext(): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "anon-v1",
                accessRevision = 3,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 3,
            accessRevision = 3,
            resolvedGeneration = NacosApiGeneration.V1
        )
    }

    private fun passwordContext(): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "password-v1",
                accessRevision = 4,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.NACOS_PASSWORD,
                principal = "alice"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot("secret"),
            authMode = AuthMode.NACOS_PASSWORD,
            profileRevision = 4,
            accessRevision = 4,
            resolvedGeneration = NacosApiGeneration.V1
        )
    }

    private class V1ReadFixture : ProtocolTransport {
        val requests = mutableListOf<ProtocolRequest>()

        override suspend fun execute(request: ProtocolRequest): ProtocolResponse {
            requests += request
            return if (request.query.any { it.first == "pageNo" }) {
                ProtocolResponse(
                    200,
                    """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"id":"1","dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"team-manual","type":"yaml"}]}"""
                )
            } else {
                ProtocolResponse(
                    200,
                    """{"dataId":"app.yaml","group":"DEFAULT_GROUP","tenant":"team-manual","content":"enabled: true","type":"yaml"}"""
                )
            }
        }
    }
}
