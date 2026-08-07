package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.ObservationSequence
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.ProtocolAdapter
import com.nanyin.nacos.search.services.operations.ProtocolCapabilities
import com.nanyin.nacos.search.services.operations.CapabilityCoverage
import com.nanyin.nacos.search.services.operations.PublishCommand
import com.nanyin.nacos.search.services.operations.PublishOutcome
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SummaryPage
import com.nanyin.nacos.search.services.operations.SummaryQuery
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Detail load and paintability at the single seam: a stub protocol adapter
 * beneath a real [OperationGateway] (issue #81). Replaces
 * [ConfigDetailPanelStaleTest]'s dedicated loader injection.
 */
class DetailControllerTest {

    @Test
    fun `maps a successful detail read to Body with remote confidence`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubDetailAdapter()))
        val outcome = controller(gateway).load(v1Target(), coordinate(), sessionEpoch = 0)
        assertInstanceOf(DetailViewState.Body::class.java, outcome)
        val body = outcome as DetailViewState.Body
        assertEquals("remote-content", body.configuration.content)
        assertEquals(CacheAge.WITHIN_TTL, body.confidence?.age)
    }

    @Test
    fun `maps Authorization failure to Failed`() = runBlocking {
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubDetailAdapter(error = RemoteOperationError.Authorization(403)))
        )
        val outcome = controller(gateway).load(v1Target(), coordinate(), sessionEpoch = 0)
        assertInstanceOf(DetailViewState.Failed::class.java, outcome)
    }

    @Test
    fun `maps ConfigurationRequired to ConfigurationRequired state`() = runBlocking {
        val gateway = OperationGateway(
            mapOf(
                NacosApiGeneration.V1 to StubDetailAdapter(
                    error = ConfigurationRequired(listOf("Select a Nacos environment profile"))
                )
            )
        )
        val outcome = controller(gateway).load(v1Target(), coordinate(), sessionEpoch = 0)
        assertInstanceOf(DetailViewState.ConfigurationRequired::class.java, outcome)
    }

    @Test
    fun `null detail without cached body is Failed`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubDetailAdapter(detail = null)))
        val outcome = controller(gateway).load(v1Target(), coordinate(), sessionEpoch = 0)
        assertInstanceOf(DetailViewState.Failed::class.java, outcome)
    }

    @Test
    fun `drops stale results when the session epoch advances mid-load`() = runBlocking {
        val epoch = AtomicLong(0)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubDetailAdapter(onRead = {
            epoch.set(1)
        })))
        val outcome = controller(gateway) { epoch.get() }
            .load(v1Target(), coordinate(), sessionEpoch = 0)
        assertEquals(DetailViewState.Stale, outcome)
    }

    @Test
    fun `deep-stale plan shows cached body with Refreshing and forces refresh`() {
        val cached = CacheService.CachedConfiguration(
            configuration = config("# cached"),
            freshness = CacheService.DetailFreshness.DEEP_STALE,
            freshUntilMillis = 1L,
            deepStaleAtMillis = 2L
        )
        val plan = controller(OperationGateway(emptyMap())).planSelection(cached)
        assertEquals(DetailOverlay.Refreshing, plan.immediate?.overlay)
        assertEquals("# cached", plan.immediate?.configuration?.content)
        assertTrue(plan.shouldLoad)
        assertTrue(plan.forceRefresh)
        assertTrue(plan.keepCachedVisible)
    }

    @Test
    fun `stale plan shows cached body and does not load`() {
        val cached = CacheService.CachedConfiguration(
            configuration = config("# cached"),
            freshness = CacheService.DetailFreshness.STALE,
            freshUntilMillis = 1L,
            deepStaleAtMillis = 2L
        )
        val plan = controller(OperationGateway(emptyMap())).planSelection(cached)
        assertNull(plan.immediate?.overlay?.takeIf { it != DetailOverlay.None })
        assertFalse(plan.shouldLoad)
        assertTrue(plan.keepCachedVisible)
    }

    @Test
    fun `fresh plan paints cached body immediately and keeps it while confirming`() {
        val cached = CacheService.CachedConfiguration(
            configuration = config("# fresh"),
            freshness = CacheService.DetailFreshness.FRESH,
            freshUntilMillis = 1L,
            deepStaleAtMillis = 2L
        )
        val plan = controller(OperationGateway(emptyMap())).planSelection(cached)
        assertEquals("# fresh", plan.immediate?.configuration?.content)
        assertNull(plan.immediate?.overlay?.takeIf { it != DetailOverlay.None })
        assertTrue(plan.shouldLoad)
        assertFalse(plan.forceRefresh)
        assertTrue(plan.keepCachedVisible)
    }

    @Test
    fun `missing cache plan loads without an immediate body`() {
        val plan = controller(OperationGateway(emptyMap())).planSelection(null)
        assertNull(plan.immediate)
        assertTrue(plan.shouldLoad)
        assertFalse(plan.keepCachedVisible)
    }

    @Test
    fun `forceRefresh reaches the adapter even when cache would serve`() = runBlocking {
        val reached = AtomicBoolean(false)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubDetailAdapter(onRead = { reached.set(true) }))
        )
        val outcome = controller(gateway).load(
            v1Target(),
            coordinate(),
            sessionEpoch = 0,
            forceRefresh = true,
            useCache = true
        )
        assertTrue(reached.get())
        assertInstanceOf(DetailViewState.Body::class.java, outcome)
    }

    @Test
    fun `authoritative not-found with cached body records missing and keeps Deleted overlay`() = runBlocking {
        val recorded = AtomicReference<Long?>(null)
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubDetailAdapter(detail = null)),
            observationSequence = ObservationSequence()
        )
        val cachedBody = DetailPresentation.body(
            config("# cached"),
            DetailPresentation.confidenceFromCache(CacheService.DetailFreshness.DEEP_STALE),
            overlay = DetailOverlay.Refreshing
        )
        val outcome = DetailController(
            gateway = gateway,
            presentation = gate(),
            onAuthoritativeNotFound = { _, _, _, _, observation ->
                recorded.set(observation)
            }
        ).load(
            target = v1Target(),
            coordinate = coordinate(),
            sessionEpoch = 0,
            forceRefresh = true,
            keepCachedVisible = true,
            cachedBody = cachedBody
        )
        assertInstanceOf(DetailViewState.Body::class.java, outcome)
        assertEquals(DetailOverlay.Deleted, (outcome as DetailViewState.Body).overlay)
        assertTrue(recorded.get() != null && recorded.get()!! > 0)
    }

    @Test
    fun `refresh failure with cached body keeps RefreshFailed overlay`() = runBlocking {
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubDetailAdapter(error = RemoteOperationError.Connection(RuntimeException("down"))))
        )
        val cachedBody = DetailPresentation.body(
            config("# cached"),
            DetailPresentation.confidenceFromCache(CacheService.DetailFreshness.DEEP_STALE)
        )
        val outcome = controller(gateway).load(
            target = v1Target(),
            coordinate = coordinate(),
            sessionEpoch = 0,
            forceRefresh = true,
            keepCachedVisible = true,
            cachedBody = cachedBody
        )
        assertInstanceOf(DetailViewState.Body::class.java, outcome)
        assertEquals(DetailOverlay.RefreshFailed, (outcome as DetailViewState.Body).overlay)
    }

    private fun controller(
        gateway: OperationGateway,
        currentEpoch: () -> Long = { 0L }
    ) = DetailController(gateway, gate(currentEpoch))

    private fun gate(currentEpoch: () -> Long = { 0L }) =
        PresentationGate(currentEpoch) { presentedCoordinate() }

    private fun presentedCoordinate() = PresentedCoordinate.of("dev", "app.yaml", "G")

    private fun coordinate() = ConfigurationCoordinate("app.yaml", "G")

    private fun config(content: String = "k=v") = NacosConfiguration(
        dataId = "app.yaml",
        group = "G",
        tenantId = "dev",
        content = content,
        type = "yaml"
    )

    private fun v1Target(): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p1",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, "dev")
    }

    private class StubDetailAdapter(
        private val detail: NacosConfiguration? = NacosConfiguration(
            dataId = "app.yaml",
            group = "G",
            tenantId = "dev",
            content = "remote-content",
            type = "yaml"
        ),
        private val error: Throwable? = null,
        private val onRead: (() -> Unit)? = null
    ) : ProtocolAdapter {
        override val capabilities = ProtocolCapabilities.NONE.copy(history = CapabilityCoverage.COMPLETE)

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> {
            onRead?.invoke()
            if (error != null) return Result.failure(error)
            return Result.success(detail)
        }
        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
            Result.success(PublishOutcome.Written("true"))
        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> =
            Result.success(HistoryPage(0, 1, 0, emptyList()))
        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> =
            Result.success(HistoryDetail(historyId, "app.yaml", "G", null, "body", "yaml", "md5", 1000L, "PUBLISH"))
    }
}
