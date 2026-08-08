package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #145: full-namespace pagination must stop at the server-reported page
 * count so a proxy that ignores paging parameters cannot fetch forever.
 */
@TestApplication
class NamespaceLoadPaginationCapTest {

    @Test
    fun `loadNamespace stops at pagesAvailable even when every page is full`() = runBlocking {
        val listCalls = AtomicInteger(0)
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                listCalls.incrementAndGet()
                // Proxy ignores pageNo and always returns a full page with
                // pagesAvailable=1 — without a cap this would never terminate.
                return summaryPageJson(
                    totalCount = 100,
                    pageNumber = 1,
                    pagesAvailable = 1,
                    itemCount = 100
                )
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "dev",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(1, listCalls.get(), "must not re-page past pagesAvailable")
        assertEquals(100, load.configurations.size)
        assertEquals(DatasetCompleteness.COMPLETE, load.completeness)
    }

    @Test
    fun `loadNamespace respects totalCount when pagesAvailable is inflated`() = runBlocking {
        val listCalls = AtomicInteger(0)
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                val page = listCalls.incrementAndGet()
                // Inflated pagesAvailable with a trusted totalCount — cap must
                // take the tighter bound (2 pages), not chase pagesAvailable.
                return summaryPageJson(
                    totalCount = 150,
                    pageNumber = page,
                    pagesAvailable = 999,
                    itemCount = 100
                )
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "dev",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(2, listCalls.get())
        assertEquals(200, load.configurations.size)
    }

    @Test
    fun `loadNamespace falls back to totalCount when pagesAvailable is zero`() = runBlocking {
        val listCalls = AtomicInteger(0)
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                val page = listCalls.incrementAndGet()
                // Always a full page; pagesAvailable stays 0 so the cap must
                // derive from totalCount (150 → 2 pages at size 100).
                return summaryPageJson(
                    totalCount = 150,
                    pageNumber = page,
                    pagesAvailable = 0,
                    itemCount = 100
                )
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "dev",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(2, listCalls.get())
        assertEquals(200, load.configurations.size)
        // More rows than totalCount because the proxy lied; still bounded.
        assertTrue(load.configurations.size >= 150)
    }

    @Test
    fun `loadNamespace safety-cap stop without totals is Partial not Complete`() = runBlocking {
        val listCalls = AtomicInteger(0)
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                listCalls.incrementAndGet()
                return summaryPageJson(
                    totalCount = 0,
                    pageNumber = 1,
                    pagesAvailable = 0,
                    itemCount = 100
                )
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "dev",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(1, listCalls.get())
        assertEquals(DatasetCompleteness.PARTIAL, load.completeness)
    }

    @Test
    fun `loadNamespace still walks every reported page on a normal server`() = runBlocking {
        val listCalls = AtomicInteger(0)
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                val page = listCalls.incrementAndGet()
                return when (page) {
                    1 -> summaryPageJson(150, 1, 2, 100)
                    2 -> summaryPageJson(150, 2, 2, 50)
                    else -> error("unexpected list call #$page")
                }
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "dev",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(2, listCalls.get())
        assertEquals(150, load.configurations.size)
        assertEquals(DatasetCompleteness.COMPLETE, load.completeness)
    }

    @Test
    fun `loadNamespace stamps the requested Namespace when the response carries none`(
    ) = runBlocking {
        // Search already falls back to the requested Namespace when the list
        // item has no tenant; the namespace-load path must match (issue #191).
        val transport = object : NacosRequestExecutor.HttpTransport {
            override fun get(request: NacosRequestExecutor.TransportRequest): String {
                if (!request.url.contains("/v1/cs/configs")) {
                    return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
                }
                return """{"totalCount":1,"pageNumber":1,"pagesAvailable":1,"pageItems":[{"dataId":"app.yaml","group":"DEFAULT_GROUP","content":null,"type":"yaml"}]}"""
            }

            override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
        }

        val load = apiWith(transport).loadNamespace(
            namespaceId = "team-a",
            useCache = false,
            operationContext = lockedV1Context()
        ).getOrThrow()

        assertEquals(1, load.configurations.size)
        assertEquals("team-a", load.configurations.single().tenantId)
    }

    private fun apiWith(transport: NacosRequestExecutor.HttpTransport): NacosApiService {
        val executor = NacosRequestExecutor(transport)
        return NacosApiService(
            executorOverride = executor,
            sessionGenerationOverride = SessionGenerationState(
                currentEpoch = { 0L },
                isEntombed = { false }
            )
        )
    }

    private fun lockedV1Context(): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "pagination-cap-profile",
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
    }

    private fun summaryPageJson(
        totalCount: Int,
        pageNumber: Int,
        pagesAvailable: Int,
        itemCount: Int
    ): String {
        val items = (1..itemCount).joinToString(",") { i ->
            """{"dataId":"cfg-$pageNumber-$i.properties","group":"DEFAULT_GROUP","content":null,"type":"properties","tenant":"dev"}"""
        }
        return """{"totalCount":$totalCount,"pageNumber":$pageNumber,"pagesAvailable":$pagesAvailable,"pageItems":[$items]}"""
    }
}
