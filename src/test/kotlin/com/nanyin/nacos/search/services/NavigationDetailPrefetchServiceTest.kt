package com.nanyin.nacos.search.services

import com.intellij.openapi.project.Project
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.nanyin.nacos.search.services.operations.Observed
import com.nanyin.nacos.search.services.operations.ObservationSequence

class NavigationDetailPrefetchServiceTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = AccessIdentity.ofProfile(
        profileId = "env-a",
        accessRevision = 1,
        canonicalEndpoint = "http://test:8848",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.BASIC,
        principal = "admin"
    )
    private val otherIdentity = AccessIdentity.ofProfile(
        profileId = "env-b",
        accessRevision = 1,
        canonicalEndpoint = "http://other:8848",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.BASIC,
        principal = "admin"
    )
    private val context = NacosOperationContext(
        identity = identity,
        endpoint = CanonicalNacosEndpoint.parse("http://test:8848").getOrThrow(),
        credential = CredentialSnapshot("admin"),
        authMode = AuthMode.BASIC,
        profileRevision = 1,
        accessRevision = 1,
        resolvedGeneration = NacosApiGeneration.V1
    )

    private lateinit var settings: NacosSettings
    private lateinit var project: Project

    @Before
    fun setUp() {
        settings = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(NacosSettings::class.java)
        settings.resetToDefaults()
        // Align active server id with the identity profile so the toggle lookup
        // by profileId sees the same entry. Publish preference record via applyServers.
        val server = settings.getActiveServer().copy(
            id = identity.profileId,
            navigationDetailPrefetchEnabled = true
        )
        settings.applyServers(listOf(server), identity.profileId)

        project = mock()
        whenever(project.isDisposed).thenReturn(false)
        whenever(project.isDefault).thenReturn(false)
        whenever(project.locationHash).thenReturn("project-hash-1")
    }

    private fun service(
        api: NacosApiService,
        cache: CacheService,
        declared: Set<String>
    ): NavigationDetailPrefetchService =
        NavigationDetailPrefetchService(
            apiService = api,
            cacheService = cache,
            settings = settings,
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            declaredSourceLookup = { declared },
            onDetailsFetched = { _, _ -> },
            nowMillis = { 1_000_000L }
        )

    @Test
    fun `prefetch with declared sources requests only those data ids`() = runBlocking {
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(
                NacosConfiguration("common.properties", "DEFAULT_GROUP", "dev", "", "properties"),
                NacosConfiguration("other.properties", "DEFAULT_GROUP", "dev", "", "properties"),
                NacosConfiguration("noise.properties", "DEFAULT_GROUP", "dev", "", "properties")
            )
        )
        whenever(
            api.getConfiguration(
                dataId = eq("common.properties"),
                group = any(),
                namespaceId = anyOrNull(),
                useCache = any(),
                forceRefresh = any(),
                operationContext = eq(context)
            )
        ).thenReturn(
            Result.success(
                Observed(
                    NacosConfiguration("common.properties", "DEFAULT_GROUP", "dev", "a=1", "properties"),
                    observation = ObservationSequence.process.next()
                )
            )
        )

        val svc = service(api, cache, setOf("common.properties"))
        val outcome = withTimeout(5_000) {
            svc.requestPrefetch(project, identity, context, "dev")!!.await()
        }

        assertTrue(outcome is PrefetchOutcome.Complete)
        val complete = outcome as PrefetchOutcome.Complete
        assertEquals(1, complete.requested)
        assertEquals(1, complete.fetched)
        assertFalse(complete.usedFallbackBudget)

        verify(api, times(1)).getConfiguration(
            dataId = eq("common.properties"),
            group = any(),
            namespaceId = anyOrNull(),
            useCache = any(),
            forceRefresh = any(),
            operationContext = eq(context)
        )
        verify(api, never()).getConfiguration(
            dataId = eq("noise.properties"),
            group = any(),
            namespaceId = anyOrNull(),
            useCache = any(),
            forceRefresh = any(),
            operationContext = any()
        )
        assertEquals(
            "a=1",
            cache.getConfigDetail(identity, "dev", "common.properties", "DEFAULT_GROUP")?.content
        )
        svc.dispose()
    }

    @Test
    fun `toggle off produces no detail requests`() = runBlocking {
        val servers = settings.cloneServers().map {
            it.copy(navigationDetailPrefetchEnabled = false)
        }
        settings.applyServers(servers, settings.activeServerId)
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        val svc = service(api, cache, setOf("common.properties"))

        val deferred = svc.requestPrefetch(project, identity, context, "dev")
        assertNull(deferred)
        verify(api, never()).getConfiguration(
            dataId = any(),
            group = any(),
            namespaceId = anyOrNull(),
            useCache = any(),
            forceRefresh = any(),
            operationContext = any()
        )
        svc.dispose()
    }

    @Test
    fun `no declared sources falls back to bounded budget and reports partial coverage`() = runBlocking {
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        val summaries = (1..50).map { i ->
            NacosConfiguration("cfg-$i.properties", "DEFAULT_GROUP", "dev", "", "properties")
        }
        cache.replaceNamespaceIndex(identity, "dev", summaries)

        // Stub each of the first FALLBACK_BUDGET configs individually. Avoid
        // thenAnswer + Result (mockito double-wraps the value type for suspend).
        repeat(NavigationDetailPrefetchService.FALLBACK_BUDGET) { i ->
            val dataId = "cfg-${i + 1}.properties"
            whenever(
                api.getConfiguration(
                    dataId = eq(dataId),
                    group = any(),
                    namespaceId = anyOrNull(),
                    useCache = any(),
                    forceRefresh = any(),
                    operationContext = eq(context)
                )
            ).thenReturn(
                Result.success(
                    Observed(
                        NacosConfiguration(dataId, "DEFAULT_GROUP", "dev", "$dataId=true", "properties"),
                        observation = ObservationSequence.process.next()
                    )
                )
            )
        }

        val svc = service(api, cache, emptySet())
        val outcome = withTimeout(10_000) {
            svc.requestPrefetch(project, identity, context, "dev")!!.await()
        } as PrefetchOutcome.Complete

        assertTrue(outcome.usedFallbackBudget)
        assertEquals(NavigationDetailPrefetchService.FALLBACK_BUDGET, outcome.requested)
        assertEquals(NavigationDetailPrefetchService.FALLBACK_BUDGET, outcome.fetched)
        assertFalse(
            "fallback budget must report incomplete coverage of the namespace",
            outcome.coverage.complete
        )

        verify(api, times(NavigationDetailPrefetchService.FALLBACK_BUDGET)).getConfiguration(
            dataId = any(),
            group = any(),
            namespaceId = anyOrNull(),
            useCache = any(),
            forceRefresh = any(),
            operationContext = eq(context)
        )
        svc.dispose()
    }

    @Test
    fun `second project on same identity reuses cached details without re-fetch`() = runBlocking {
        val api = mock<NacosApiService>()
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(NacosConfiguration("shared.properties", "DEFAULT_GROUP", "dev", "", "properties"))
        )
        whenever(
            api.getConfiguration(
                dataId = eq("shared.properties"),
                group = any(),
                namespaceId = anyOrNull(),
                useCache = any(),
                forceRefresh = any(),
                operationContext = eq(context)
            )
        ).thenReturn(
            Result.success(
                Observed(
                    NacosConfiguration("shared.properties", "DEFAULT_GROUP", "dev", "shared=1", "properties"),
                    observation = ObservationSequence.process.next()
                )
            )
        )

        val svc = service(api, cache, setOf("shared.properties"))
        withTimeout(5_000) {
            svc.requestPrefetch(project, identity, context, "dev")!!.await()
        }

        val project2 = mock<Project>()
        whenever(project2.isDisposed).thenReturn(false)
        whenever(project2.isDefault).thenReturn(false)
        whenever(project2.locationHash).thenReturn("project-hash-2")
        // Different project hash → own freshness gate, but same identity cache.
        svc.clearFreshness()
        withTimeout(5_000) {
            svc.requestPrefetch(project2, identity, context, "dev")!!.await()
        }

        // First project fetched once; second found the identity-scoped detail.
        verify(api, times(1)).getConfiguration(
            dataId = eq("shared.properties"),
            group = any(),
            namespaceId = anyOrNull(),
            useCache = any(),
            forceRefresh = any(),
            operationContext = eq(context)
        )
        svc.dispose()
    }

    @Test
    fun `different access identity cannot see prefetched details`() = runBlocking {
        val cache = CacheService(InMemoryCacheStore())
        cache.clearAll()
        cache.writeDetail(
            identity,
            "dev",
            NacosConfiguration("secret.properties", "DEFAULT_GROUP", "dev", "secret=1", "properties"),
            60_000L
        )
        assertNull(
            cache.getConfigDetail(otherIdentity, "dev", "secret.properties", "DEFAULT_GROUP")
        )
    }

    @Test
    fun `prefetch toggle does not bump profile revision`() {
        val profileBefore = settings.getActiveProfile()!!
        val revisionBefore = profileBefore.profileRevision
        val accessBefore = profileBefore.accessRevision

        // Preference-only write path: applyServers with same connection fields.
        val servers = settings.cloneServers().map {
            it.copy(navigationDetailPrefetchEnabled = false)
        }
        settings.applyServers(servers, settings.activeServerId)

        val profileAfter = settings.getActiveProfile()!!
        assertEquals(revisionBefore, profileAfter.profileRevision)
        assertEquals(accessBefore, profileAfter.accessRevision)
        assertFalse(settings.preferencesFor(settings.activeServerId).navigationDetailPrefetchEnabled)
        // Dual-write surface stays aligned for the settings dialog.
        assertFalse(settings.getActiveServer().navigationDetailPrefetchEnabled)
    }
}
