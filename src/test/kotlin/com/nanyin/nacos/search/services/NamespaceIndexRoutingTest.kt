package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DataFreshness
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetState
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NamespaceIndexRoutingTest {
    private val request = NamespaceIndexRequest(
        NamespaceIndexKey(AccessIdentity.of("http://a:8848", AuthMode.BASIC, "alice"), "ns-a"),
        NacosServerSnapshot("http://a:8848", "alice", "secret", AuthMode.BASIC, false)
    )
    private val outcome = IndexOutcome.Complete(
        1,
        DatasetState(DataSource.REMOTE, DataFreshness.FRESH, DatasetCompleteness.COMPLETE, 1L)
    )

    @Test
    fun `startup preheat uses namespace switch trigger`() { runBlocking {
        val requester = mock<NamespaceIndexRequester>()
        whenever(requester.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)).thenReturn(outcome)
        requester.requestStartupNamespaceIndex(request)
        verify(requester).requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)
    } }

    @Test
    fun `window namespace switch uses namespace switch trigger`() { runBlocking {
        val requester = mock<NamespaceIndexRequester>()
        whenever(requester.requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)).thenReturn(outcome)
        requester.requestSwitchedNamespaceIndex(request)
        verify(requester).requestIndex(request, IndexTrigger.NAMESPACE_SWITCH)
    } }

    @Test
    fun `manual refresh uses manual refresh trigger`() { runBlocking {
        val requester = mock<NamespaceIndexRequester>()
        whenever(requester.requestIndex(request, IndexTrigger.MANUAL_REFRESH)).thenReturn(outcome)
        requester.requestManualNamespaceRefresh(request)
        verify(requester).requestIndex(request, IndexTrigger.MANUAL_REFRESH)
    } }
}
