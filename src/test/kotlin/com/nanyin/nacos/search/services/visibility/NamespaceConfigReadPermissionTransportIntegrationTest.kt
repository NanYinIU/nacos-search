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
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.writeDetail
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport-driven Namespace-scoped configuration-read permission blocks through
 * the real adapter → protocol core → gateway → visibility → cache stack
 * (issue #124).
 */
@TestApplication
class NamespaceConfigReadPermissionTransportIntegrationTest {

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
    fun `scripted list authorization failure blocks that namespace and search presentation`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val blockedNs = "team-a"
        val openNs = "team-b"

        cache.writeListPage(
            identity = context.identity,
            namespaceId = blockedNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("must-hide.properties", blockedNs)
        )
        cache.writeListPage(
            identity = context.identity,
            namespaceId = openNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", openNs)
        )
        assertNotNull(cache.getListPage(context.identity, blockedNs, listPageCacheKey()))
        assertNotNull(cache.getListPage(context.identity, openNs, listPageCacheKey()))

        val api = apiWith(
            ScriptedFailureTransport(
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                )
            )
        )
        val listResult = api.listConfigurations(
            namespaceId = blockedNs,
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
            RemoteOperationError.Authorization::class.java,
            listResult.exceptionOrNull(),
            "typed failure was ${listResult.exceptionOrNull()}"
        )
        assertTrue(
            cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs),
            "gateway report must block namespace; blocks=${cache.accessVisibility().namespaceConfigReadBlocks().keys}"
        )
        assertFalse(cache.accessVisibility().isIdentityAuthBlocked(context.identity))
        assertFalse(cache.accessVisibility().isConfigurationReadBlocked(context.identity, openNs))

        // Search over the blocked Namespace renders access refused, not empty.
        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(listPageRequest(namespace(blockedNs), context), api)

        val state = service.searchState.value
        assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
        val presentation = ConfigListPresentation.fromSearchState(state)
        assertInstanceOf(ConfigListViewState.Blocked::class.java, presentation)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (presentation as ConfigListViewState.Blocked).reason
        )

        // Cache surfaces: blocked namespace hidden, open namespace still readable.
        assertNull(cache.getListPage(context.identity, blockedNs, listPageCacheKey()))
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, openNs, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        assertFalse(cache.snapshot(context.identity).isAccessBlocked)
    }

    @Test
    fun `scripted detail authorization failure blocks the same namespace`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"

        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = com.nanyin.nacos.search.models.NacosConfiguration(
                "secret.properties", "DEFAULT_GROUP", namespace, "k=v", "properties"
            )
        )
        assertNotNull(
            cache.getConfigDetail(context.identity, namespace, "secret.properties", "DEFAULT_GROUP")
        )

        val api = apiWith(
            ScriptedFailureTransport(
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"forbidden"}"""
                )
            )
        )
        val detailResult = api.getConfiguration(
            dataId = "secret.properties",
            group = "DEFAULT_GROUP",
            namespaceId = namespace,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(detailResult.isFailure, "detail must fail: $detailResult")
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            detailResult.exceptionOrNull()
        )
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace))
        assertNull(
            cache.getConfigDetail(context.identity, namespace, "secret.properties", "DEFAULT_GROUP")
        )
        // Payload retained under the store.
        assertNotNull(
            cache.accessVisibility().namespaceConfigReadBlock(context.identity, namespace)
        )
    }

    @Test
    fun `matching remote list success clears namespace block and reveals retained cache`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = listPageCacheKey(),
            response = cachedListPage("retained.properties", "")
        )

        val failing = apiWith(
            ScriptedFailureTransport(
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                )
            )
        )
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
        assertTrue(
            cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace.namespaceId)
        )
        assertNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))

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
        assertFalse(
            cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace.namespaceId)
        )
        assertNotNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))
    }

    @Test
    fun `success in another namespace does not clear blocked namespace via transport`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val blockedNs = "team-a"
        val openNs = "team-b"

        cache.writeListPage(
            identity = context.identity,
            namespaceId = blockedNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("hidden.properties", blockedNs)
        )

        // Block team-a.
        apiWith(
            ScriptedFailureTransport(
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                )
            )
        ).listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = blockedNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))

        // Succeed in team-b — must not clear team-a.
        val okBody = """
            {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
             "pageItems":[{"dataId":"other.properties","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":"$openNs"}]}
        """.trimIndent()
        apiWith(ScriptedSuccessTransport(okBody)).listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = openNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))
        assertFalse(cache.accessVisibility().isConfigurationReadBlocked(context.identity, openNs))
        assertNull(cache.getListPage(context.identity, blockedNs, listPageCacheKey()))
    }

    private fun apiWith(
        transport: NacosRequestExecutor.HttpTransport,
        visibility: VisibilityReporter? = null
    ): NacosApiService {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val executor = NacosRequestExecutor(transport)
        val protocolTransport =
            com.nanyin.nacos.search.services.operations.NacosRequestExecutorProtocolTransport(executor)
        val sessions = ApplicationManager.getApplication()
            .getService(com.nanyin.nacos.search.services.NacosAuthService::class.java)
            .authenticationSessions
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

    private fun namespace(id: String) = NamespaceInfo(
        namespaceId = id,
        namespaceName = id,
        namespaceDesc = id
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

    private fun cachedListPage(dataId: String, tenant: String) = ConfigListResponse(
        totalCount = 1,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = listOf(
            ConfigItem("1", dataId, "DEFAULT_GROUP", "k=v", "properties", tenant)
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
}
