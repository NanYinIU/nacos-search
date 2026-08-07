package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.OperationContextResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

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
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        val testProfileId = "s_refresh"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            // Flat fields are not runtime sources after migration (#104). Publish
            // through applyServers so captureNamespaceIndexRequest (off-EDT) reads
            // the BASIC profile and staged credential, not the ANONYMOUS seed.
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(
                listOf(
                    NacosServerConfig(
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
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
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
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        val defaultProfileId = "s_default"
        val projectProfileId = "s_project"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> }
        try {
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.id })
            // Migration default / first profile is defaultProfileId; the gutter
            // identity is projectProfileId — capture must follow the identity.
            settings.applyServers(
                listOf(
                    NacosServerConfig(
                        id = defaultProfileId,
                        displayName = "Default",
                        serverUrl = "http://default:8848",
                        username = "default-user",
                        password = "secret",
                        authMode = AuthMode.BASIC
                    ),
                    NacosServerConfig(
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
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.id })
            settings.applyServers(originalServers, originalActive)
            clearProfileTombstones(listOf(defaultProfileId, projectProfileId) + originalServers.map { it.id })
        }
    }

    @Test
    fun `Complete outcome runs afterRefresh Partial does not`() = runBlocking {
        val afterRefreshCount = java.util.concurrent.atomic.AtomicInteger(0)
        val outcomes = java.util.ArrayDeque<IndexOutcome>()
        val requester = object : NamespaceIndexRequester {
            override suspend fun requestIndex(
                request: NamespaceIndexRequest,
                trigger: IndexTrigger
            ): IndexOutcome = outcomes.removeFirst()
        }
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.clearAll()
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val originalServers = settings.servers.map { it.copy() }
        val originalActive = settings.activeServerId
        val testProfileId = "s_after_refresh"
        val service = NamespaceIndexRefreshService(
            requester,
            cacheService,
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        ) { _, _ -> afterRefreshCount.incrementAndGet() }
        try {
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(
                listOf(
                    NacosServerConfig(
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

            outcomes.add(
                IndexOutcome.Partial(
                    loaded = 1,
                    expected = 2,
                    state = com.nanyin.nacos.search.models.DatasetState(
                        com.nanyin.nacos.search.models.DataSource.REMOTE,
                        com.nanyin.nacos.search.models.DataFreshness.FRESH,
                        com.nanyin.nacos.search.models.DatasetCompleteness.PARTIAL,
                        System.currentTimeMillis()
                    )
                )
            )
            service.requestIfNeeded(identity, "partial-ns", project = null)
            // Unconfined runs the coroutine inline; Partial must not refresh.
            assertEquals(0, afterRefreshCount.get())

            outcomes.add(
                IndexOutcome.Complete(
                    count = 1,
                    state = com.nanyin.nacos.search.models.DatasetState(
                        com.nanyin.nacos.search.models.DataSource.REMOTE,
                        com.nanyin.nacos.search.models.DataFreshness.FRESH,
                        com.nanyin.nacos.search.models.DatasetCompleteness.COMPLETE,
                        System.currentTimeMillis()
                    )
                )
            )
            service.requestIfNeeded(identity, "complete-ns", project = null)
            assertEquals(1, afterRefreshCount.get())
        } finally {
            service.dispose()
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
            settings.applyServers(originalServers, originalActive)
            clearProfileTombstones(listOf(testProfileId) + originalServers.map { it.id })
        }
    }

    private fun clearProfileTombstones(profileIds: List<String>) {
        val tombstones = ApplicationManager.getApplication()
            .getService(ProfileTombstoneRegistry::class.java)
        profileIds.forEach { tombstones.clear(it) }
    }
}
