@file:OptIn(CacheWriteAccess::class, SearchRequestAssembly::class)

package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.ui.BlockedReason
import com.nanyin.nacos.search.ui.ConfigListPresentation
import com.nanyin.nacos.search.ui.ConfigListViewState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * End-to-end issue #122: list-page and local-index search apply the same
 * [StaleSearchFallbackPolicy] against failures produced by the real protocol
 * stack (scripted [NacosRequestExecutor.HttpTransport] → adapter → gateway).
 */
@TestApplication
class StaleSearchFallbackIntegrationTest {

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

    @ParameterizedTest(name = "list-page allows stale for {0}")
    @MethodSource("eligibleHttpFailures")
    fun `list-page search falls back to stale cache with REFRESH_FAILED`(
        label: String,
        error: NacosRequestError
    ) = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        val requestKey = listPageCacheKey()
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = requestKey,
            response = cachedListPage("cached.properties")
        )

        val api = apiWith(
            ScriptedFailureTransport(error)
        )
        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespace = namespace,
                pageNo = 1,
                pageSize = 10,
                forceRefresh = true,
                operationContext = context,
                serverId = context.identity.profileId
            ),
            api
        )

        val state = service.searchState.value
        assertInstanceOf(
            NacosSearchService.SearchState.Success::class.java,
            state,
            "expected stale success for $label, got $state"
        )
        val success = state as NacosSearchService.SearchState.Success
        assertEquals(NacosSearchService.SearchSource.STALE_CACHE, success.source)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, success.confidence.confirmation)
        assertEquals("cached.properties", success.configurations.single().dataId)
        // Age/source/confirmation remain independent dimensions (ADR-0036):
        // refresh-failed confirmation does not rewrite age or invent REMOTE source.
        assertEquals(com.nanyin.nacos.search.models.DataSource.CACHE, success.confidence.source)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, success.confidence.confirmation)
        assertTrue(success.confidence.fetchedAtMillis != null)
    }

    @ParameterizedTest(name = "list-page refuses stale for {0}")
    @MethodSource("terminalHttpFailures")
    fun `list-page search refuses stale cache for terminal failures`(
        label: String,
        error: NacosRequestError,
        expectedRemote: Class<out RemoteOperationError>
    ) = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = listPageCacheKey(),
            response = cachedListPage("must-not-show.properties")
        )

        val api = apiWith(ScriptedFailureTransport(error))
        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                namespace = namespace,
                forceRefresh = true,
                operationContext = context,
                serverId = context.identity.profileId
            ),
            api
        )

        val state = service.searchState.value
        assertInstanceOf(
            NacosSearchService.SearchState.Error::class.java,
            state,
            "expected structured failure for $label, got $state"
        )
        val err = state as NacosSearchService.SearchState.Error
        assertInstanceOf(expectedRemote, err.throwable, "throwable was ${err.throwable}")

        val presentation = ConfigListPresentation.fromSearchState(state)
        if (expectedRemote == RemoteOperationError.Authentication::class.java ||
            expectedRemote == RemoteOperationError.Authorization::class.java
        ) {
            assertInstanceOf(ConfigListViewState.Blocked::class.java, presentation)
            assertEquals(
                BlockedReason.REFUSED_ACCESS,
                (presentation as ConfigListViewState.Blocked).reason
            )
        }
    }

    @Test
    fun `local-index search refuses stale cache on authentication failure`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        cache.replaceNamespaceIndex(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            summaries = listOf(
                NacosConfiguration("secret.properties", "DEFAULT_GROUP", "", "k=v", "properties")
            ),
            ttl = 1L // expired so only allowStale path can see it
        )
        // Ensure the entry is treated as expired for non-stale reads.
        Thread.sleep(5)

        val api = apiWith(
            ScriptedFailureTransport(NacosRequestError.Authentication(401, "unauthorized"))
        )
        // Local-index search loads through the coordinator, so the scripted API
        // must back that coordinator — the application service still uses the
        // real transport seam.
        val service = NacosSearchService(
            indexRequester = NamespaceIndexCoordinator(api, cache),
            apiProvider = { api }
        )
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "*",
                namespace = namespace,
                forceRefresh = true,
                operationContext = context,
                serverId = context.identity.profileId
            ),
            api
        )

        val state = service.searchState.value
        assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
        assertInstanceOf(
            RemoteOperationError.Authentication::class.java,
            (state as NacosSearchService.SearchState.Error).throwable
        )
        val presentation = ConfigListPresentation.fromSearchState(state)
        assertInstanceOf(ConfigListViewState.Blocked::class.java, presentation)
        assertEquals(
            BlockedReason.REFUSED_ACCESS,
            (presentation as ConfigListViewState.Blocked).reason
        )
    }

    @Test
    fun `local-index search allows stale cache on connection failure with REFRESH_FAILED`() =
        runBlocking {
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val context = settings.captureOperationContext().getOrThrow()
            val namespace = NamespaceInfo.createPublicNamespace()
            cache.replaceNamespaceIndex(
                identity = context.identity,
                namespaceId = namespace.namespaceId,
                summaries = listOf(
                    NacosConfiguration("cached-index.properties", "DEFAULT_GROUP", "", "", "properties")
                ),
                ttl = 1L
            )
            Thread.sleep(5)

            val api = apiWith(
                ScriptedFailureTransport(NacosRequestError.Connection(RuntimeException("offline")))
            )
            val service = NacosSearchService(
                indexRequester = NamespaceIndexCoordinator(api, cache),
                apiProvider = { api }
            )
            service.performSearch(
                NacosSearchService.SearchRequest(
                    dataId = "*",
                    namespace = namespace,
                    forceRefresh = true,
                    operationContext = context,
                    serverId = context.identity.profileId
                ),
                api
            )

            val state = service.searchState.value
            assertInstanceOf(
                NacosSearchService.SearchState.Success::class.java,
                state,
                "expected stale index success, got $state"
            )
            val success = state as NacosSearchService.SearchState.Success
            assertEquals(NacosSearchService.SearchSource.STALE_CACHE, success.source)
            assertEquals(DatasetConfirmation.REFRESH_FAILED, success.confidence.confirmation)
            assertEquals("cached-index.properties", success.configurations.single().dataId)
        }

    private fun apiWith(transport: NacosRequestExecutor.HttpTransport): NacosApiService {
        return NacosApiService(
            executorOverride = NacosRequestExecutor(transport),
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

    private fun cachedListPage(dataId: String): ConfigListResponse =
        ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                ConfigItem(
                    id = "1",
                    dataId = dataId,
                    group = "DEFAULT_GROUP",
                    content = null,
                    type = "properties",
                    tenant = ""
                )
            )
        )

    private class ScriptedFailureTransport(
        private val listError: NacosRequestError
    ) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            if (request.url.contains("/v1/cs/configs") ||
                request.url.contains("/v3/admin/cs/config/list")
            ) {
                throw listError
            }
            // Locked V1 should not hit these; keep probes safe if policy is AUTO.
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

    companion object {
        @JvmStatic
        fun eligibleHttpFailures(): Stream<Array<Any>> = Stream.of(
            arrayOf("connection", NacosRequestError.Connection(RuntimeException("dns"))),
            arrayOf("read-timeout", NacosRequestError.ReadTimeout(RuntimeException("slow"))),
            arrayOf("server-500", NacosRequestError.Server(500, "boom")),
            arrayOf("rate-limit", NacosRequestError.RateLimited(retryAfterMs = 100L)),
            arrayOf("protocol", NacosRequestError.Protocol("malformed"))
        )

        @JvmStatic
        fun terminalHttpFailures(): Stream<Array<Any>> = Stream.of(
            arrayOf(
                "authentication-401",
                NacosRequestError.Authentication(401, "unauthorized"),
                RemoteOperationError.Authentication::class.java
            ),
            arrayOf(
                "authorization-403",
                // V1 maps 403 + permission-shaped envelope to Authorization.
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                ),
                RemoteOperationError.Authorization::class.java
            )
        )
    }
}
