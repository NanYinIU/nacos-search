@file:OptIn(com.nanyin.nacos.search.services.CacheWriteAccess::class)

package com.nanyin.nacos.search.services.visibility

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.psi.ConfigReferenceStatus
import com.nanyin.nacos.search.psi.NacosKeyIndexService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.QueueingDispatcher
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.writeDetail
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * PSI hot-path tests driven through the scripted protocol transport: the key
 * index is already built, then a configuration-read failure lands a block
 * through the real adapter → protocol core → gateway → visibility stack, and
 * the next resolution decision must stop serving the previously built
 * derivation. A matching remote success then restores navigation to the
 * retained payloads (issue #126).
 */
@TestApplication
class PsiNavigationVisibilityTransportIntegrationTest {

    @BeforeEach
    fun publishAnonymousProfile() {
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            applyProfileIntents(
                listOf(
                    com.nanyin.nacos.search.settings.profileIntentFixture(
                        id = "s_local",
                        displayName = "Local",
                        serverUrl = "https://nacos.example",
                        authMode = AuthMode.ANONYMOUS,
                        apiPolicy = com.nanyin.nacos.search.models.NacosApiPolicy.V1
                    )
                ),
                "s_local"
            )
        }
        runBlocking {
            ApplicationManager.getApplication().getService(CacheService::class.java).clearAll()
        }
    }

    @Test
    fun `scripted detail authorization failure hides an already-built index and recovery restores it`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        cache.awaitLoadCompleted()
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"

        // Seed the detail and build the key index before anything is blocked.
        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration(
                "secret.properties", "DEFAULT_GROUP", namespace, "secret.key=hidden\n", "properties"
            )
        )
        val dispatcher = QueueingDispatcher()
        val indexService = NacosKeyIndexService(dispatcher)
        indexService.ensureIndexBuilt(cache.snapshot(context.identity))
        dispatcher.drain()
        assertTrue(indexService.currentIndex(cache.snapshot(context.identity))!!.definitionsByKey.containsKey("secret.key"))

        // Drive the block through the scripted protocol transport.
        val failing = apiWith(
            ScriptedFailureTransport(
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                )
            )
        )
        val detailResult = failing.getConfiguration(
            dataId = "secret.properties",
            group = "DEFAULT_GROUP",
            namespaceId = namespace,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(detailResult.isFailure, "detail must fail: $detailResult")
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace))
        assertFalse(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        val blocked = cache.snapshot(context.identity)
        assertFalse(blocked.isAccessBlocked)
        assertEquals(setOf(namespace), blocked.blockedNamespaces)

        // The previously built index is not served as a temporary bypass.
        assertNull(indexService.currentIndex(blocked))
        assertTrue(indexService.resolve(blocked, "secret.key").isEmpty())

        // Once the rebuild lands, the blocked reference is undecidable and
        // absence stays unproven.
        dispatcher.drain()
        val rebuilt = indexService.currentIndex(blocked)!!
        assertEquals(setOf(namespace), rebuilt.blockedNamespaces)
        assertFalse(rebuilt.definitionsByKey.containsKey("secret.key"))
        val undecidable = indexService.resolveCurrentState(blocked, "secret.key", activeNamespaceId = namespace)
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, undecidable.status)
        assertTrue(undecidable.visibilityBlocked)
        assertTrue(indexService.isDataIdKnown(blocked, "secret.properties", activeNamespaceId = namespace))

        // Recovery: a newer matching success through the transport clears the
        // block; the retained payload becomes navigable again after the
        // derivation rebuilds.
        val succeeding = apiWith(
            ScriptedListSuccessTransport(
                """
                {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
                 "pageItems":[{"dataId":"secret.properties","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":"$namespace"}]}
                """.trimIndent()
            )
        )
        succeeding.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        ).getOrThrow()
        assertFalse(cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace))

        val recovered = cache.snapshot(context.identity)
        assertFalse(recovered.isAccessBlocked)
        assertEquals(emptySet<String>(), recovered.blockedNamespaces)
        indexService.refreshIndex(recovered)
        assertEquals(1, indexService.resolve(recovered, "secret.key").size)
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(recovered, "secret.key", activeNamespaceId = namespace).status
        )
    }

    @Test
    fun `scripted authentication failure hides the identity and a matching success reveals retained payloads`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        cache.awaitLoadCompleted()
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-b"

        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration(
                "visible.properties", "DEFAULT_GROUP", namespace, "visible.key=1\n", "properties"
            )
        )
        val dispatcher = QueueingDispatcher()
        val indexService = NacosKeyIndexService(dispatcher)
        indexService.ensureIndexBuilt(cache.snapshot(context.identity))
        dispatcher.drain()
        assertTrue(indexService.resolve(cache.snapshot(context.identity), "visible.key").isNotEmpty())

        apiWith(ScriptedFailureTransport(NacosRequestError.Authentication(401, "unauthorized")))
            .getConfiguration(
                dataId = "visible.properties",
                group = "DEFAULT_GROUP",
                namespaceId = namespace,
                useCache = false,
                forceRefresh = true,
                operationContext = context
            )
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        val blocked = cache.snapshot(context.identity)
        assertTrue(blocked.isAccessBlocked)
        // No hits, and the reference is undecidable — not a confident miss.
        assertTrue(indexService.resolve(blocked, "visible.key").isEmpty())
        val resolution = indexService.resolveCurrentState(blocked, "visible.key")
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.visibilityBlocked)
        assertTrue(indexService.isDataIdKnown(blocked, "visible.properties", activeNamespaceId = namespace))

        // The stale index is not served even after the rebuild is queued.
        assertNull(indexService.currentIndex(blocked))
        dispatcher.drain()
        assertTrue(indexService.currentIndex(blocked)!!.accessBlocked)

        // A matching success through the transport clears the block.
        apiWith(
            ScriptedListSuccessTransport(
                """
                {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
                 "pageItems":[{"dataId":"visible.properties","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":"$namespace"}]}
                """.trimIndent()
            )
        ).listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        ).getOrThrow()
        assertFalse(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        val recovered = cache.snapshot(context.identity)
        indexService.refreshIndex(recovered)
        assertEquals(1, indexService.resolve(recovered, "visible.key").size)
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(recovered, "visible.key", activeNamespaceId = namespace).status
        )
    }

    private fun apiWith(
        transport: NacosRequestExecutor.HttpTransport
    ): NacosApiService {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val executor = NacosRequestExecutor(transport)
        val protocolTransport =
            com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport(executor)
        val sessions = ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.services.AuthenticationSessionRegistry::class.java)
        val v1 = com.nanyin.nacos.search.services.operations.V1ProtocolAdapter(protocolTransport, sessions)
        val v3 = com.nanyin.nacos.search.services.operations.V3ProtocolAdapter(protocolTransport, sessions)
        val gateway = com.nanyin.nacos.search.services.operations.OperationGateway(
            adapters = mapOf(
                com.nanyin.nacos.search.models.NacosApiGeneration.V1 to v1,
                com.nanyin.nacos.search.models.NacosApiGeneration.V3 to v3
            ),
            cache = com.nanyin.nacos.search.services.operations.CacheServiceOperationCache(cache) {
                settings.getCacheTtlMillis()
            },
            visibility = cache.visibilityReporter()
        )
        return NacosApiService(
            v1GatewayOverride = gateway,
            sessionGenerationOverride = SessionGenerationState(
                currentEpoch = { 0L },
                isEntombed = { false }
            )
        )
    }

    private class ScriptedFailureTransport(
        private val error: NacosRequestError
    ) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String = throw error
        override fun post(request: NacosRequestExecutor.TransportRequest): String = throw error
    }

    private class ScriptedListSuccessTransport(
        private val body: String
    ) : NacosRequestExecutor.HttpTransport {
        private val calls = AtomicInteger(0)
        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            calls.incrementAndGet()
            return body
        }
        override fun post(request: NacosRequestExecutor.TransportRequest): String = body
    }
}
