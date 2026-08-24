@file:OptIn(CacheWriteAccess::class)

package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Acceptance for issue #248: the visible search box is one case-insensitive
 * Data ID substring, assembled the same way for Enter search and live search.
 */
@TestApplication
class NacosSearchServiceDataIdSearchTest {

    @BeforeEach
    fun resetSharedSettingsToAnonymousDefaults() {
        ApplicationManager.getApplication().getService(NacosSettings::class.java).apply {
            resetToDefaults()
            applyProfileIntents(
                listOf(
                    com.nanyin.nacos.search.settings.profileIntentFixture(
                        id = "s_local",
                        displayName = "Local",
                        serverUrl = "http://localhost:8848",
                        authMode = AuthMode.ANONYMOUS
                    )
                ),
                "s_local"
            )
        }
    }

    @Test
    fun `a query matches when it is contained in the Data ID ignoring case`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("OTHER.properties"),
            config("app-prod.yaml", group = "PROD_GROUP")
        )

        service.search(SearchCriteria(dataId = "APP"))

        assertEquals(setOf("application.yaml", "app-prod.yaml"), resultIds(service))
    }

    @Test
    fun `an ordinary middle substring matches`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("gateway-route.yaml")
        )

        service.search(SearchCriteria(dataId = "cation"))

        assertEquals(setOf("application.yaml"), resultIds(service))
    }

    @Test
    fun `asterisk matches only Data IDs containing a literal asterisk`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("star*.yaml"),
            config("prefix-star.yaml")
        )

        service.search(SearchCriteria(dataId = "*"))

        assertEquals(setOf("star*.yaml"), resultIds(service))
    }

    @Test
    fun `question mark matches only Data IDs containing a literal question mark`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("what?.yaml")
        )

        service.search(SearchCriteria(dataId = "?"))

        assertEquals(setOf("what?.yaml"), resultIds(service))
    }

    @Test
    fun `an invalid regex character is a literal substring and does not fail the search`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("foo[bar].yaml")
        )

        service.search(SearchCriteria(dataId = "["))

        assertEquals(setOf("foo[bar].yaml"), resultIds(service))
    }

    @Test
    fun `group filtering composes with the Data ID substring`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("app-prod.yaml", group = "PROD_GROUP"),
            config("unrelated.yaml", group = "PROD_GROUP")
        )

        service.search(SearchCriteria(dataId = "app", group = "PROD_GROUP"))

        assertEquals(setOf("app-prod.yaml"), resultIds(service))
    }

    @Test
    fun `empty criteria is the ordinary unfiltered listing not a local match-all`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        cache.replaceNamespaceIndex(
            context.identity,
            "",
            listOf(config("indexed-only.yaml"), config("star*.yaml")),
            ttl = 600_000L
        )
        val service = NacosSearchService(apiProvider = { listingApi("listed.yaml") })
        service.adoptSession(sessionFor(settings))

        service.search(SearchCriteria())

        assertEquals(listOf("listed.yaml"), resultIds(service).toList())
    }

    @Test
    fun `the search box does not match configuration body text`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml", content = "unique-body-token=42")
        )

        service.search(SearchCriteria(dataId = "unique-body-token"))

        assertEquals(emptySet<String>(), resultIds(service))
    }

    @Test
    fun `Enter search and live search return the same matching set`() = runBlocking {
        val service = serviceOverIndex(
            config("application.yaml"),
            config("app-prod.yaml", group = "PROD_GROUP"),
            config("star*.yaml"),
            config("what?.yaml")
        )
        val enterCriteria = SearchCriteria(dataId = "APP", group = "PROD_GROUP")
        val otherCriteria = SearchCriteria(dataId = "what")

        service.search(enterCriteria)
        val entered = resultIds(service)
        assertEquals(setOf("app-prod.yaml"), entered)

        // Move the published result away from [entered] so waiting for that
        // set cannot succeed on the previous Enter search's Success.
        service.search(otherCriteria)
        assertEquals(setOf("what?.yaml"), resultIds(service))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            service.searchAsYouType(enterCriteria, scope)
            val live = withTimeout(3_000) {
                service.searchState.first { state ->
                    state is NacosSearchService.SearchState.Success &&
                        state.configurations.map { it.dataId }.toSet() == entered
                }
            }
            assertEquals(
                entered,
                (live as NacosSearchService.SearchState.Success).configurations.map { it.dataId }.toSet()
            )
        } finally {
            scope.cancel()
        }
    }

    private suspend fun serviceOverIndex(vararg configs: NacosConfiguration): NacosSearchService {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val context = settings.captureOperationContext().getOrThrow()
        cache.replaceNamespaceIndex(context.identity, "", configs.toList(), ttl = 600_000L)
        val service = NacosSearchService(apiProvider = { mock() })
        service.adoptSession(sessionFor(settings))
        return service
    }

    private fun sessionFor(settings: NacosSettings) = SearchSessionContext(
        profileId = settings.resolveDefaultProfileId(),
        namespace = NamespaceInfo.createPublicNamespace(),
        operationContext = settings.captureOperationContext(settings.resolveDefaultProfileId()).getOrThrow()
    )

    private fun config(
        dataId: String,
        group: String = "DEFAULT_GROUP",
        content: String = "k=v"
    ) = NacosConfiguration(dataId, group, "", content, "yaml")

    private fun resultIds(service: NacosSearchService): Set<String> {
        val state = service.searchState.value
        assertTrue(state is NacosSearchService.SearchState.Success, "search did not succeed: $state")
        return (state as NacosSearchService.SearchState.Success).configurations.map { it.dataId }.toSet()
    }

    private fun listingApi(dataId: String): NacosApiService {
        val response = com.nanyin.nacos.search.models.ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                com.nanyin.nacos.search.models.ConfigItem(
                    id = "1",
                    dataId = dataId,
                    group = "DEFAULT_GROUP",
                    content = "k=v",
                    type = "yaml",
                    tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        runBlocking {
            org.mockito.kotlin.whenever(
                api.listConfigurations(
                    org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                    org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull()
                )
            ).thenReturn(Result.success(com.nanyin.nacos.search.services.operations.Observed(response, 1L)))
        }
        return api
    }
}
