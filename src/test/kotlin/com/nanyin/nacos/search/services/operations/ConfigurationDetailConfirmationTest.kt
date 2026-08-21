package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Configuration-detail confirmation at the gateway/adapter seam (issue #235).
 * No Swing fixture.
 */
class ConfigurationDetailConfirmationTest {

    @Test
    fun `fresh stale and deep-stale cache plans keep ADR-0057 confirm behaviour`() {
        val body = cached(CacheService.DetailFreshness.FRESH)
        val fresh = ConfigurationDetailConfirmation.plan(body)
        assertTrue(fresh.shouldConfirm)
        assertFalse(fresh.forceRefresh)
        assertTrue(fresh.keepCachedVisible)

        val stale = ConfigurationDetailConfirmation.plan(
            cached(CacheService.DetailFreshness.STALE)
        )
        assertTrue(stale.shouldConfirm)
        assertFalse(stale.forceRefresh)
        assertTrue(stale.keepCachedVisible)

        val deep = ConfigurationDetailConfirmation.plan(
            cached(CacheService.DetailFreshness.DEEP_STALE)
        )
        assertTrue(deep.shouldConfirm)
        assertTrue(deep.forceRefresh)
        assertTrue(deep.keepCachedVisible)

        val missing = ConfigurationDetailConfirmation.plan(null)
        assertTrue(missing.shouldConfirm)
        assertFalse(missing.keepCachedVisible)
        assertNull(missing.cached)
    }

    @Test
    fun `consult uses the cache lookup and never captures a credential`() = runBlocking {
        val captured = AtomicBoolean(false)
        val confirmation = confirmation(
            captureContext = {
                captured.set(true)
                v1Context()
            },
            lookupCached = { _, _, dataId, _ ->
                cached(CacheService.DetailFreshness.FRESH, content = "# $dataId")
            }
        )
        val plan = confirmation.consult(v1Context().identity, "dev", "app.yaml", "G")
        assertEquals("# app.yaml", plan.cached?.configuration?.content)
        assertFalse(captured.get())
    }

    @Test
    fun `present remote body after a successful coordinate read`() = runBlocking {
        val confirmation = confirmation()
        val result = confirmation.confirm("dev", coordinate())
        val present = result as DetailReadResult.Present
        assertEquals("remote-content", present.configuration.content)
        assertFalse(present.servedFromCache)
        assertTrue(present.observation > 0)
    }

    @Test
    fun `missing context is 需要配置 and does not read the gateway`() = runBlocking {
        val reached = AtomicBoolean(false)
        val confirmation = confirmation(
            adapter = StubDetailAdapter(onRead = { reached.set(true) }),
            captureContext = { null }
        )
        val result = confirmation.confirm("dev", coordinate())
        assertInstanceOf(DetailReadResult.ConfigurationIncomplete::class.java, result)
        assertFalse(reached.get())
    }

    @Test
    fun `authorization failure is Failed not a retained overlay`() = runBlocking {
        val confirmation = confirmation(
            adapter = StubDetailAdapter(error = RemoteOperationError.Authorization(403))
        )
        val result = confirmation.confirm("dev", coordinate())
        assertInstanceOf(DetailReadResult.Failed::class.java, result)
        assertInstanceOf(
            RemoteOperationError.Authorization::class.java,
            (result as DetailReadResult.Failed).error
        )
    }

    @Test
    fun `authoritative not-found records missing through the observation-ordered path`() = runBlocking {
        val recorded = AtomicReference<Long?>(null)
        val retained = cached(CacheService.DetailFreshness.DEEP_STALE, content = "# cached")
        val confirmation = confirmation(
            adapter = StubDetailAdapter(detail = null),
            recordMissing = { _, _, _, _, observation -> recorded.set(observation) }
        )
        val result = confirmation.confirm(
            namespaceId = "dev",
            coordinate = coordinate(),
            forceRefresh = true,
            keepCachedVisible = true,
            retained = retained
        )
        val gone = result as DetailReadResult.AuthoritativeNotFound
        assertEquals("# cached", gone.retained.configuration.content)
        assertTrue(recorded.get() != null && recorded.get()!! > 0)
        assertEquals(recorded.get(), gone.observation)
    }

    @Test
    fun `refresh failure retains the cached body`() = runBlocking {
        val retained = cached(CacheService.DetailFreshness.STALE, content = "# cached")
        val confirmation = confirmation(
            adapter = StubDetailAdapter(error = RemoteOperationError.Connection(RuntimeException("down")))
        )
        val result = confirmation.confirm(
            namespaceId = "dev",
            coordinate = coordinate(),
            keepCachedVisible = true,
            retained = retained
        )
        val failed = result as DetailReadResult.RefreshFailed
        assertEquals("# cached", failed.retained.configuration.content)
        assertInstanceOf(RemoteOperationError.Connection::class.java, failed.error)
    }

    @Test
    fun `targetFor captures and resolves without the caller holding a context`() = runBlocking {
        val confirmation = confirmation()
        val target = confirmation.targetFor("team-a").getOrThrow()
        assertEquals("team-a", target.namespaceId)
        assertEquals(NacosApiGeneration.V1, target.context.resolvedGeneration)
    }

    @Test
    fun `targetFor is 需要配置 when capture yields nothing`() = runBlocking {
        val confirmation = confirmation(captureContext = { null })
        val result = confirmation.targetFor("dev")
        assertInstanceOf(ConfigurationRequired::class.java, result.exceptionOrNull())
    }

    private fun confirmation(
        adapter: StubDetailAdapter = StubDetailAdapter(),
        captureContext: suspend () -> NacosOperationContext? = { v1Context() },
        recordMissing: (suspend (AccessIdentity, String?, String, String, Long) -> Unit)? = null,
        lookupCached: (AccessIdentity, String?, String, String) -> CacheService.CachedConfiguration? =
            { _, _, _, _ -> null }
    ) = ConfigurationDetailConfirmation(
        gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter),
            observationSequence = ObservationSequence()
        ),
        captureContext = captureContext,
        resolveTarget = { context, namespaceId -> Result.success(OperationTarget(context, namespaceId)) },
        recordMissing = recordMissing,
        lookupCached = lookupCached
    )

    private fun coordinate() = ConfigurationCoordinate("app.yaml", "G")

    private fun cached(
        freshness: CacheService.DetailFreshness,
        content: String = "# cached"
    ) = CacheService.CachedConfiguration(
        configuration = config(content),
        freshness = freshness,
        freshUntilMillis = 1L,
        deepStaleAtMillis = 2L
    )

    private fun config(content: String) = NacosConfiguration(
        dataId = "app.yaml",
        group = "G",
        tenantId = "dev",
        content = content,
        type = "yaml"
    )

    private fun v1Context(): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
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
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery) =
            Result.success(HistoryPage(0, 1, 0, emptyList()))
        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String) =
            Result.success(HistoryDetail(historyId, "app.yaml", "G", null, "body", "yaml", "md5", 1000L, "PUBLISH"))
    }
}
