package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class NamespaceServiceTest {
    private lateinit var namespaceService: NamespaceService
    private lateinit var apiService: NacosApiService

    private val testContext = NacosOperationContext(
        identity = AccessIdentity.ofProfile(
            profileId = "test",
            accessRevision = 1,
            canonicalEndpoint = "http://localhost:8848",
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        ),
        endpoint = CanonicalNacosEndpoint.parse("http://localhost:8848").getOrThrow(),
        credential = CredentialSnapshot(""),
        authMode = AuthMode.ANONYMOUS,
        profileRevision = 1,
        accessRevision = 1,
        resolvedGeneration = NacosApiGeneration.V1
    )

    @BeforeEach
    fun setUp() {
        apiService = mock()
        namespaceService = NamespaceService(apiService)
    }

    @Test
    fun `discovery returns selectable options without adopting one`() = runBlocking {
        val options = listOf(
            NamespaceInfo("public", "Public"),
            NamespaceInfo("team-a", "Team A")
        )
        whenever(apiService.getNamespaces(any())).thenReturn(Result.success(options))

        val result = namespaceService.loadNamespacesAsync(testContext).await()

        assertEquals(options, result.getOrThrow())
    }

    @Test
    fun `discovery requires the invoking project operation context`() = runBlocking {
        val result = namespaceService.loadNamespacesAsync().await()

        assertTrue(result.isFailure)
        assertInstanceOf(ConfigurationRequired::class.java, result.exceptionOrNull())
        verifyNoInteractions(apiService)
    }

}
