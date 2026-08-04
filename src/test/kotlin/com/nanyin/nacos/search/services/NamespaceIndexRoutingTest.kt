package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DataFreshness
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetState
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NamespaceIndexRoutingTest {
    private val identity = AccessIdentity.ofProfile(
        profileId = "server-a",
        accessRevision = 1,
        canonicalEndpoint = "http://a:8848",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.BASIC,
        principal = "alice"
    )
    private val context = NacosOperationContext(
        identity = identity,
        endpoint = CanonicalNacosEndpoint.parse("http://a:8848").getOrThrow(),
        credential = CredentialSnapshot("secret"),
        authMode = AuthMode.BASIC,
        profileRevision = 1,
        accessRevision = 1,
        resolvedGeneration = NacosApiGeneration.V1
    )
    private val request = NamespaceIndexRequest(
        NamespaceIndexKey(identity, "ns-a"),
        300_000L,
        context
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
