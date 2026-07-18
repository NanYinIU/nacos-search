package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
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
    fun `missing namespace index requests a non-blocking PSI refresh`() = runBlocking {
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
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        val server = NacosServerSnapshot(
            "http://localhost:8848",
            "admin",
            "secret",
            AuthMode.BASIC,
            false
        )
        val request = NamespaceIndexRequest(
            NamespaceIndexKey(
                AccessIdentity.of(server.serverUrl, server.authMode, server.username),
                "dev"
            ),
            server,
            60_000L
        )

        service.requestIfNeeded(request, project = null)

        val (actualRequest, trigger) = withTimeout(1_000L) { observed.await() }
        assertEquals(request, actualRequest)
        assertEquals(IndexTrigger.PSI, trigger)
        service.dispose()
    }
}
