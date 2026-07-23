package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
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
        val cacheService = CacheService()
        cacheService.clearAll()
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.settings.NacosSettings::class.java)
        settings.resetToDefaults()
        settings.serverUrl = "http://localhost:8848"
        settings.username = "admin"
        settings.password = "secret"
        settings.authMode = AuthMode.BASIC
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }

        val identity = OperationContextResolver.identityFromProfile(
            EnvironmentProfile.fromLegacy(settings.getActiveServer())
        )
        service.requestIfNeeded(identity, "dev", project = null)

        val (actualRequest, trigger) = withTimeout(2_000L) { observed.await() }
        assertEquals(IndexTrigger.PSI, trigger)
        assertEquals("dev", actualRequest.key.namespaceId)
        assertEquals("http://localhost:8848", actualRequest.key.identity.canonicalEndpoint)
        assertEquals(AuthMode.BASIC, actualRequest.key.identity.authMode)
        assertEquals("admin", actualRequest.key.identity.principal)
        service.dispose()
    }
}
