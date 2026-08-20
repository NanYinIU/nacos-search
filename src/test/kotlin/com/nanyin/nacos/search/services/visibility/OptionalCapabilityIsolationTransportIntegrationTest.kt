@file:OptIn(
    com.nanyin.nacos.search.services.CacheWriteAccess::class,
    com.nanyin.nacos.search.services.SearchRequestAssembly::class
)

package com.nanyin.nacos.search.services.visibility

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.DiagnosticSnapshot
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.PublishCommand
import com.nanyin.nacos.search.services.operations.PublishOutcome
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.writeDetail
import com.nanyin.nacos.search.services.writeListPage
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
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
 * Optional-capability authorization denials (publish, Namespace discovery,
 * history) through the real adapter → protocol core → gateway → visibility →
 * cache stack (issue #125).
 *
 * Every test drives the scripted protocol transport and asserts configuration
 * cache visibility from the public read surfaces — list pages, details,
 * snapshots, aggregate reads, and search — proving a capability denial never
 * hides data the user can still read, and that a matching success clears only
 * its own capability block.
 */
@TestApplication
class OptionalCapabilityIsolationTransportIntegrationTest {

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
    fun `publish authorization denial blocks only publish and leaves list detail snapshot and search visible`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"

        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )
        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration("keep.properties", "DEFAULT_GROUP", namespace, "k=v", "properties")
        )

        // The publish POST is denied; every configuration-read GET succeeds,
        // so any read side effect of the denial is isolated to the publish.
        val api = apiWith(
            ScriptedDenialTransport(
                okBody = okListBody("fresh.properties", namespace)
            )
        )
        val target = api.resolveOperationTarget(context, namespace).getOrThrow()
        val publishResult = api.operationGateway().publish(
            target,
            PublishCommand(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                content = "v=2",
                type = "properties",
                namespaceId = namespace
            )
        )
        assertTrue(publishResult.isFailure, "publish must fail: $publishResult")
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            publishResult.exceptionOrNull(),
            "typed failure was ${publishResult.exceptionOrNull()}"
        )

        // The denial is recorded in its own capability state only.
        val visibility = cache.accessVisibility()
        assertNotNull(
            visibility.publishAuthBlock(context.identity, namespace),
            "publish denial must be recorded as a publish block; blocks=${visibility.publishAuthBlocks().keys}"
        )
        assertEquals(
            AccessVisibilityRecord.PUBLISH,
            visibility.publishAuthBlock(context.identity, namespace)?.capability
        )
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertNull(visibility.discoveryAuthBlock(context.identity))
        assertNull(visibility.historyAuthBlock(context.identity, namespace))

        // Public read surfaces stay visible.
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        assertEquals(
            "k=v",
            cache.getConfigDetail(context.identity, namespace, "keep.properties", "DEFAULT_GROUP")?.content
        )
        assertTrue(
            cache.getAllCachedConfigurations(context.identity).any { it.dataId == "keep.properties" },
            "publish denial must not hide the aggregate cached-configuration read"
        )
        val snap = cache.snapshot(context.identity)
        assertFalse(snap.isAccessBlocked, "publish denial must not block the configuration snapshot")
        assertTrue(
            snap.configurations.any { it.configuration.dataId == "keep.properties" },
            "snapshot must still list the cached configuration"
        )

        // A remote search succeeds and is not attributed to the publish block.
        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(listPageRequest(namespace(namespace), context), api)
        val state = service.searchState.value
        assertInstanceOf(
            NacosSearchService.SearchState.Success::class.java,
            state,
            "search must stay successful, got $state"
        )
        assertEquals(
            "fresh.properties",
            (state as NacosSearchService.SearchState.Success).configurations.single().dataId
        )
        // The successful read never clears the publish block (issue #125):
        // success clears only the matching capability and scope.
        assertNotNull(visibility.publishAuthBlock(context.identity, namespace))
    }

    @Test
    fun `namespace discovery authorization denial leaves manually readable namespaces and cached data visible`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )
        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration("keep.properties", "DEFAULT_GROUP", namespace, "k=v", "properties")
        )

        val api = apiWith(
            ScriptedDenialTransport(
                okBody = okListBody("fresh.properties", namespace),
                deniedPathParts = setOf(NAMESPACES_PATH)
            )
        )
        // Discovery failure must degrade to the manual/public fallback, not fail.
        val namespaces = api.getNamespaces(context).getOrThrow()
        assertTrue(
            namespaces.any { it.isPublicNamespace() },
            "manual public Namespace must remain available; got $namespaces"
        )

        val visibility = cache.accessVisibility()
        assertNotNull(
            visibility.discoveryAuthBlock(context.identity),
            "discovery denial must be recorded as a discovery block; blocks=${visibility.discoveryAuthBlocks().keys}"
        )
        assertEquals(
            AccessVisibilityRecord.NAMESPACE_DISCOVERY,
            visibility.discoveryAuthBlock(context.identity)?.capability
        )
        assertNull(visibility.discoveryAuthBlock(context.identity)?.namespaceId)
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertNull(visibility.publishAuthBlock(context.identity, namespace))

        // The manually readable Namespace's cached configuration data stays visible.
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        val snap = cache.snapshot(context.identity)
        assertFalse(snap.isAccessBlocked)
        assertTrue(snap.configurations.any { it.configuration.dataId == "keep.properties" })
    }

    @Test
    fun `history authorization denial leaves current configuration reads visible`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )
        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration("keep.properties", "DEFAULT_GROUP", namespace, "k=v", "properties")
        )

        val api = apiWith(
            ScriptedDenialTransport(
                okBody = okListBody("fresh.properties", namespace),
                deniedPathParts = setOf(HISTORY_PATH)
            )
        )
        val target = api.resolveOperationTarget(context, namespace).getOrThrow()
        val historyResult = api.listConfigurationHistory(
            target,
            HistoryQuery(
                coordinate = ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"),
                pageNo = 1,
                pageSize = 10
            )
        )
        assertTrue(historyResult.isFailure, "history must fail: $historyResult")
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            historyResult.exceptionOrNull()
        )

        val visibility = cache.accessVisibility()
        assertNotNull(
            visibility.historyAuthBlock(context.identity, namespace),
            "history denial must be recorded as a history block; blocks=${visibility.historyAuthBlocks().keys}"
        )
        assertEquals(
            AccessVisibilityRecord.HISTORY,
            visibility.historyAuthBlock(context.identity, namespace)?.capability
        )
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertNull(visibility.publishAuthBlock(context.identity, namespace))

        // Current configuration reads stay fully visible.
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        assertEquals(
            "k=v",
            cache.getConfigDetail(context.identity, namespace, "keep.properties", "DEFAULT_GROUP")?.content
        )
        assertFalse(cache.snapshot(context.identity).isAccessBlocked)
    }

    @Test
    fun `bare 403 publish denial cannot become an identity-wide auth block on V1`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )
        cache.writeDetail(
            identity = context.identity,
            namespaceId = namespace,
            configuration = NacosConfiguration("keep.properties", "DEFAULT_GROUP", namespace, "k=v", "properties")
        )

        // A reverse proxy / gateway in front of Nacos replies with a Spring-style
        // 403 that carries no Nacos error envelope. The V1 dialect must still
        // classify it as authorization — never as an identity-wide
        // authentication refusal — or a publish denial would hide readable data
        // (issue #125).
        val api = apiWith(
            ScriptedDenialTransport(
                okBody = okListBody("fresh.properties", namespace),
                denialBody = """{"timestamp":"2026-08-06T00:00:00.000+00:00","status":403,"error":"Forbidden","path":"/nacos/v1/cs/configs"}"""
            )
        )
        val publishResult = api.operationGateway().publish(
            api.resolveOperationTarget(context, namespace).getOrThrow(),
            PublishCommand(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                content = "v=2",
                type = "properties",
                namespaceId = namespace
            )
        )
        assertTrue(publishResult.isFailure, "publish must fail: $publishResult")
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            publishResult.exceptionOrNull(),
            "bare 403 must classify as Authorization, got ${publishResult.exceptionOrNull()}"
        )

        val visibility = cache.accessVisibility()
        assertNotNull(visibility.publishAuthBlock(context.identity, namespace))
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        assertEquals(
            "k=v",
            cache.getConfigDetail(context.identity, namespace, "keep.properties", "DEFAULT_GROUP")?.content
        )
        assertFalse(cache.snapshot(context.identity).isAccessBlocked)
    }

    @Test
    fun `matching publish success clears only the publish block via the transport`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )
        val command = PublishCommand(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            content = "v=2",
            type = "properties",
            namespaceId = namespace
        )

        // First: denied publish records the block.
        val failing = apiWith(ScriptedDenialTransport(okBody = okListBody("fresh.properties", namespace)))
        assertTrue(failing.operationGateway().publish(
            failing.resolveOperationTarget(context, namespace).getOrThrow(),
            command
        ).isFailure)
        assertNotNull(cache.accessVisibility().publishAuthBlock(context.identity, namespace))
        assertFalse(cache.accessVisibility().isConfigurationReadBlocked(context.identity, namespace))

        // Second: a successful publish clears only the publish block; the
        // configuration-read surfaces never stopped being visible.
        val succeeding = apiWith(
            ScriptedDenialTransport(okBody = okListBody("fresh.properties", namespace), denyPost = false)
        )
        val published = succeeding.operationGateway().publish(
            succeeding.resolveOperationTarget(context, namespace).getOrThrow(),
            command
        ).getOrThrow()
        assertInstanceOf(PublishOutcome.Written::class.java, published)

        val visibility = cache.accessVisibility()
        assertNull(visibility.publishAuthBlock(context.identity, namespace))
        assertTrue(visibility.publishAuthBlocks().isEmpty())
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
        )
    }

    @Test
    fun `connection diagnostics with an isolated reporter cannot block or unblock production visibility`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = "team-a"
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace,
            requestKey = listPageCacheKey(),
            response = cachedListPage("keep.properties", namespace)
        )

        // Every diagnostic remote stage fails over the scripted transport.
        val api = apiWith(
            ScriptedDenialTransport(
                okBody = okListBody("fresh.properties", namespace),
                deniedPathParts = setOf("https://nacos.example")
            )
        )
        val report = api.diagnoseConnection(
            DiagnosticSnapshot(
                endpoint = "https://nacos.example",
                apiPolicy = "V1",
                authStrategy = "ANONYMOUS",
                principal = "",
                secret = "",
                namespaceId = namespace
            )
        )
        assertFalse(report.connected, "diagnostic must report failure: ${report.summary}")

        // The diagnostic shared no reporter with production: no block formed
        // and the already-readable configuration data stays visible.
        val visibility = cache.accessVisibility()
        assertFalse(visibility.isIdentityAuthBlocked(context.identity))
        assertFalse(visibility.isConfigurationReadBlocked(context.identity, namespace))
        assertTrue(visibility.identityAuthBlocks().isEmpty())
        assertTrue(visibility.namespaceConfigReadBlocks().isEmpty())
        assertTrue(visibility.publishAuthBlocks().isEmpty())
        assertTrue(visibility.discoveryAuthBlocks().isEmpty())
        assertTrue(visibility.historyAuthBlocks().isEmpty())
        assertEquals(
            "keep.properties",
            cache.getListPage(context.identity, namespace, listPageCacheKey())?.pageItems?.single()?.dataId
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
            // The isolated diagnostic stack shares the scripted transport.
            executorOverride = executor,
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

    private fun okListBody(dataId: String, tenant: String) = """
        {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
         "pageItems":[{"dataId":"$dataId","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":"$tenant"}]}
    """.trimIndent()

    companion object {
        /** V1 wire paths used to scope scripted denials per operation. */
        const val CONFIGS_PATH = "/nacos/v1/cs/configs"
        const val NAMESPACES_PATH = "/nacos/v1/console/namespaces"
        const val HISTORY_PATH = "/nacos/v1/cs/history"
    }

    /**
     * Scripted transport: [deniedPathParts] reads (GETs) fail with [denialBody]
     * (defaults to the Nacos permission-denied envelope); the publish POST is
     * also denied unless [denyPost] is disabled, in which case it returns
     * `true` (the V1 publish confirmation body).
     */
    private class ScriptedDenialTransport(
        private val okBody: String,
        private val deniedPathParts: Set<String> = emptySet(),
        private val denyPost: Boolean = true,
        private val denialBody: String = """{"code":"403","message":"permission denied"}"""
    ) : NacosRequestExecutor.HttpTransport {
        private val calls = AtomicInteger(0)
        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            calls.incrementAndGet()
            deniedPathParts.firstOrNull { request.url.contains(it) }?.let { throw denial() }
            return okBody
        }
        override fun post(request: NacosRequestExecutor.TransportRequest): String {
            calls.incrementAndGet()
            if (denyPost) throw denial()
            return "true"
        }
        private fun denial(): NacosRequestError =
            NacosRequestError.Authentication(403, denialBody)
    }
}
