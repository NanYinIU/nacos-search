package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.OperationContextResolver
import com.nanyin.nacos.search.settings.profileIntentFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NamespaceIndexRefreshServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `missing namespace index requests a non-blocking PSI refresh off the EDT`() = runBlocking {
        val observed = CompletableDeferred<Pair<NamespaceIndexRequest, IndexTrigger>>()
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                observed.complete(request to trigger)
                return IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("offline")))
            }
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.loadIntentDraft().snapshot()
        val originalActive = settings.resolveDefaultProfileId()
        val testProfileId = "s_refresh"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            // Flat fields are not runtime sources after migration (#104). Publish
            // through profile intents so captureNamespaceIndexRequest (off-EDT) reads
            // the BASIC profile and staged credential, not the ANONYMOUS seed.
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(
                listOf(
                    profileIntentFixture(
                        id = testProfileId,
                        displayName = "Refresh Test",
                        serverUrl = "http://localhost:8848",
                        username = "admin",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    )
                ),
                testProfileId
            )

            val profile = requireNotNull(settings.getProfile(testProfileId)) {
                "expected published profile $testProfileId"
            }
            val identity = OperationContextResolver.identityFromProfile(profile)
            service.requestIfNeeded(identity, "dev", project = null)

            val (actualRequest, trigger) = withTimeout(2_000L) { observed.await() }
            assertEquals(IndexTrigger.PSI, trigger)
            assertEquals("dev", actualRequest.key.namespaceId)
            assertEquals("http://localhost:8848", actualRequest.key.identity.canonicalEndpoint)
            assertEquals(AuthMode.BASIC, actualRequest.key.identity.authMode)
            assertEquals("admin", actualRequest.key.identity.principal)
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
        }
    }

    @Test
    fun `PSI refresh captures the callers profile not the app default`() = runBlocking {
        val observed = CompletableDeferred<NamespaceIndexRequest>()
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                observed.complete(request)
                return IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("offline")))
            }
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.loadIntentDraft().snapshot()
        val originalActive = settings.resolveDefaultProfileId()
        val defaultProfileId = "s_default"
        val projectProfileId = "s_project"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.profileId })
            // Migration default / first profile is defaultProfileId; the gutter
            // identity is projectProfileId — capture must follow the identity.
            settings.applyProfileIntents(
                listOf(
                    profileIntentFixture(
                        id = defaultProfileId,
                        displayName = "Default",
                        serverUrl = "http://default:8848",
                        username = "default-user",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    ),
                    profileIntentFixture(
                        id = projectProfileId,
                        displayName = "Project",
                        serverUrl = "http://project:8848",
                        username = "project-user",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    )
                ),
                defaultProfileId
            )

            val projectProfile = requireNotNull(settings.getProfile(projectProfileId))
            val identity = OperationContextResolver.identityFromProfile(projectProfile)
            service.requestIfNeeded(identity, "dev", project = null)

            val actualRequest = withTimeout(2_000L) { observed.await() }
            assertEquals(projectProfileId, actualRequest.key.identity.profileId)
            assertEquals("http://project:8848", actualRequest.key.identity.canonicalEndpoint)
            assertEquals("project-user", actualRequest.key.identity.principal)
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(originalServers, originalActive)
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.profileId })
        }
    }

    @Test
    fun `AUTO freshness-check and index-write identities stay equal before and after resolution`() =
        runBlocking {
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val originalServers = settings.loadIntentDraft().snapshot()
            val originalActive = settings.resolveDefaultProfileId()
            val profileId = "s_auto_keyspace"
            val endpoint = "http://auto-keyspace:8848"
            val lastKnown = ApplicationManager.getApplication()
                .getService(LastKnownGenerationStore::class.java)
            try {
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
                lastKnown.clearProfile(profileId)
                settings.applyProfileIntents(
                    listOf(
                        profileIntentFixture(
                            id = profileId,
                            displayName = "AUTO Keyspace",
                            serverUrl = endpoint,
                            username = "admin",
                            password = "secret",
                            authMode = AuthMode.BASIC,
                            apiPolicy = NacosApiPolicy.AUTO
                        )
                    ),
                    profileId
                )
                val profile = requireNotNull(settings.getProfile(profileId))

                // Before resolution: both sides stay UNKNOWN and equal.
                val freshnessBefore = settings.captureAccessIdentity(profileId)
                val requestBefore = settings.captureNamespaceIndexRequest(
                    "dev",
                    settings.captureOperationContext(profileId).getOrThrow()
                )
                assertEquals(NacosApiGeneration.UNKNOWN, freshnessBefore.resolvedGeneration)
                assertEquals(freshnessBefore, requestBefore.key.identity)
                assertEquals(requestBefore.operationContext.identity, requestBefore.key.identity)

                // After a resolved generation is known (persisted last-known —
                // the same credential-free source ADR-0053 uses on restart):
                lastKnown.put(
                    LastKnownGenerationStore.Key(
                        profileId = profile.id,
                        accessRevision = profile.accessRevision,
                        canonicalEndpoint = endpoint
                    ),
                    NacosApiGeneration.V1
                )
                val freshnessAfter = settings.captureAccessIdentity(profileId)
                val requestAfter = settings.captureNamespaceIndexRequest(
                    "dev",
                    settings.captureOperationContext(profileId).getOrThrow()
                )
                assertEquals(NacosApiGeneration.V1, freshnessAfter.resolvedGeneration)
                assertEquals(freshnessAfter, requestAfter.key.identity)
                assertEquals(requestAfter.operationContext.identity, requestAfter.key.identity)
            } finally {
                lastKnown.clearProfile(profileId)
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
                settings.applyProfileIntents(originalServers, originalActive)
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
            }
        }

    @Test
    fun `locked V1 profile index request keeps the locked generation`() = runBlocking {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.loadIntentDraft().snapshot()
        val originalActive = settings.resolveDefaultProfileId()
        val profileId = "s_locked_v1_keyspace"
        try {
            clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(
                listOf(
                    profileIntentFixture(
                        id = profileId,
                        displayName = "Locked V1",
                        serverUrl = "http://locked-v1:8848",
                        username = "admin",
                        password = "secret",
                        authMode = AuthMode.BASIC,
                        apiPolicy = NacosApiPolicy.V1
                    )
                ),
                profileId
            )
            val freshness = settings.captureAccessIdentity(profileId)
            val request = settings.captureNamespaceIndexRequest(
                "dev",
                settings.captureOperationContext(profileId).getOrThrow()
            )
            assertEquals(NacosApiGeneration.V1, freshness.resolvedGeneration)
            assertEquals(freshness, request.key.identity)
            assertEquals(request.operationContext.identity, request.key.identity)
        } finally {
            clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(originalServers, originalActive)
            clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
        }
    }

    @Test
    fun `PSI Complete under AUTO resolved generation leaves the next gutter pass idle`() =
        runBlocking {
            val requestCount = AtomicInteger(0)
            val observed = CompletableDeferred<NamespaceIndexRequest>()
            val requester = object : NamespaceIndexRequester {
                override suspend fun requestIndex(
                    request: NamespaceIndexRequest,
                    trigger: IndexTrigger
                ): IndexOutcome {
                    requestCount.incrementAndGet()
                    if (!observed.isCompleted) observed.complete(request)
                    return IndexOutcome.Complete(count = 0)
                }
            }
            val cacheService = CacheService(InMemoryCacheStore())
            cacheService.clearAll()
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val originalServers = settings.loadIntentDraft().snapshot()
            val originalActive = settings.resolveDefaultProfileId()
            val profileId = "s_auto_psi_fresh"
            val endpoint = "http://auto-psi-fresh:8848"
            val lastKnown = ApplicationManager.getApplication()
                .getService(LastKnownGenerationStore::class.java)
            val service = NamespaceIndexRefreshService(
                requester,
                cacheService,
                CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            ) { _, _ -> }
            try {
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
                lastKnown.clearProfile(profileId)
                settings.applyProfileIntents(
                    listOf(
                        profileIntentFixture(
                            id = profileId,
                            displayName = "AUTO PSI Fresh",
                            serverUrl = endpoint,
                            username = "admin",
                            password = "secret",
                            authMode = AuthMode.BASIC,
                            apiPolicy = NacosApiPolicy.AUTO
                        )
                    ),
                    profileId
                )
                val profile = requireNotNull(settings.getProfile(profileId))
                lastKnown.put(
                    LastKnownGenerationStore.Key(
                        profileId = profile.id,
                        accessRevision = profile.accessRevision,
                        canonicalEndpoint = endpoint
                    ),
                    NacosApiGeneration.V1
                )
                val identity = settings.captureAccessIdentity(profileId)
                assertEquals(NacosApiGeneration.V1, identity.resolvedGeneration)

                service.requestIfNeeded(identity, "dev", project = null)
                val request = withTimeout(2_000L) { observed.await() }
                assertEquals(identity, request.key.identity)
                assertEquals(1, requestCount.get())

                // Simulate what a Complete coordinator write lands under the
                // request key — the next gutter pass must see FRESH and idle.
                @OptIn(CacheWriteAccess::class)
                cacheService.applyMutation(
                    CacheMutation.ReplaceNamespaceIndex(
                        identity = request.key.identity,
                        namespaceId = "dev",
                        summaries = emptyList(),
                        ttlMillis = settings.getCacheTtlMillis()
                    ),
                    observation = 1L
                )
                service.requestIfNeeded(identity, "dev", project = null)
                assertEquals(1, requestCount.get())
            } finally {
                service.dispose()
                lastKnown.clearProfile(profileId)
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
                settings.applyProfileIntents(originalServers, originalActive)
                clearProfileTombstones(listOf(profileId) + originalServers.map { it.profileId })
            }
        }

    @Test
    fun `an indexed namespace is never re-paged from a gutter pass once it ages`() = runBlocking {
        val requestCount = AtomicInteger(0)
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                requestCount.incrementAndGet()
                return IndexOutcome.Failed(NacosRequestError.Connection(RuntimeException("offline")))
            }
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.loadIntentDraft().snapshot()
        val originalActive = settings.resolveDefaultProfileId()
        val testProfileId = "s_aged_index"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(
                listOf(
                    profileIntentFixture(
                        id = testProfileId,
                        displayName = "Aged Index",
                        serverUrl = "http://localhost:8848",
                        username = "admin",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    )
                ),
                testProfileId
            )
            val profile = requireNotNull(settings.getProfile(testProfileId))
            val identity = OperationContextResolver.identityFromProfile(profile)

            // A complete index that has aged out of its TTL. It still answers
            // "does this data id exist"; paging 100 rows a page to restamp it
            // was the five-minute full re-scan (ADR-0057).
            cacheService.replaceNamespaceIndex(identity, "dev", emptyList(), ttl = -1L)
            assertEquals(
                CacheService.DetailFreshness.STALE,
                cacheService.snapshot(identity).namespaceIndex("dev")?.freshness
            )

            service.requestIfNeeded(identity, "dev", project = null)
            kotlinx.coroutines.delay(50)
            assertEquals(0, requestCount.get())

            // Cold is still cold: with no index at all the question is
            // unanswerable and the page walk is the only thing that can answer.
            service.requestIfNeeded(identity, "never-indexed", project = null)
            withTimeout(2_000L) {
                while (requestCount.get() == 0) kotlinx.coroutines.delay(10)
            }
            assertEquals(1, requestCount.get())
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
        }
    }

    @Test
    fun `Complete outcome runs afterRefresh Partial does not`() = runBlocking {
        val afterRefreshCount = java.util.concurrent.atomic.AtomicInteger(0)
        val afterRefreshSignals = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        val requestSignals = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<IndexOutcome>()
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome {
                val outcome = outcomes.remove()
                    ?: error("unexpected requestIndex call")
                requestSignals.poll()?.complete(Unit)
                return outcome
            }
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.loadIntentDraft().snapshot()
        val originalActive = settings.resolveDefaultProfileId()
        val testProfileId = "s_after_refresh"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { _, _ ->
            afterRefreshCount.incrementAndGet()
            afterRefreshSignals.poll()?.complete(Unit)
        }
        try {
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(
                listOf(
                    profileIntentFixture(
                        id = testProfileId,
                        displayName = "After Refresh",
                        serverUrl = "http://localhost:8848",
                        username = "admin",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    )
                ),
                testProfileId
            )
            val profile = requireNotNull(settings.getProfile(testProfileId))
            val identity = OperationContextResolver.identityFromProfile(profile)

            val partialDone = CompletableDeferred<Unit>()
            requestSignals.add(partialDone)
            outcomes.add(
                IndexOutcome.Partial(
                    loaded = 1,
                    expected = 2
                )
            )
            service.requestIfNeeded(identity, "partial-ns", project = null)
            withTimeout(2_000L) { partialDone.await() }
            // Give a Partial→afterRefresh bug a moment to fire before asserting.
            kotlinx.coroutines.delay(50)
            assertEquals(0, afterRefreshCount.get())

            val completeRequest = CompletableDeferred<Unit>()
            val completeRefresh = CompletableDeferred<Unit>()
            requestSignals.add(completeRequest)
            afterRefreshSignals.add(completeRefresh)
            outcomes.add(
                IndexOutcome.Complete(count = 1)
            )
            service.requestIfNeeded(identity, "complete-ns", project = null)
            withTimeout(2_000L) { completeRequest.await() }
            withTimeout(2_000L) { completeRefresh.await() }
            assertEquals(1, afterRefreshCount.get())
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
            settings.applyProfileIntents(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.profileId })
        }
    }

    private fun clearProfileTombstones(profileIds: List<String>) {
        val tombstones = ApplicationManager.getApplication()
            .getService(ProfileTombstoneRegistry::class.java)
        profileIds.forEach { tombstones.clear(it) }
    }
}
