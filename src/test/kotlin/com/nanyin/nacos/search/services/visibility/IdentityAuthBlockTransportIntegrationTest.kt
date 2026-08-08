@file:OptIn(
    com.nanyin.nacos.search.services.CacheWriteAccess::class,
    com.nanyin.nacos.search.services.SearchRequestAssembly::class
)

package com.nanyin.nacos.search.services.visibility

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.ProtocolCapability
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.writeListPage
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.ui.BlockedReason
import com.nanyin.nacos.search.ui.ConfigListPresentation
import com.nanyin.nacos.search.ui.ConfigListViewState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport-driven identity-wide authentication blocks through the real
 * adapter → protocol core → gateway → visibility → cache stack (issue #123).
 */
@TestApplication
class IdentityAuthBlockTransportIntegrationTest {

    @BeforeEach
    fun publishAnonymousProfile() {
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            applyServers(
                listOf(
                    NacosServerConfig(
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
    fun `scripted authentication failure blocks cache and search presentation`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = listPageCacheKey(),
            response = cachedListPage("must-hide.properties")
        )
        assertEquals(
            "must-hide.properties",
            cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey())
                ?.pageItems?.single()?.dataId
        )

        val api = apiWith(ScriptedFailureTransport(NacosRequestError.Authentication(401, "unauthorized")))
        val listResult = api.listConfigurations(
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(listResult.isFailure, "list must fail: $listResult")
        assertInstanceOf(
            RemoteOperationError.Authentication::class.java,
            listResult.exceptionOrNull(),
            "typed failure was ${listResult.exceptionOrNull()}"
        )
        assertTrue(
            cache.accessVisibility().isIdentityAuthBlocked(context.identity),
            "gateway report must block identity; blocks=${cache.accessVisibility().identityAuthBlocks().keys}"
        )

        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(listPageRequest(namespace, context), api)

        val state = service.searchState.value
        assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
        val presentation = ConfigListPresentation.fromSearchState(state)
        assertInstanceOf(ConfigListViewState.Blocked::class.java, presentation)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (presentation as ConfigListViewState.Blocked).reason
        )

        // Cache surfaces hide the retained page.
        assertNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))
        assertTrue(cache.snapshot(context.identity).isAccessBlocked)
    }

    @Test
    fun `matching remote success after auth block reveals retained cache`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = listPageCacheKey(),
            response = cachedListPage("retained.properties")
        )

        // First: auth failure creates the block.
        val failing = apiWith(ScriptedFailureTransport(NacosRequestError.Authentication(401, "unauthorized")))
        failing.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))
        assertNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))

        // Second: successful remote list clears the block and rewrites the page.
        val okBody = """
            {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
             "pageItems":[{"dataId":"fresh.properties","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":""}]}
        """.trimIndent()
        val succeeding = apiWith(ScriptedSuccessTransport(okBody))
        val observed = succeeding.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        ).getOrThrow()
        assertEquals("fresh.properties", observed.value.pageItems.single().dataId)
        assertFalse(cache.accessVisibility().isIdentityAuthBlocked(context.identity))
        // Cache is readable again (new remote page).
        assertNotNull(
            cache.getListPage(
                context.identity,
                namespace.namespaceId,
                listOf(
                    "appName=",
                    "config_tags=",
                    "dataId=app.yaml",
                    "group=DEFAULT_GROUP",
                    "pageNo=1",
                    "pageSize=10",
                    "search=accurate"
                ).joinToString("|")
            )
        )
    }

    @Test
    fun `cache hit with no observation does not clear a block`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()

        // Seed via remote success first, then block, then force a path that would
        // read cache — the block must stay (NO_OBSERVATION cannot clear).
        val okBody = """
            {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
             "pageItems":[{"dataId":"seeded.properties","group":"DEFAULT_GROUP","content":"s","type":"properties","tenant":""}]}
        """.trimIndent()
        val seedApi = apiWith(ScriptedSuccessTransport(okBody))
        seedApi.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = true,
            forceRefresh = true,
            operationContext = context
        ).getOrThrow()

        cache.accessVisibility().reportCompleted(
            CompletedObservation(
                // Use the process sequence so this outranks any prior APP-session mark.
                observation = com.nanyin.nacos.search.services.operations.ObservationSequence.process.next(),
                identity = context.identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
            )
        )
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        // useCache=true, forceRefresh=false: cache hit path if not blocked.
        // Under block, cache returns null so remote is attempted — script another auth failure.
        val stillFailing = apiWith(ScriptedFailureTransport(NacosRequestError.Authentication(401, "still bad")))
        stillFailing.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = true,
            forceRefresh = false,
            operationContext = context
        )
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))
        assertNull(
            cache.getListPage(
                context.identity,
                namespace.namespaceId,
                listOf(
                    "appName=",
                    "config_tags=",
                    "dataId=app.yaml",
                    "group=DEFAULT_GROUP",
                    "pageNo=1",
                    "pageSize=10",
                    "search=accurate"
                ).joinToString("|")
            )
        )
    }

    private fun apiWith(
        transport: NacosRequestExecutor.HttpTransport,
        visibility: VisibilityReporter? = null
    ): NacosApiService {
        // Build the formal stack explicitly so gateway visibility is the same
        // AccessVisibility the APP CacheService enforces on reads (issue #123).
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
            visibility = visibility ?: cache.visibilityReporter()
        )
        return NacosApiService(
            v1GatewayOverride = gateway,
            sessionGenerationOverride = SessionGenerationState(
                currentEpoch = { 0L },
                isEntombed = { false }
            )
        )
    }

    private fun listPageRequest(
        namespace: NamespaceInfo,
        context: com.nanyin.nacos.search.settings.NacosOperationContext
    ) = NacosSearchService.SearchRequest(
        dataId = "app.yaml",
        group = "DEFAULT_GROUP",
        namespace = namespace,
        pageNo = 1,
        pageSize = 10,
        forceRefresh = true,
        operationContext = context,
        serverId = context.identity.profileId
    )

    private fun listPageCacheKey(): String =
        listOf(
            "appName=",
            "config_tags=",
            "dataId=app.yaml",
            "group=DEFAULT_GROUP",
            "pageNo=1",
            "pageSize=10",
            "search=accurate"
        ).joinToString("|")

    private fun cachedListPage(dataId: String) = ConfigListResponse(
        totalCount = 1,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = listOf(
            ConfigItem("1", dataId, "DEFAULT_GROUP", "k=v", "properties", "")
        )
    )

    private class ScriptedFailureTransport(
        private val error: NacosRequestError
    ) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String = throw error
        override fun post(request: NacosRequestExecutor.TransportRequest): String = throw error
    }

    private class ScriptedSuccessTransport(
        private val body: String
    ) : NacosRequestExecutor.HttpTransport {
        private val calls = AtomicInteger(0)
        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            calls.incrementAndGet()
            return body
        }
        override fun post(request: NacosRequestExecutor.TransportRequest): String = body
    }

    private fun assertNotNull(value: Any?) {
        org.junit.jupiter.api.Assertions.assertNotNull(value)
    }
}
