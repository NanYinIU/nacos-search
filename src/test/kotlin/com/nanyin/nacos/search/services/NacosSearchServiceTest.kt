@file:OptIn(SearchRequestAssembly::class, CacheWriteAccess::class)

package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.NacosSettings
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@TestApplication
class NacosSearchServiceTest {

    @BeforeEach
    fun resetSharedSettingsToAnonymousDefaults() {
        // Publish an ANONYMOUS profile through the write path so capture is pure
        // and does not read flat-field mirrors (issue #104).
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            applyServers(
                listOf(
                    NacosServerConfig(
                        id = "s_local",
                        displayName = "本地 Local",
                        serverUrl = "http://localhost:8848",
                        authMode = AuthMode.ANONYMOUS
                    )
                ),
                "s_local"
            )
        }
    }

    @Test
    fun `search request normalizes wildcard and prefix fuzzy dataId`() {
        val wildcard = NacosSearchService.SearchRequest(dataId = "*")
        assertEquals("", wildcard.getProcessedDataId())
        assertEquals("blur", wildcard.getSearchMode())
        assertTrue(wildcard.requiresLocalIndex())

        val prefix = NacosSearchService.SearchRequest(dataId = "*config")
        assertEquals("config", prefix.getProcessedDataId())
        assertEquals("blur", prefix.getSearchMode())
        assertTrue(prefix.requiresLocalIndex())

        val exact = NacosSearchService.SearchRequest(dataId = "app.yaml", group = "DEFAULT_GROUP")
        assertEquals("app.yaml", exact.getProcessedDataId())
        assertEquals("accurate", exact.getSearchMode())
        assertFalse(exact.requiresLocalIndex())
    }

    @Test
    fun `only searches requiring a local index route to coordinator`() {
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(searchContent = true).fullNamespaceTrigger()
        )
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(dataId = "*config").fullNamespaceTrigger()
        )
        assertEquals(
            null,
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                pageNo = 3
            ).fullNamespaceTrigger()
        )
    }

    @Test
    fun `regex and content-paged searches route to coordinator`() {
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(useRegex = true).fullNamespaceTrigger()
        )
        // Content search takes priority over paging — still routes to coordinator
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(
                searchContent = true,
                pageNo = 3,
                pageSize = 20
            ).fullNamespaceTrigger()
        )
        // Plain wildcard-only search without content or regex also routes
        assertEquals(
            IndexTrigger.SEARCH,
            NacosSearchService.SearchRequest(dataId = "*").fullNamespaceTrigger()
        )
    }

    @Test
    fun `ordinary paged search does not request a namespace index`() = runBlocking {
        val coordinator = mock<NamespaceIndexRequester>()
        val service = NacosSearchService(indexRequester = coordinator)
        val api = stubApi()

        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                pageNo = 2,
                pageSize = 20
            ),
            api
        )

        verify(coordinator, never()).requestIndex(any(), any())
        verify(api, times(1)).listConfigurations(
            anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        Unit
    }

    @Test
    fun `invalid configuration fails closed in the search UI path before cache or API`() = runBlocking {
        val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(NacosSettings::class.java)
        val original = settings.copy()
        try {
            settings.resetToDefaults()
            // Invalid origin (path present) — must fail closed via profile capture.
            settings.applyServers(
                listOf(
                    NacosServerConfig(
                        id = "s_local",
                        displayName = "Bad",
                        serverUrl = "https://nacos.example/not-an-origin",
                        authMode = AuthMode.ANONYMOUS
                    )
                ),
                "s_local"
            )
            val api = mock<NacosApiService>()
            val service = NacosSearchService()

            service.performSearch(
                NacosSearchService.SearchRequest(namespace = NamespaceInfo.createPublicNamespace()),
                api
            )

            val state = service.searchState.value
            assertTrue(state is NacosSearchService.SearchState.Error)
            assertTrue((state as NacosSearchService.SearchState.Error).throwable is ConfigurationRequired)
            verify(api, never()).listConfigurations(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
            )
            Unit
        } finally {
            settings.copyFrom(original)
        }
    }

    @Test
    fun `search request cache key includes filters paging and content options`() = runBlocking {
        val first = NacosSearchService.SearchRequest(
            dataId = "app",
            group = "DEFAULT_GROUP",
            query = "timeout",
            searchContent = true,
            caseSensitive = false,
            useRegex = true,
            pageNo = 2,
            pageSize = 50
        )
        val second = first.copy(pageNo = 3)

        assertEquals(
            "namespace=|dataId=app|group=DEFAULT_GROUP|appName=|configTags=|query=timeout|searchContent=true|caseSensitive=false|useRegex=true|search=accurate|pageNo=2|pageSize=50",
            first.toCacheKey()
        )
        assertTrue(first.toCacheKey() != second.toCacheKey())
    }

    @Test
    fun `search request cache key separates namespaces`() {
        val publicNamespace = NamespaceInfo.createPublicNamespace()
        val testNamespace = NamespaceInfo(namespaceId = "test-01", namespaceName = "test-01")
        val request = NacosSearchService.SearchRequest(
            dataId = "application.properties",
            group = "DEFAULT_GROUP",
            namespace = publicNamespace
        )

        assertTrue(request.toCacheKey() != request.copy(namespace = testNamespace).toCacheKey())
    }

    @Test
    fun `a published result reports the observation sequence its read took`() = runBlocking {
        // The result list judges paintability by that sequence, so a search that
        // reached the server has to carry it out of the service (ADR-0047).
        val service = NacosSearchService()

        service.performSearch(
            NacosSearchService.SearchRequest(dataId = "app.yaml", group = "DEFAULT_GROUP"),
            stubApi()
        )

        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        assertEquals(1L, (state as NacosSearchService.SearchState.Success).observation)
    }

    @Test
    fun `a result assembled from the namespace index observed nothing remotely`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        cache.replaceNamespaceIndex(
            context.identity,
            "",
            listOf(NacosConfiguration("app.yaml", "DEFAULT_GROUP", "", "feature=true", "yaml")),
            ttl = 600_000L
        )

        val service = NacosSearchService()
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "*",
                namespace = NamespaceInfo.createPublicNamespace(),
                operationContext = context
            ),
            mock<NacosApiService>()
        )

        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        assertEquals(
            Observed.NO_OBSERVATION,
            (state as NacosSearchService.SearchState.Success).observation
        )
    }

    @Test
    fun `local index search reports cache age from the index entry timestamp`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()

        cache.replaceNamespaceIndex(
            context.identity,
            "",
            listOf(NacosConfiguration("app.yaml", "DEFAULT_GROUP", "", "feature=true", "yaml")),
            ttl = 600_000L
        )
        val entry = cache.getNamespaceIndexEntry(context.identity, "")

        val service = NacosSearchService()
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "*",
                namespace = NamespaceInfo.createPublicNamespace(),
                operationContext = context
            ),
            mock<NacosApiService>()
        )

        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        val success = state as NacosSearchService.SearchState.Success
        assertEquals(NacosSearchService.SearchSource.CACHE, success.source)
        // Regression guard: this path used to pass no timestamp at all, so every
        // index-backed result claimed WITHIN_TTL regardless of its real age (#42).
        assertEquals(entry?.createdAtMillis, success.confidence.fetchedAtMillis)
        Unit
    }

    @Test
    fun `local content search matches text that occurs only in seeded configuration bodies`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()

        // Index stores summaries only (issue #52); bodies arrive via list-carried
        // seeding into ordinary detail coordinates (issue #146).
        cache.replaceNamespaceIndex(
            context.identity,
            "",
            listOf(
                NacosConfiguration("app.yaml", "DEFAULT_GROUP", "", "", "yaml"),
                NacosConfiguration("other.yaml", "DEFAULT_GROUP", "", "", "yaml")
            ),
            ttl = 600_000L
        )
        cache.writeDetail(
            context.identity,
            "",
            NacosConfiguration(
                "app.yaml",
                "DEFAULT_GROUP",
                "",
                "unique-body-token=42",
                "yaml"
            ),
            ttl = 600_000L
        )

        val service = NacosSearchService()
        service.performSearch(
            NacosSearchService.SearchRequest(
                query = "unique-body-token",
                searchContent = true,
                namespace = NamespaceInfo.createPublicNamespace(),
                operationContext = context
            ),
            mock<NacosApiService>()
        )

        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        val success = state as NacosSearchService.SearchState.Success
        assertEquals(1, success.configurations.size)
        assertEquals("app.yaml", success.configurations.single().dataId)
        assertTrue(
            success.configurations.single().content.contains("unique-body-token"),
            "result should carry the seeded body used for matching"
        )
    }

    @Test
    fun `local index search serves a stale index without full namespace pagination`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        cache.clearAll()
        // 1 ms TTL so the index is immediately expired; issue #147 requires
        // serving it as STALE_CACHE without asking the coordinator to re-page.
        cache.replaceNamespaceIndex(
            context.identity,
            "",
            listOf(NacosConfiguration("app.yaml", "DEFAULT_GROUP", "", "", "yaml")),
            ttl = 1L
        )
        delay(2L)
        assertNull(cache.getNamespaceIndexEntry(context.identity, ""))
        assertTrue(
            cache.getNamespaceIndexEntry(context.identity, "", allowStale = true) != null
        )

        var coordinatorCalls = 0
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                coordinatorCalls++
                error("full namespace pagination must not run when a stale index is present")
            }
        }
        val service = NacosSearchService(indexRequester = requester)
        service.performSearch(
            NacosSearchService.SearchRequest(
                dataId = "*",
                namespace = NamespaceInfo.createPublicNamespace(),
                operationContext = context
            ),
            mock<NacosApiService>()
        )

        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        val success = state as NacosSearchService.SearchState.Success
        assertEquals(NacosSearchService.SearchSource.STALE_CACHE, success.source)
        assertEquals(listOf("app.yaml"), success.configurations.map { it.dataId })
        assertEquals(0, coordinatorCalls)
        Unit
    }

    @Test
    fun `search request cache key excludes credentials and uses prepared operation context only`() {
        // Issue #53: SearchRequest carries a prepared operation context; credentials
        // must not participate in cache keys (or any other identity-derived key).
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        val slots = com.nanyin.nacos.search.settings.InMemoryCredentialSlotStore()
        settings.applyProfileIntents(
            intents = listOf(
                com.nanyin.nacos.search.models.ProfileIntent(
                    profileId = "s_local",
                    displayName = "Alice",
                    endpoint = "https://nacos.example",
                    authMode = AuthMode.NACOS_PASSWORD,
                    principal = "alice",
                    secret = "s3cret-one"
                )
            ),
            newActiveId = "s_local",
            credentialSlots = slots
        )
        val profile = requireNotNull(settings.getProfile("s_local"))
        val context = com.nanyin.nacos.search.settings.OperationContextResolver.resolve(
            profile,
            slots.read(profile.id, profile.credentialSlotVersion)
        ).getOrThrow()
        assertEquals("s3cret-one", context.credential.secret)
        val request = NacosSearchService.SearchRequest(
            dataId = "app.yaml",
            namespace = NamespaceInfo.createPublicNamespace(),
            serverId = settings.activeServerId,
            operationContext = context
        )
        val cacheKey = request.toCacheKey()
        assertFalse(cacheKey.contains("s3cret-one"))
        assertFalse(cacheKey.contains(context.credential.secret))
        assertEquals(context, request.operationContext)
        // CredentialSnapshot masks the secret in toString; the raw value must not leak.
        assertFalse(request.toString().contains("s3cret-one"))
    }

    @Test
    fun `a search started before a namespace switch keeps the namespace it started under`() = runBlocking {
        // The search runs against the environment that was current when it
        // started, and its result is dropped rather than attributed to the one
        // the user switched to.
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val namespacesAsked = mutableListOf<String?>()
        val api = mock<NacosApiService>()
        whenever(
            api.listConfigurations(
                anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
            )
        ).thenAnswer { invocation ->
            namespacesAsked += invocation.getArgument<String?>(0)
            started.complete(Unit)
            runBlocking { release.await() }
            Observed(singlePageResponse(), 1L)
        }

        val service = NacosSearchService(apiProvider = { api })
        service.adoptSession(sessionFor(settings, "ns-a"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val search = scope.async { service.reload() }

        withTimeout(2_000) { started.await() }
        service.adoptSession(sessionFor(settings, "ns-b"))
        release.complete(Unit)
        search.await()

        assertEquals(listOf<String?>("ns-a"), namespacesAsked)
        assertFalse(
            service.searchState.value is NacosSearchService.SearchState.Success,
            "a result for the namespace the user left must not publish; was ${service.searchState.value}"
        )
        scope.cancel()
    }

    @Test
    fun `a namespace switch drops the type-ahead search typed before it`() = runBlocking {
        // Type-ahead is debounced, so the switch lands while the search is still
        // waiting to run. It must not go on to search — and certainly not
        // publish — under the namespace the user moved to.
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val api = stubApi()
        val service = NacosSearchService(apiProvider = { api })
        service.adoptSession(sessionFor(settings, "ns-a"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        service.searchAsYouType("app", scope)
        service.adoptSession(sessionFor(settings, "ns-b"))
        delay(600) // well past the 300ms debounce window

        verify(api, never()).listConfigurations(
            anyOrNull(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)
        scope.cancel()
    }

    @Test
    fun `re-preparing the same environment leaves the results standing`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val service = NacosSearchService(apiProvider = { stubApi() })
        service.adoptSession(sessionFor(settings, "ns-a"))
        service.reload()
        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)

        // A freshly captured context for an unchanged profile is the same target,
        // not a session change — the credential snapshot alone differs.
        val adopted = service.adoptSession(sessionFor(settings, "ns-a"))

        assertFalse(adopted, "re-preparing one environment is not a session change")
        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)
    }

    @Test
    fun `switching namespace abandons what the previous one published`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val service = NacosSearchService(apiProvider = { stubApi() })
        service.adoptSession(sessionFor(settings, "ns-a"))
        service.reload()
        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)

        val adopted = service.adoptSession(sessionFor(settings, "ns-b"))

        assertTrue(adopted)
        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)
    }

    private fun sessionFor(settings: NacosSettings, namespaceId: String) = SearchSessionContext(
        profileId = settings.activeServerId,
        namespace = NamespaceInfo(namespaceId = namespaceId, namespaceName = namespaceId),
        operationContext = settings.captureOperationContext(settings.activeServerId).getOrThrow()
    )

    private fun singlePageResponse() = ConfigListResponse(
        totalCount = 1,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = listOf(
            ConfigItem(
                id = "1",
                dataId = "app.yaml",
                group = "DEFAULT_GROUP",
                content = "feature=true",
                type = "yaml",
                tenant = null
            )
        )
    )

    private fun stubApi(): NacosApiService {
        val response = ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                ConfigItem(
                    id = "1",
                    dataId = "app.yaml",
                    group = "DEFAULT_GROUP",
                    content = "feature=true",
                    type = "yaml",
                    tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        runBlocking {
            whenever(
                api.listConfigurations(
                    anyOrNull(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.success(Observed(response, 1L)))
        }
        return api
    }

    @Test
    fun `searchWithDebounce delays execution until debounce window elapses`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val request = NacosSearchService.SearchRequest(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            namespace = NamespaceInfo.createPublicNamespace()
        )

        service.searchWithDebounce(request, api, scope)

        // Immediately after scheduling: the 300ms debounce delay has not
        // elapsed, so performSearch has not run yet.
        assertEquals(NacosSearchService.SearchState.Idle, service.searchState.value)

        // Wait past the 300ms debounce window for the search to run.
        val deadline = System.currentTimeMillis() + 3000
        while (service.searchState.value !is NacosSearchService.SearchState.Success &&
            System.currentTimeMillis() < deadline) {
            delay(50)
        }

        assertTrue(service.searchState.value is NacosSearchService.SearchState.Success)
        scope.cancel()
    }

    @Test
    fun `searchWithDebounce cancels the previous in-flight search`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val namespace = NamespaceInfo.createPublicNamespace()

        // Two rapid calls with different dataIds — only the second should execute.
        service.searchWithDebounce(
            NacosSearchService.SearchRequest(dataId = "first.yaml", namespace = namespace),
            api, scope
        )
        service.searchWithDebounce(
            NacosSearchService.SearchRequest(dataId = "second.yaml", namespace = namespace),
            api, scope
        )

        // Wait for the surviving (second) debounced search to complete.
        val deadline = System.currentTimeMillis() + 3000
        while (service.searchState.value !is NacosSearchService.SearchState.Success &&
            System.currentTimeMillis() < deadline) {
            delay(50)
        }

        // The cancelled (first) request must not have triggered a remote call;
        // only the second survives.
        verify(api, times(1)).listConfigurations(
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), anyOrNull()
        )
        scope.cancel()
    }

}
