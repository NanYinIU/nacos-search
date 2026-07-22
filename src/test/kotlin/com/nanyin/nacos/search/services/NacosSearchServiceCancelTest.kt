package com.nanyin.nacos.search.services

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NamespaceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertTrue

@TestApplication
class NacosSearchServiceCancelTest {

    private fun stubApi(): NacosApiService {
        val response = NacosApiService.ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                NacosApiService.ConfigItem(
                    id = "1", dataId = "app.yaml", group = "DEFAULT_GROUP",
                    content = "feature=true", type = "yaml", tenant = null
                )
            )
        )
        val api = mock<NacosApiService>()
        runBlocking {
            whenever(
                api.listConfigurations(
                    any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), anyOrNull()
                )
            ).thenReturn(Result.success(response))
        }
        return api
    }

    @Test
    fun `performSearch cancels a pending debounced search`() = runBlocking {
        val service = NacosSearchService()
        val api = stubApi()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ns = NamespaceInfo.createPublicNamespace()

        val reqA = NacosSearchService.SearchRequest(dataId = "a.yaml", group = "DEFAULT_GROUP", namespace = ns)
        service.searchWithDebounce(reqA, api, scope)

        // Immediate search (e.g. page change) must cancel the pending debounce.
        val reqB = NacosSearchService.SearchRequest(dataId = "b.yaml", group = "DEFAULT_GROUP", namespace = ns)
        service.performSearch(reqB, api)

        delay(500)

        // Only the immediate (B) search reached the API; the cancelled debounced (A)
        // coroutine must not have issued a second listing call.
        verify(api, times(1)).listConfigurations(
            any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), anyOrNull()
        )

        scope.cancel()
    }
}
