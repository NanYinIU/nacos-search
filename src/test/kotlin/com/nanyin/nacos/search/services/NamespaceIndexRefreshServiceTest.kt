package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.OperationContextResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NamespaceIndexRefreshServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `missing namespace index requests a non-blocking PSI refresh off the EDT`() = runBlocking {
        val observed = CompletableDeferred<Pair<NamespaceIndexRequest, IndexTrigger>>()
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                observed.complete(request to trigger)
                return IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("offline")))
            }
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        val testProfileId = "s_refresh"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            // Flat fields are not runtime sources after migration (#104). Publish
            // through applyServers so captureNamespaceIndexRequest (off-EDT) reads
            // the BASIC profile and staged credential, not the ANONYMOUS seed.
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(
                listOf(
                    NacosServerConfig(
                        id = testProfileId,
                        displayName = "Refresh Test",
                        serverUrl = "http://localhost:8848",
                        username = "admin",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    )
                ),
                testProfileId
            )

            val profile = requireNotNull(settings.getProfile(testProfileId)) {
                "expected published profile $testProfileId"
            }
            val identity = OperationContextResolver.identityFromProfile(profile)
            service.requestIfNeeded(identity, "dev", project = null)

            val (actualRequest, trigger) = withTimeout(2_000L) { observed.await() }
            assertEquals(IndexTrigger.PSI, trigger)
            assertEquals("dev", actualRequest.key.namespaceId)
            assertEquals("http://localhost:8848", actualRequest.key.identity.canonicalEndpoint)
            assertEquals(AuthMode.BASIC, actualRequest.key.identity.authMode)
            assertEquals("admin", actualRequest.key.identity.principal)
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
        }
    }

    private fun clearProfileTombstones(profileIds: List<String>) {
        val tombstones = ApplicationManager.getApplication()
            .getService(ProfileTombstoneRegistry::class.java)
        profileIds.forEach { tombstones.clear(it) }
    }
}
