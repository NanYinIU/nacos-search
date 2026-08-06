@file:OptIn(CacheWriteAccess::class, SearchRequestAssembly::class)

package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.ui.BlockedReason
import com.nanyin.nacos.search.ui.ConfigListPresentation
import com.nanyin.nacos.search.ui.ConfigListViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

/**
 * End-to-end issue #122: list-page and local-index search apply the same
 * [StaleSearchFallbackPolicy] against failures produced by the real protocol
 * stack (scripted [NacosRequestExecutor.HttpTransport] → adapter → gateway)
 * or typed failures injected at the API seam when the protocol cannot produce them.
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

    // ---- List-page allow / refuse ----

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
        cache.writeListPage(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            requestKey = listPageCacheKey(),
            response = cachedListPage("cached.properties")
        )

        val api = apiWith(ScriptedFailureTransport(error))
        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(listPageRequest(namespace, context), api)

        val success = assertStaleSuccess(service.searchState.value, "list-page $label")
        assertEquals("cached.properties", success.configurations.single().dataId)
        assertRefreshFailedConfidence(success)
        // Allow-path presentation is Results with REFRESH_FAILED, never Blocked.
        val presentation = ConfigListPresentation.fromSearchState(success)
        assertInstanceOf(ConfigListViewState.Results::class.java, presentation)
        val results = presentation as ConfigListViewState.Results
        assertEquals(DatasetConfirmation.REFRESH_FAILED, results.status.confidence.confirmation)
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
        service.performSearch(listPageRequest(namespace, context), api)

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
    fun `list-page eligible failure with empty cache is typed Error not refresh-failed Success`() =
        runBlocking {
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val context = settings.captureOperationContext().getOrThrow()
            val namespace = NamespaceInfo.createPublicNamespace()
            // Cache intentionally empty for this identity.

            val api = apiWith(
                ScriptedFailureTransport(NacosRequestError.Connection(RuntimeException("dns")))
            )
            val service = NacosSearchService(apiProvider = { api })
            service.performSearch(listPageRequest(namespace, context), api)

            val state = service.searchState.value
            assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
            assertInstanceOf(
                RemoteOperationError.Connection::class.java,
                (state as NacosSearchService.SearchState.Error).throwable
            )
            val presentation = ConfigListPresentation.fromSearchState(state)
            assertInstanceOf(ConfigListViewState.Failed::class.java, presentation)
        }

    @Test
    fun `list-page ConfigurationRequired with cache present refuses stale and is blocked`() =
        runBlocking {
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

            val api = mock<NacosApiService>()
            whenever(
                api.listConfigurations(
                    anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.failure(ConfigurationRequired(listOf("A valid Nacos endpoint is required"))))

            val service = NacosSearchService(apiProvider = { api })
            service.performSearch(listPageRequest(namespace, context), api)

            val state = service.searchState.value
            assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
            assertInstanceOf(
                ConfigurationRequired::class.java,
                (state as NacosSearchService.SearchState.Error).throwable
            )
            val presentation = ConfigListPresentation.fromSearchState(state)
            assertInstanceOf(ConfigListViewState.Blocked::class.java, presentation)
            assertEquals(
                BlockedReason.CONFIGURATION_REQUIRED,
                (presentation as ConfigListViewState.Blocked).reason
            )
        }

    @Test
    fun `list-page GenerationUnsupported with cache present refuses stale`() = runBlocking {
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

        val api = mock<NacosApiService>()
        whenever(
            api.listConfigurations(
                anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
            )
        ).thenReturn(Result.failure(RemoteOperationError.GenerationUnsupported("no V3")))

        val service = NacosSearchService(apiProvider = { api })
        service.performSearch(listPageRequest(namespace, context), api)

        val state = service.searchState.value
        assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
        assertInstanceOf(
            RemoteOperationError.GenerationUnsupported::class.java,
            (state as NacosSearchService.SearchState.Error).throwable
        )
        // Not refresh-failed Success and not empty results from cache.
        assertTrue(state !is NacosSearchService.SearchState.Success)
    }

    @Test
    fun `list-page CancellationException is rethrown and does not surface stale Success`() {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        runBlocking {
            cache.writeListPage(
                identity = context.identity,
                namespaceId = namespace.namespaceId,
                requestKey = listPageCacheKey(),
                response = cachedListPage("must-not-show.properties")
            )
        }

        val api = mock<NacosApiService>()
        runBlocking {
            whenever(
                api.listConfigurations(
                    anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.failure(CancellationException("cancelled mid-search")))
        }

        val service = NacosSearchService(apiProvider = { api })
        assertThrows<CancellationException> {
            runBlocking {
                service.performSearch(listPageRequest(namespace, context), api)
            }
        }
        // Must not have published STALE_CACHE Success after packing CE into Result.failure.
        assertTrue(
            service.searchState.value !is NacosSearchService.SearchState.Success,
            "cancel must not surface stale cache: ${service.searchState.value}"
        )
    }

    // ---- Local-index allow / refuse ----

    @ParameterizedTest(name = "local-index allows stale for {0}")
    @MethodSource("eligibleHttpFailures")
    fun `local-index search falls back to stale cache with REFRESH_FAILED`(
        label: String,
        error: NacosRequestError
    ) = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        // forceRefresh skips fresh index reads; allowStale still returns this entry.
        seedExpiredNamespaceIndex(cache, context, namespace, "cached-index.properties")

        val api = apiWith(ScriptedFailureTransport(error))
        val service = NacosSearchService(
            indexRequester = NamespaceIndexCoordinator(api, cache),
            apiProvider = { api }
        )
        service.performSearch(localIndexRequest(namespace, context), api)

        val success = assertStaleSuccess(service.searchState.value, "local-index $label")
        assertEquals("cached-index.properties", success.configurations.single().dataId)
        assertRefreshFailedConfidence(success)
    }

    @Test
    fun `local-index search refuses stale cache on authentication failure`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        seedExpiredNamespaceIndex(cache, context, namespace, "secret.properties")

        val api = apiWith(
            ScriptedFailureTransport(NacosRequestError.Authentication(401, "unauthorized"))
        )
        val service = NacosSearchService(
            indexRequester = NamespaceIndexCoordinator(api, cache),
            apiProvider = { api }
        )
        service.performSearch(localIndexRequest(namespace, context), api)

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
    fun `local-index Partial Authorization refuses stale and is blocked`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        seedExpiredNamespaceIndex(cache, context, namespace, "seeded-secret.properties")

        val api = apiWith(
            ScriptedMultiPageTransport(
                page1Body = summaryPageJson(totalCount = 150, pageNumber = 1, itemCount = 100),
                page2Error = NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                )
            )
        )
        val service = NacosSearchService(
            indexRequester = NamespaceIndexCoordinator(api, cache),
            apiProvider = { api }
        )
        service.performSearch(localIndexRequest(namespace, context), api)

        val state = service.searchState.value
        assertInstanceOf(
            NacosSearchService.SearchState.Error::class.java,
            state,
            "Partial + Authorization must not fall through to stale Success: $state"
        )
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
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
    fun `local-index Partial Connection falls back to stale cache with REFRESH_FAILED`() =
        runBlocking {
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val context = settings.captureOperationContext().getOrThrow()
            val namespace = NamespaceInfo.createPublicNamespace()
            seedExpiredNamespaceIndex(cache, context, namespace, "partial-seed.properties")

            val api = apiWith(
                ScriptedMultiPageTransport(
                    page1Body = summaryPageJson(totalCount = 150, pageNumber = 1, itemCount = 100),
                    page2Error = NacosRequestError.Connection(RuntimeException("dns mid-page"))
                )
            )
            val service = NacosSearchService(
                indexRequester = NamespaceIndexCoordinator(api, cache),
                apiProvider = { api }
            )
            service.performSearch(localIndexRequest(namespace, context), api)

            val success = assertStaleSuccess(
                service.searchState.value,
                "Partial + Connection"
            )
            assertEquals("partial-seed.properties", success.configurations.single().dataId)
            assertRefreshFailedConfidence(success)
        }

    @Test
    fun `local-index eligible failure with empty cache is typed Error`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        val namespace = NamespaceInfo.createPublicNamespace()
        // No seed.

        val api = apiWith(
            ScriptedFailureTransport(NacosRequestError.Connection(RuntimeException("offline")))
        )
        val service = NacosSearchService(
            indexRequester = NamespaceIndexCoordinator(api, cache),
            apiProvider = { api }
        )
        service.performSearch(localIndexRequest(namespace, context), api)

        val state = service.searchState.value
        assertInstanceOf(NacosSearchService.SearchState.Error::class.java, state)
        assertInstanceOf(
            RemoteOperationError.Connection::class.java,
            (state as NacosSearchService.SearchState.Error).throwable
        )
        assertInstanceOf(
            ConfigListViewState.Failed::class.java,
            ConfigListPresentation.fromSearchState(state)
        )
    }

    // ---- helpers ----

    private fun apiWith(transport: NacosRequestExecutor.HttpTransport): NacosApiService {
        return NacosApiService(
            executorOverride = NacosRequestExecutor(transport),
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

    private fun localIndexRequest(
        namespace: NamespaceInfo,
        context: com.nanyin.nacos.search.settings.NacosOperationContext
    ) = NacosSearchService.SearchRequest(
        dataId = "*",
        namespace = namespace,
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

    /**
     * Seeds a namespace index that only [allowStale] reads will surface under
     * forceRefresh. No Thread.sleep: forceRefresh skips the fresh-TTL path and
     * the stale fallback path always consults allowStale.
     */
    private suspend fun seedExpiredNamespaceIndex(
        cache: CacheService,
        context: com.nanyin.nacos.search.settings.NacosOperationContext,
        namespace: NamespaceInfo,
        dataId: String
    ) {
        cache.replaceNamespaceIndex(
            identity = context.identity,
            namespaceId = namespace.namespaceId,
            summaries = listOf(
                NacosConfiguration(dataId, "DEFAULT_GROUP", "", "k=v", "properties")
            ),
            ttl = 1L
        )
    }

    private fun assertStaleSuccess(
        state: NacosSearchService.SearchState,
        label: String
    ): NacosSearchService.SearchState.Success {
        assertInstanceOf(
            NacosSearchService.SearchState.Success::class.java,
            state,
            "expected stale success for $label, got $state"
        )
        val success = state as NacosSearchService.SearchState.Success
        assertEquals(NacosSearchService.SearchSource.STALE_CACHE, success.source)
        return success
    }

    private fun assertRefreshFailedConfidence(success: NacosSearchService.SearchState.Success) {
        assertEquals(DatasetConfirmation.REFRESH_FAILED, success.confidence.confirmation)
        assertEquals(DataSource.CACHE, success.confidence.source)
        assertTrue(success.confidence.fetchedAtMillis != null)
    }

    private fun summaryPageJson(totalCount: Int, pageNumber: Int, itemCount: Int): String {
        val items = (1..itemCount).joinToString(",") { i ->
            """{"dataId":"cfg-$i.properties","group":"DEFAULT_GROUP","content":null,"type":"properties","tenant":"dev"}"""
        }
        val pagesAvailable = (totalCount + 99) / 100
        return """{"totalCount":$totalCount,"pageNumber":$pageNumber,"pagesAvailable":$pagesAvailable,"pageItems":[$items]}"""
    }

    private class ScriptedFailureTransport(
        private val listError: NacosRequestError
    ) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            if (request.url.contains("/v1/cs/configs") ||
                request.url.contains("/v3/admin/cs/config/list")
            ) {
                throw listError
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

    /**
     * First V1 list page succeeds; second throws [page2Error] so loadNamespace
     * produces PARTIAL with a typed stopping cause (issue #122 AC #2).
     */
    private class ScriptedMultiPageTransport(
        private val page1Body: String,
        private val page2Error: NacosRequestError
    ) : NacosRequestExecutor.HttpTransport {
        private val listCalls = AtomicInteger(0)

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            if (request.url.contains("/v1/cs/configs") ||
                request.url.contains("/v3/admin/cs/config/list")
            ) {
                val n = listCalls.getAndIncrement()
                return when (n) {
                    0 -> page1Body
                    else -> throw page2Error
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

    companion object {
        @JvmStatic
        fun eligibleHttpFailures(): Stream<Array<Any>> = Stream.of(
            arrayOf("connection", NacosRequestError.Connection(RuntimeException("dns"))),
            arrayOf("connect-timeout", NacosRequestError.ConnectTimeout(RuntimeException("connect"))),
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
                NacosRequestError.Authentication(
                    403,
                    """{"code":"403","message":"permission denied"}"""
                ),
                RemoteOperationError.Authorization::class.java
            )
        )
    }
}
