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
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.services.writeListPage
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Release-gate transport-driven observation-ordering races (issue #127 / #48).
 *
 * Both out-of-order races run through the real adapter → protocol core →
 * gateway → visibility → cache stack, with a scripted transport that delays
 * one list request until the newer request has completed:
 *
 * - An older refusal that completes after a newer success must not re-block.
 * - An older success that completes after a newer refusal must not reveal.
 *
 * Each race is exercised for identity-wide authentication decisions and for
 * Namespace-scoped configuration-read authorization decisions.
 */
@TestApplication
class ReleaseGateOrderingRacesTransportIntegrationTest {

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

    // ---- Identity-wide authentication races ----

    @Test
    fun `older identity refusal completing after a newer success cannot re-block`() = runBlocking {
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

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = apiWith(
            ScriptedRaceTransport(
                calls = listOf(
                    ScriptedCall.Failure(NacosRequestError.Authentication(401, "seed")),
                    ScriptedCall.Failure(NacosRequestError.Authentication(401, "slow refusal")),
                    ScriptedCall.Success(okBody("fresh.properties"))
                ),
                delayedCall = 1,
                entered = entered,
                release = release
            )
        )

        // Seed the block through the transport (observation N0).
        val seed = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(seed.isFailure)
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        // The older refusal starts first (observation N1) and stalls in transport.
        val slow = async(Dispatchers.Default) {
            api.listConfigurations(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespaceId = namespace.namespaceId,
                pageNo = 1,
                pageSize = 10,
                useCache = false,
                forceRefresh = true,
                operationContext = context
            )
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "slow refusal never entered the transport" }

        // The newer success (observation N2 > N1) completes first and clears the block.
        val fast = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(fast.isSuccess, "newer success must win: $fast")
        assertFalse(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        // Let the older refusal finish: it must not re-apply the block.
        release.countDown()
        val slowResult = slow.await()
        assertTrue(slowResult.isFailure)
        assertInstanceOf(
            RemoteOperationError.Authentication::class.java,
            slowResult.exceptionOrNull()
        )
        assertFalse(
            cache.accessVisibility().isIdentityAuthBlocked(context.identity),
            "older refusal must not re-block after newer success"
        )
        assertNotNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))
        assertFalse(cache.snapshot(context.identity).isAccessBlocked)
    }

    @Test
    fun `older identity success completing after a newer refusal cannot reveal`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = apiWith(
            ScriptedRaceTransport(
                calls = listOf(
                    ScriptedCall.Failure(NacosRequestError.Authentication(401, "seed")),
                    ScriptedCall.Success(okBody("late-success.properties")),
                    ScriptedCall.Failure(NacosRequestError.Authentication(401, "newer refusal"))
                ),
                delayedCall = 1,
                entered = entered,
                release = release
            )
        )

        // Seed the block through the transport (observation N0).
        val seed = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(seed.isFailure)
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        // The older success starts first (observation N1) and stalls in transport.
        val slow = async(Dispatchers.Default) {
            api.listConfigurations(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespaceId = namespace.namespaceId,
                pageNo = 1,
                pageSize = 10,
                useCache = false,
                forceRefresh = true,
                operationContext = context
            )
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "slow success never entered the transport" }

        // The newer refusal (observation N2 > N1) completes first and re-raises the block.
        val fast = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = namespace.namespaceId,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(fast.isFailure)
        assertTrue(cache.accessVisibility().isIdentityAuthBlocked(context.identity))

        // Let the older success finish: it must not clear the newer block.
        release.countDown()
        val slowResult = slow.await()
        assertTrue(slowResult.isSuccess, "older success itself succeeded: $slowResult")
        assertTrue(
            cache.accessVisibility().isIdentityAuthBlocked(context.identity),
            "older success must not reveal data hidden by a newer refusal"
        )
        assertNull(cache.getListPage(context.identity, namespace.namespaceId, listPageCacheKey()))
        assertTrue(cache.snapshot(context.identity).isAccessBlocked)
    }

    // ---- Namespace-scoped configuration-read authorization races ----

    @Test
    fun `older namespace refusal completing after a newer success cannot re-block`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val blockedNs = "team-a"
        val openNs = "team-b"

        cache.writeListPage(
            identity = context.identity,
            namespaceId = blockedNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("retained.properties", blockedNs)
        )
        cache.writeListPage(
            identity = context.identity,
            namespaceId = openNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("open.properties", openNs)
        )

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = apiWith(
            ScriptedRaceTransport(
                calls = listOf(
                    ScriptedCall.Failure(permissionDenied("seed")),
                    ScriptedCall.Failure(permissionDenied("slow refusal")),
                    ScriptedCall.Success(okBody("fresh.properties", blockedNs))
                ),
                delayedCall = 1,
                entered = entered,
                release = release
            )
        )

        // Seed the Namespace block through the transport (observation N0).
        val seed = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = blockedNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(seed.isFailure)
        assertInstanceOf(RemoteOperationError.Authorization::class.java, seed.exceptionOrNull())
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))

        // The older refusal starts first (observation N1) and stalls in transport.
        val slow = async(Dispatchers.Default) {
            api.listConfigurations(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespaceId = blockedNs,
                pageNo = 1,
                pageSize = 10,
                useCache = false,
                forceRefresh = true,
                operationContext = context
            )
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "slow refusal never entered the transport" }

        // The newer success (observation N2 > N1) completes first and clears the Namespace block.
        val fast = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = blockedNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(fast.isSuccess, "newer success must win: $fast")
        assertFalse(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))

        // Let the older refusal finish: it must not re-apply the Namespace block.
        release.countDown()
        val slowResult = slow.await()
        assertTrue(slowResult.isFailure)
        assertInstanceOf(RemoteOperationError.Authorization::class.java, slowResult.exceptionOrNull())
        assertFalse(
            cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs),
            "older namespace refusal must not re-block after newer success"
        )
        assertNotNull(cache.getListPage(context.identity, blockedNs, listPageCacheKey()))
        // The sibling Namespace stayed visible throughout.
        assertEquals(
            "open.properties",
            cache.getListPage(context.identity, openNs, listPageCacheKey())?.pageItems?.single()?.dataId
        )
    }

    @Test
    fun `older namespace success completing after a newer refusal cannot reveal`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val blockedNs = "team-a"
        val openNs = "team-b"

        cache.writeListPage(
            identity = context.identity,
            namespaceId = openNs,
            requestKey = listPageCacheKey(),
            response = cachedListPage("open.properties", openNs)
        )

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = apiWith(
            ScriptedRaceTransport(
                calls = listOf(
                    ScriptedCall.Failure(permissionDenied("seed")),
                    ScriptedCall.Success(okBody("late-success.properties", blockedNs)),
                    ScriptedCall.Failure(permissionDenied("newer refusal"))
                ),
                delayedCall = 1,
                entered = entered,
                release = release
            )
        )

        // Seed the Namespace block through the transport (observation N0).
        val seed = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = blockedNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(seed.isFailure)
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))

        // The older success starts first (observation N1) and stalls in transport.
        val slow = async(Dispatchers.Default) {
            api.listConfigurations(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespaceId = blockedNs,
                pageNo = 1,
                pageSize = 10,
                useCache = false,
                forceRefresh = true,
                operationContext = context
            )
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "slow success never entered the transport" }

        // The newer refusal (observation N2 > N1) completes first and re-raises the block.
        val fast = api.listConfigurations(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespaceId = blockedNs,
            pageNo = 1,
            pageSize = 10,
            useCache = false,
            forceRefresh = true,
            operationContext = context
        )
        assertTrue(fast.isFailure)
        assertTrue(cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs))

        // Let the older success finish: it must not clear the newer block.
        release.countDown()
        val slowResult = slow.await()
        assertTrue(slowResult.isSuccess, "older success itself succeeded: $slowResult")
        assertTrue(
            cache.accessVisibility().isConfigurationReadBlocked(context.identity, blockedNs),
            "older success must not reveal a namespace hidden by a newer refusal"
        )
        assertNull(cache.getListPage(context.identity, blockedNs, listPageCacheKey()))
        // The sibling Namespace stayed visible throughout.
        assertEquals(
            "open.properties",
            cache.getListPage(context.identity, openNs, listPageCacheKey())?.pageItems?.single()?.dataId
        )
        assertFalse(cache.snapshot(context.identity).isAccessBlocked)
    }

    // ---- helpers ----

    private fun apiWith(transport: NacosRequestExecutor.HttpTransport): NacosApiService {
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

    private fun cachedListPage(dataId: String, tenant: String = "") = ConfigListResponse(
        totalCount = 1,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = listOf(
            ConfigItem("1", dataId, "DEFAULT_GROUP", "k=v", "properties", tenant)
        )
    )

    private fun okBody(dataId: String, tenant: String = "") = """
        {"totalCount":1,"pageNumber":1,"pagesAvailable":1,
         "pageItems":[{"dataId":"$dataId","group":"DEFAULT_GROUP","content":"ok","type":"properties","tenant":"$tenant"}]}
    """.trimIndent()

    /**
     * A transport 403 whose JSON body names a permission denial. The V1
     * adapter classifies this envelope as [RemoteOperationError.Authorization]
     * (the same convention the Namespace-readable-permission transport tests
     * use), so a bare 401 stays [RemoteOperationError.Authentication].
     */
    private fun permissionDenied(detail: String) = NacosRequestError.Authentication(
        403,
        """{"code":"403","message":"permission denied $detail"}"""
    )

    private sealed interface ScriptedCall {
        data class Success(val body: String) : ScriptedCall
        data class Failure(val error: NacosRequestError) : ScriptedCall
    }

    /**
     * Scripted transport for ordering races. The call at [delayedCall] signals
     * [entered] and then blocks on [release] before producing its result, so
     * the test can finish a *newer* request first and control which completion
     * lands last. Non-list URLs serve harmless defaults (never reached by the
     * list path).
     */
    private class ScriptedRaceTransport(
        private val calls: List<ScriptedCall>,
        private val delayedCall: Int,
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : NacosRequestExecutor.HttpTransport {
        private val counter = AtomicInteger(0)

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            if (request.url.contains("/v1/cs/configs") ||
                request.url.contains("/v3/admin/cs/config/list")
            ) {
                val n = counter.getAndIncrement()
                if (n == delayedCall) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "release timed out" }
                }
                return when (val call = calls[n]) {
                    is ScriptedCall.Success -> call.body
                    is ScriptedCall.Failure -> throw call.error
                }
            }
            if (request.url.contains("/v3/admin/core/state")) {
                return """{"version":"3.0.0","status":"UP"}"""
            }
            if (request.url.contains("/v1/console/namespaces")) {
                return """[{"namespace":"","namespaceShowName":"public"}]"""
            }
            throw NacosRequestError.Client(404, "not found: ${request.url}")
        }

        override fun post(request: NacosRequestExecutor.TransportRequest): String = "true"
    }
}
