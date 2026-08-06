@file:OptIn(CacheWriteAccess::class)

package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #122: Namespace summary pagination must preserve the original typed
 * failure instead of collapsing it to a generic connection failure. Driven
 * through the real [NacosApiService] → gateway → V1 adapter with a scripted
 * [NacosRequestExecutor.HttpTransport] seam.
 */
@TestApplication
class TypedNamespaceSummaryFailureTest {

    @Test
    fun `first-page authentication failure retains typed cause on load and index outcome`() =
        runBlocking {
            val transport = ScriptedListTransport(
                responses = listOf(
                    ScriptedListTransport.Response.HttpError(
                        NacosRequestError.Authentication(401, """{"message":"unauthorized"}""")
                    )
                )
            )
            val api = apiWith(transport)
            val cache = CacheService(InMemoryCacheStore())
            cache.clearAll()
            val coordinator = NamespaceIndexCoordinator(api, cache)
            val context = lockedV1Context()

            val load = api.loadNamespace(
                namespaceId = "dev",
                useCache = false,
                operationContext = context
            ).getOrThrow()

            assertEquals(DatasetCompleteness.FAILED, load.completeness)
            assertEquals(0, load.configurations.size)
            assertInstanceOf(RemoteOperationError.Authentication::class.java, load.stoppingCause)

            val outcome = coordinator.requestIndex(
                NamespaceIndexRequest(
                    key = NamespaceIndexKey(context.identity, "dev"),
                    cacheTtlMillis = 60_000L,
                    operationContext = context
                ),
                IndexTrigger.NAMESPACE_SWITCH
            )
            assertInstanceOf(IndexOutcome.Failed::class.java, outcome)
            assertInstanceOf(
                RemoteOperationError.Authentication::class.java,
                (outcome as IndexOutcome.Failed).error
            )
        }

    @Test
    fun `later-page authorization failure retains partial rows and typed stopping cause`() =
        runBlocking {
            val page1Body = summaryPageJson(
                totalCount = 150,
                pageNumber = 1,
                pagesAvailable = 2,
                itemCount = 100
            )
            val transport = ScriptedListTransport(
                responses = listOf(
                    ScriptedListTransport.Response.Body(page1Body),
                    ScriptedListTransport.Response.HttpError(
                        NacosRequestError.Authentication(
                            403,
                            """{"code":"403","message":"permission denied"}"""
                        )
                    )
                )
            )
            val api = apiWith(transport)
            val cache = CacheService(InMemoryCacheStore())
            cache.clearAll()
            val coordinator = NamespaceIndexCoordinator(api, cache)
            val context = lockedV1Context()

            val load = api.loadNamespace(
                namespaceId = "dev",
                useCache = false,
                operationContext = context
            ).getOrThrow()

            assertEquals(DatasetCompleteness.PARTIAL, load.completeness)
            assertEquals(150, load.expectedCount)
            assertEquals(100, load.configurations.size)
            assertInstanceOf(RemoteOperationError.Authorization::class.java, load.stoppingCause)

            val outcome = coordinator.requestIndex(
                NamespaceIndexRequest(
                    key = NamespaceIndexKey(context.identity, "dev"),
                    cacheTtlMillis = 60_000L,
                    operationContext = context
                ),
                IndexTrigger.NAMESPACE_SWITCH
            )
            assertInstanceOf(IndexOutcome.Partial::class.java, outcome)
            val partial = outcome as IndexOutcome.Partial
            assertEquals(100, partial.loaded)
            assertEquals(150, partial.expected)
            assertInstanceOf(RemoteOperationError.Authorization::class.java, partial.stoppingCause)
        }

    @Test
    fun `first-page connection failure retains typed connection cause not generic message only`() =
        runBlocking {
            val transport = ScriptedListTransport(
                responses = listOf(
                    ScriptedListTransport.Response.HttpError(
                        NacosRequestError.Connection(RuntimeException("dns down"))
                    )
                )
            )
            val api = apiWith(transport)
            val context = lockedV1Context()

            val load = api.loadNamespace(
                namespaceId = "dev",
                useCache = false,
                operationContext = context
            ).getOrThrow()

            assertEquals(DatasetCompleteness.FAILED, load.completeness)
            assertInstanceOf(RemoteOperationError.Connection::class.java, load.stoppingCause)
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
                profileId = "typed-fail-profile",
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
            """{"dataId":"cfg-$i.properties","group":"DEFAULT_GROUP","content":null,"type":"properties","tenant":"dev"}"""
        }
        return """{"totalCount":$totalCount,"pageNumber":$pageNumber,"pagesAvailable":$pagesAvailable,"pageItems":[$items]}"""
    }

    /**
     * Scripted list transport: successive V1 config-list calls consume the
     * response queue. Non-list URLs get a harmless empty success so probes do
     * not interfere with locked V1.
     */
    private class ScriptedListTransport(
        private val responses: List<Response>
    ) : NacosRequestExecutor.HttpTransport {
        private val index = AtomicInteger(0)

        sealed class Response {
            data class Body(val body: String) : Response()
            data class HttpError(val error: NacosRequestError) : Response()
        }

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            if (!request.url.contains("/v1/cs/configs")) {
                // Locked V1 should not probe, but keep the seam safe.
                return """{"totalCount":0,"pageNumber":1,"pagesAvailable":0,"pageItems":[]}"""
            }
            val i = index.getAndIncrement()
            require(i < responses.size) { "unexpected list call #$i for ${request.url}" }
            return when (val response = responses[i]) {
                is Response.Body -> response.body
                is Response.HttpError -> throw response.error
            }
        }

        override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
    }
}
