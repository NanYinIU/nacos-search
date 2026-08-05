package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosApiPolicy
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.services.operations.GenerationProbeFlight
import com.nanyin.nacos.search.services.operations.GenerationSessionCapture
import com.nanyin.nacos.search.services.operations.SessionGenerationState
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Acceptance tests for issue #72 — one environment addresses one cache key
 * space on every path.
 *
 * Every assertion drives the public interfaces: an AUTO profile goes through
 * generation resolution and a gateway read, and the test then asks what a
 * hot-path read returns. Nothing here asserts which key string was derived.
 */
@TestApplication
class AutoGenerationCacheAddressingTest {

    private lateinit var settings: NacosSettings
    private lateinit var cacheService: CacheService
    private lateinit var transport: V3Transport

    private var originalServers: List<NacosServerConfig> = emptyList()
    private var originalActiveId: String = ""

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
        originalServers = settings.cloneServers()
        originalActiveId = settings.activeServerId
        transport = V3Transport()
        runBlocking { cacheService.clearAll() }
        forgetTestProfiles()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { cacheService.clearAll() }
        settings.applyServers(originalServers, originalActiveId)
        forgetTestProfiles()
        // Restoring the original set removes this test's profiles, and removing
        // a profile entombs it for the life of the application. Lift those and
        // the ones the first applyServers displaced, so neither this class's
        // next test nor another class inherits a dead profile id.
        (originalServers.map { it.id }).forEach { tombstones().clear(it) }
    }

    @Test
    fun `an AUTO profile reads back the configuration the gateway cached`() = runBlocking {
        selectAutoProfile()

        readThroughGateway(DATA_ID)

        val cached = cacheService.getConfigDetail(
            settings.captureAccessIdentity(),
            NAMESPACE,
            DATA_ID,
            GROUP
        )
        assertNotNull(cached, "hot-path read must address the space the gateway wrote under")
        assertEquals("k=v", cached!!.content)
    }

    @Test
    fun `local search over the cache returns what the gateway cached under an AUTO profile`() = runBlocking {
        selectAutoProfile()

        readThroughGateway(DATA_ID)

        val configurations = cacheService.getAllCachedConfigurations(settings.captureAccessIdentity())
        assertTrue(
            configurations.any { it.dataId == DATA_ID },
            "local search saw ${configurations.map { it.dataId }}"
        )
    }

    @Test
    fun `the clear gesture invalidates the namespace the gateway wrote into`() = runBlocking {
        selectAutoProfile()
        val apiService = readThroughGateway(DATA_ID)

        apiService.clearCache(NAMESPACE)

        assertNull(
            cacheService.getConfigDetail(settings.captureAccessIdentity(), NAMESPACE, DATA_ID, GROUP),
            "clearing a namespace must name the identity the gateway wrote under"
        )
    }

    @Test
    fun `an authoritative not-found removes the entry the gateway wrote`() = runBlocking {
        selectAutoProfile()
        readThroughGateway(DATA_ID)
        val identity = settings.captureAccessIdentity()

        // What the detail panel does on an authoritative not-found: report the
        // observation of the read that proved it gone through the recorder.
        val observation = com.nanyin.nacos.search.services.operations.ObservationSequence.process.next()
        com.nanyin.nacos.search.services.operations.ObservedDetailRecorder(cacheService)
            .recordMissing(identity, NAMESPACE, DATA_ID, GROUP, observation)

        assertNull(cacheService.getConfigDetail(identity, NAMESPACE, DATA_ID, GROUP))
    }

    @Test
    fun `without a session resolution or a last-known value the AUTO read is a miss`() = runBlocking {
        selectAutoProfile()
        readThroughGateway(DATA_ID)
        // A cold session that never resolved, with nothing persisted for this
        // access key: ADR-0034 says AUTO exposes no cache at all.
        lastKnownStore().clearProfile(AUTO_PROFILE)
        projectSessionEpochs().clearResolvedGeneration()

        val identity = settings.captureAccessIdentity()

        assertEquals(NacosApiGeneration.UNKNOWN, identity.resolvedGeneration)
        assertNull(cacheService.getConfigDetail(identity, NAMESPACE, DATA_ID, GROUP))
        assertTrue(cacheService.getAllCachedConfigurations(identity).isEmpty())
    }

    @Test
    fun `a restarted session with a matching last-known value locates its cache`() = runBlocking {
        selectAutoProfile()
        readThroughGateway(DATA_ID)
        // Restart: no in-memory session generation survives, only what ADR-0034
        // persisted by profile id, access revision, and canonical endpoint.
        projectSessionEpochs().clearResolvedGeneration()

        assertNotNull(
            cacheService.getConfigDetail(settings.captureAccessIdentity(), NAMESPACE, DATA_ID, GROUP)
        )
    }

    @Test
    fun `this project's session resolution addresses the cache before any last-known value exists`() = runBlocking {
        selectAutoProfile()
        readThroughGateway(DATA_ID)
        // Only the session knows: nothing is persisted for this access key.
        lastKnownStore().clearProfile(AUTO_PROFILE)
        commitProjectSessionGeneration(NacosApiGeneration.V3)

        val project = ProjectManager.getInstance().defaultProject
        assertNotNull(
            cacheService.getConfigDetail(
                project.captureSelectedAccessIdentity(settings),
                NAMESPACE,
                DATA_ID,
                GROUP
            )
        )
    }

    @Test
    fun `a newly resolved different generation hides what the previous one cached`() = runBlocking {
        selectAutoProfile()
        readThroughGateway(DATA_ID)
        lastKnownStore().clearProfile(AUTO_PROFILE)
        commitProjectSessionGeneration(NacosApiGeneration.V3)
        val project = ProjectManager.getInstance().defaultProject
        val epochs = projectSessionEpochs()
        val beforeEpoch = epochs.currentEpoch()

        commitProjectSessionGeneration(NacosApiGeneration.V1)

        assertTrue(epochs.currentEpoch() > beforeEpoch, "a changed generation advances the session epoch")
        assertNull(
            cacheService.getConfigDetail(
                project.captureSelectedAccessIdentity(settings),
                NAMESPACE,
                DATA_ID,
                GROUP
            ),
            "two identities differing only in resolved generation stay mutually invisible"
        )
    }

    @Test
    fun `a profile locked to V1 keeps its generation whatever was last known`() = runBlocking {
        selectLockedProfile(NacosApiPolicy.V1)
        lastKnownStore().put(
            LastKnownGenerationStore.Key(LOCKED_PROFILE, lockedProfileAccessRevision(), ENDPOINT),
            NacosApiGeneration.V3
        )

        assertEquals(
            NacosApiGeneration.V1,
            settings.captureAccessIdentity().resolvedGeneration
        )
    }

    @Test
    fun `a profile locked to V3 keeps its generation whatever was last known`() = runBlocking {
        selectLockedProfile(NacosApiPolicy.V3)
        lastKnownStore().put(
            LastKnownGenerationStore.Key(LOCKED_PROFILE, lockedProfileAccessRevision(), ENDPOINT),
            NacosApiGeneration.V1
        )

        assertEquals(
            NacosApiGeneration.V3,
            settings.captureAccessIdentity().resolvedGeneration
        )
    }

    // ── Driving the public interfaces ──

    /** Runs one gateway read, which resolves AUTO and caches what it returned. */
    private suspend fun readThroughGateway(dataId: String): NacosApiService {
        val apiService = NacosApiService(
            executorOverride = NacosRequestExecutor(transport),
            probeFlightOverride = GenerationProbeFlight()
        )
        val context = settings.captureOperationContext().getOrThrow()
        val observed = apiService.getConfiguration(
            dataId = dataId,
            group = GROUP,
            namespaceId = NAMESPACE,
            useCache = true,
            operationContext = context
        ).getOrThrow()
        assertNotNull(observed.value, "gateway read must return the configuration: ${transport.urls}")
        return apiService
    }

    private fun selectAutoProfile() = selectProfile(AUTO_PROFILE, NacosApiPolicy.AUTO)

    private fun selectLockedProfile(policy: NacosApiPolicy) = selectProfile(LOCKED_PROFILE, policy)

    private fun selectProfile(profileId: String, policy: NacosApiPolicy) {
        settings.applyServers(
            listOf(
                NacosServerConfig(
                    id = profileId,
                    displayName = profileId,
                    serverUrl = ENDPOINT,
                    authMode = AuthMode.ANONYMOUS,
                    apiPolicy = policy
                )
            ),
            profileId
        )
        ProjectManager.getInstance().defaultProject
            .getService(NacosProjectSession::class.java)
            .select(profileId, NAMESPACE)
    }

    /**
     * Commits a resolved generation into this project's session exactly as the
     * operation layer does after formal detection.
     */
    private fun commitProjectSessionGeneration(generation: NacosApiGeneration) {
        val profile = settings.getProfile(AUTO_PROFILE)!!
        val epochs = projectSessionEpochs()
        val capture = GenerationSessionCapture(
            profileId = profile.id,
            profileRevision = profile.profileRevision,
            accessRevision = profile.accessRevision,
            sessionEpoch = epochs.currentEpoch(),
            canonicalEndpoint = ENDPOINT,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        val committed = epochs.tryCommitResolvedGeneration(
            generation,
            capture,
            SessionGenerationState.LiveIdentityKeys(
                profileId = profile.id,
                profileRevision = profile.profileRevision,
                accessRevision = profile.accessRevision
            )
        )
        assertTrue(committed, "session must accept the resolved generation")
    }

    private fun lockedProfileAccessRevision(): Long = settings.getProfile(LOCKED_PROFILE)!!.accessRevision

    private fun forgetTestProfiles() {
        listOf(AUTO_PROFILE, LOCKED_PROFILE).forEach { profileId ->
            lastKnownStore().clearProfile(profileId)
            tombstones().clear(profileId)
        }
        projectSessionEpochs().clearResolvedGeneration()
    }

    private fun lastKnownStore(): LastKnownGenerationStore =
        ApplicationManager.getApplication().getService(LastKnownGenerationStore::class.java)

    private fun tombstones(): ProfileTombstoneRegistry =
        ApplicationManager.getApplication().getService(ProfileTombstoneRegistry::class.java)

    private fun projectSessionEpochs(): ProjectSessionEpochs =
        ProjectManager.getInstance().defaultProject.getService(ProjectSessionEpochs::class.java)

    /** Answers a V3 state probe and a V3 detail read for [DATA_ID]. */
    private class V3Transport : NacosRequestExecutor.HttpTransport {
        val urls = CopyOnWriteArrayList<String>()

        override fun get(request: NacosRequestExecutor.TransportRequest): String {
            urls.add(request.url)
            return when {
                request.url.contains("/v3/admin/core/state") -> """{"version":"3.2.3","status":"UP"}"""
                request.url.contains("/v3/admin/cs/config") ->
                    """{"code":0,"message":"success","data":{"dataId":"$DATA_ID","group":"$GROUP",""" +
                        """"content":"k=v","type":"properties"}}"""
                else -> throw NacosRequestError.Client(404, "not found: ${request.url}")
            }
        }

        override fun post(request: NacosRequestExecutor.TransportRequest): String {
            urls.add(request.url)
            return "true"
        }
    }

    private companion object {
        const val AUTO_PROFILE = "issue72-auto"
        const val LOCKED_PROFILE = "issue72-locked"
        const val ENDPOINT = "https://nacos72.example"
        const val NAMESPACE = "dev"
        const val DATA_ID = "issue72.properties"
        const val GROUP = "DEFAULT_GROUP"
    }
}
