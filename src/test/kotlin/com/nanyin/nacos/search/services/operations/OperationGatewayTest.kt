package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationGatewayTest {

    @Test
    fun `captured manual namespace target controls adapter calls results and identity scoped cache`() = runBlocking {
        val cache = InMemoryOperationCache()
        val adapter = ScriptedAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter), cache)
        val startedTarget = anonymousTarget("dev", "https://dev.nacos.example", "team-manual")
        val laterUiSelection = anonymousTarget("prod", "https://prod.nacos.example", "other-team")

        val page = gateway.listSummaries(startedTarget, SummaryQuery(pageSize = 20), useCache = true).getOrThrow().value
        val detail = gateway.readDetail(
            startedTarget,
            ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"),
            useCache = true
        ).getOrThrow().value

        assertEquals(startedTarget, adapter.summaryTargets.single())
        assertEquals(startedTarget, adapter.detailTargets.single())
        assertEquals("team-manual", page.items.single().tenantId)
        assertEquals("from-dev", detail?.content)
        assertEquals(page, cache.getSummaries(startedTarget.context.identity, "team-manual", SummaryQuery(pageSize = 20).cacheKey()))
        assertEquals(detail, cache.getDetail(startedTarget.context.identity, "team-manual", "app.yaml", "DEFAULT_GROUP"))
        assertNull(cache.getSummaries(laterUiSelection.context.identity, "other-team", SummaryQuery(pageSize = 20).cacheKey()))
        assertNull(cache.getDetail(laterUiSelection.context.identity, "other-team", "app.yaml", "DEFAULT_GROUP"))
    }

    private fun anonymousTarget(profileId: String, endpointValue: String, namespaceId: String): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse(endpointValue).getOrThrow()
        val identity = AccessIdentity.ofProfile(
            profileId = profileId,
            accessRevision = 9,
            canonicalEndpoint = endpoint.value,
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
        return OperationTarget(
            NacosOperationContext(
                identity = identity,
                endpoint = endpoint,
                credential = CredentialSnapshot(""),
                authMode = AuthMode.ANONYMOUS,
                profileRevision = 9,
                accessRevision = 9,
                resolvedGeneration = NacosApiGeneration.V1
            ),
            namespaceId
        )
    }


    @Test
    fun `a read that started earlier does not overwrite one that started later`() = runBlocking {
        val cache = InMemoryOperationCache()
        val adapter = ParkingAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter), cache)
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")
        val query = SummaryQuery(pageNo = 1, pageSize = 1)

        coroutineScope {
            // The earlier read enters the adapter first, so it holds the lower
            // sequence — and stays parked there until the later read has landed.
            val earlier = async {
                gateway.listSummaries(target, query, forceRefresh = true).getOrThrow().value
            }
            adapter.awaitEntry()
            val later = async {
                gateway.listSummaries(target, query, forceRefresh = true).getOrThrow().value
            }
            adapter.awaitEntry()

            adapter.release(index = 1, page = page("later"))
            later.await()
            adapter.release(index = 0, page = page("earlier"))
            earlier.await()
        }

        val cached = cache.getSummaries(target.context.identity, target.namespaceId, query.cacheKey())
        assertEquals("later", cached!!.items.first().dataId)
    }

    @Test
    fun `a remote read returns the observation sequence its cache write used`() = runBlocking {
        val cache = RecordingOperationCache()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to ScriptedAdapter()), cache)
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")

        val observed = gateway.listSummaries(target, SummaryQuery(pageSize = 1), forceRefresh = true).getOrThrow()

        assertEquals(observed.observation, cache.lastSummaryObservation)
    }

    @Test
    fun `a read served from cache reports that it observed nothing`() = runBlocking {
        val cache = InMemoryOperationCache()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to ScriptedAdapter()), cache)
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")
        val query = SummaryQuery(pageSize = 1)

        val remote = gateway.listSummaries(target, query).getOrThrow()
        val fromCache = gateway.listSummaries(target, query).getOrThrow()

        assertNotEquals(Observed.NO_OBSERVATION, remote.observation)
        // A cache hit observed nothing, so a mutation derived from it can never
        // outrank a real observation — including the one that wrote this entry.
        assertEquals(Observed.NO_OBSERVATION, fromCache.observation)
    }

    @Test
    fun `authentication failure is reported to access visibility`() = runBlocking {
        val store = com.nanyin.nacos.search.services.InMemoryCacheStore()
        val visibility = com.nanyin.nacos.search.services.visibility.AccessVisibility(store)
        val adapter = object : ProtocolAdapter {
            override suspend fun probe(target: OperationTarget): Result<Unit> = Result.success(Unit)
            override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
                Result.failure(RemoteOperationError.Authentication(401))
            override suspend fun readDetail(
                target: OperationTarget,
                coordinate: ConfigurationCoordinate
            ): Result<NacosConfiguration?> =
                Result.failure(RemoteOperationError.Authentication(401))
            override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
                Result.failure(RemoteOperationError.Authentication(401))
        }
        val gateway = OperationGateway(
            adapters = mapOf(NacosApiGeneration.V1 to adapter),
            visibility = visibility
        )
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")

        val result = gateway.listSummaries(target, SummaryQuery(pageSize = 1), forceRefresh = true)
        assertTrue(result.isFailure)
        assertTrue(visibility.isIdentityAuthBlocked(target.context.identity))
    }

    @Test
    fun `matching success after reported auth failure clears the block`() = runBlocking {
        val store = com.nanyin.nacos.search.services.InMemoryCacheStore()
        val visibility = com.nanyin.nacos.search.services.visibility.AccessVisibility(store)
        var fail = true
        val adapter = object : ProtocolAdapter {
            override suspend fun probe(target: OperationTarget): Result<Unit> = Result.success(Unit)
            override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
                if (fail) Result.failure(RemoteOperationError.Authentication(401))
                else Result.success(page("ok.properties"))
            override suspend fun readDetail(
                target: OperationTarget,
                coordinate: ConfigurationCoordinate
            ): Result<NacosConfiguration?> = Result.success(null)
            override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
                Result.success(PublishOutcome.Written("true"))
        }
        val gateway = OperationGateway(
            adapters = mapOf(NacosApiGeneration.V1 to adapter),
            visibility = visibility
        )
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")

        gateway.listSummaries(target, SummaryQuery(pageSize = 1), forceRefresh = true)
        assertTrue(visibility.isIdentityAuthBlocked(target.context.identity))

        fail = false
        gateway.listSummaries(target, SummaryQuery(pageSize = 1), forceRefresh = true)
        assertFalse(visibility.isIdentityAuthBlocked(target.context.identity))
    }

    @Test
    fun `identity auth block hides history cache hits`() = runBlocking {
        val store = com.nanyin.nacos.search.services.InMemoryCacheStore()
        val visibility = com.nanyin.nacos.search.services.visibility.AccessVisibility(store)
        val history = HistoryMemoryCache()
        val adapter = object : ProtocolAdapter {
            override val capabilities = ProtocolCapabilities.V1
            override suspend fun probe(target: OperationTarget): Result<Unit> = Result.success(Unit)
            override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> =
                Result.success(page("x"))
            override suspend fun readDetail(
                target: OperationTarget,
                coordinate: ConfigurationCoordinate
            ): Result<NacosConfiguration?> = Result.success(null)
            override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
                Result.success(PublishOutcome.Written("true"))
            override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> =
                Result.success(
                    HistoryPage(
                        totalCount = 1,
                        pageNumber = 1,
                        pagesAvailable = 1,
                        items = emptyList()
                    )
                )
        }
        val gateway = OperationGateway(
            adapters = mapOf(NacosApiGeneration.V1 to adapter),
            historyCache = history,
            visibility = visibility
        )
        val target = anonymousTarget("dev", "https://dev.nacos.example", "public")
        val query = HistoryQuery(coordinate = ConfigurationCoordinate("app.yaml", "DEFAULT_GROUP"))

        // Seed history cache via remote success.
        val remote = gateway.listHistory(target, query, forceRefresh = true).getOrThrow()
        assertNotEquals(Observed.NO_OBSERVATION, remote.observation)
        val hit = gateway.listHistory(target, query, forceRefresh = false).getOrThrow()
        assertEquals(Observed.NO_OBSERVATION, hit.observation)

        visibility.reportCompleted(
            com.nanyin.nacos.search.services.visibility.CompletedObservation(
                observation = ObservationSequence.process.next(),
                identity = target.context.identity,
                operationClass = com.nanyin.nacos.search.services.visibility.VisibilityOperationClass.CONFIGURATION_READ,
                outcome = com.nanyin.nacos.search.services.visibility.ObservationOutcome.RemoteFailure(
                    RemoteOperationError.Authentication(401)
                )
            )
        )
        assertTrue(visibility.isIdentityAuthBlocked(target.context.identity))

        // Under block, history cache hit must not be served (falls through to remote).
        val underBlock = gateway.listHistory(target, query, forceRefresh = false).getOrThrow()
        assertNotEquals(
            Observed.NO_OBSERVATION,
            underBlock.observation,
            "blocked identity must not get a history cache hit"
        )
    }

    private fun page(dataId: String) = SummaryPage(
        1, 1, 1, listOf(ConfigurationSummary(dataId, "G", "public", "c", "yaml"))
    )

    private class ScriptedAdapter : ProtocolAdapter {
        val summaryTargets = mutableListOf<OperationTarget>()
        val detailTargets = mutableListOf<OperationTarget>()

        override suspend fun probe(target: OperationTarget): Result<Unit> = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery): Result<SummaryPage> {
            summaryTargets += target
            return Result.success(
                SummaryPage(
                    totalCount = 1,
                    pageNumber = query.pageNo,
                    pagesAvailable = 1,
                    items = listOf(ConfigurationSummary("app.yaml", "DEFAULT_GROUP", target.namespaceId, null, "yaml"))
                )
            )
        }

        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> {
            detailTargets += target
            return Result.success(
                NacosConfiguration(coordinate.dataId, coordinate.group, target.namespaceId, "from-dev", "yaml")
            )
        }

        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
    }


    /**
     * Parks each call until the test releases it with a payload, so a read that
     * entered first — and therefore holds the lower observation sequence — can
     * be made to finish last.
     */
    private class ParkingAdapter : ProtocolAdapter {
        private val entries = Channel<Unit>(Channel.UNLIMITED)
        private val parked = mutableListOf<CompletableDeferred<SummaryPage>>()

        /** Suspends until one more call has entered and parked. */
        suspend fun awaitEntry() {
            entries.receive()
        }

        /** Completes the [index]-th entered call, counting in entry order. */
        fun release(index: Int, page: SummaryPage) {
            synchronized(this) { parked[index] }.complete(page)
        }

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(
            target: OperationTarget,
            query: SummaryQuery
        ): Result<SummaryPage> {
            val slot = CompletableDeferred<SummaryPage>()
            synchronized(this) { parked.add(slot) }
            entries.send(Unit)
            return Result.success(slot.await())
        }

        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> = Result.success(null)

        override suspend fun publish(
            target: OperationTarget,
            command: PublishCommand
        ): Result<PublishOutcome> = Result.success(PublishOutcome.Written("true"))
    }

    /** Records the observation each write carried, without gating anything. */
    private class RecordingOperationCache : OperationCache {
        var lastSummaryObservation: Long? = null

        override suspend fun getSummaries(identity: AccessIdentity, namespaceId: String, requestKey: String) = null

        override suspend fun putSummaries(
            identity: AccessIdentity,
            namespaceId: String,
            requestKey: String,
            page: SummaryPage,
            observation: Long
        ) {
            lastSummaryObservation = observation
        }

        override suspend fun getDetail(
            identity: AccessIdentity,
            namespaceId: String,
            dataId: String,
            group: String
        ): NacosConfiguration? = null

        override suspend fun putDetail(
            identity: AccessIdentity,
            namespaceId: String,
            detail: NacosConfiguration,
            observation: Long
        ) = Unit
    }
}
