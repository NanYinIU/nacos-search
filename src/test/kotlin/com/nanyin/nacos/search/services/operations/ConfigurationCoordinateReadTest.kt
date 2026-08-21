package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared 配置坐标 read at the gateway/adapter seam (issue #236).
 * No Swing fixture; trigger policy (TTL vs click) is asserted here.
 */
class ConfigurationCoordinateReadTest {

    @Test
    fun `cache hit performs no remote read`() = runBlocking {
        val reads = AtomicInteger(0)
        val published = AtomicReference<AccessIdentity?>(null)
        val reader = reader(
            adapter = StubDetailAdapter(onRead = { reads.incrementAndGet() }),
            lookupCached = { _, _, _, _, _ -> config("# cached") },
            onRemoteFetched = { published.set(it) }
        )
        val result = reader.navigation(identity(), "dev", "app.yaml", "G").await()
        assertInstanceOf(CoordinateReadResult.Hit::class.java, result)
        assertEquals("# cached", (result as CoordinateReadResult.Hit).configuration.content)
        assertEquals(0, reads.get())
        assertNull(published.get())
    }

    @Test
    fun `cold read fetches once and publishes the resolved identity`() = runBlocking {
        val reads = AtomicInteger(0)
        val published = AtomicReference<AccessIdentity?>(null)
        val reader = reader(
            adapter = StubDetailAdapter(onRead = { reads.incrementAndGet() }),
            onRemoteFetched = { published.set(it) }
        )
        val result = withTimeout(5_000) {
            reader.navigation(identity(), "dev", "app.yaml", "G").await()
        }
        val fetched = result as CoordinateReadResult.Fetched
        assertEquals("remote-content", fetched.configuration.content)
        assertEquals(1, reads.get())
        assertEquals(NacosApiGeneration.V1, published.get()?.resolvedGeneration)
        assertEquals(fetched.identity, published.get())
    }

    @Test
    fun `concurrent background requests coalesce to one attempt per TTL`() = runBlocking {
        val reads = AtomicInteger(0)
        val reader = reader(
            adapter = StubDetailAdapter(onRead = { reads.incrementAndGet() })
        )
        val first = reader.background(identity(), "dev", "app.yaml", "G")
        val second = reader.background(identity(), "dev", "app.yaml", "G")
        assertTrue(first != null)
        assertNull(second)
        first!!.await()
        assertEquals(1, reads.get())
        assertNull(reader.background(identity(), "dev", "app.yaml", "G"))
    }

    @Test
    fun `click joins an in-flight background read`() = runBlocking {
        val reads = AtomicInteger(0)
        val published = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val reader = reader(
            adapter = StubDetailAdapter(
                onRead = {
                    reads.incrementAndGet()
                    gate.await()
                }
            ),
            onRemoteFetched = { published.incrementAndGet() },
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        )
        val background = reader.background(identity(), "dev", "app.yaml", "G")!!
        val joined = reader.navigation(identity(), "dev", "app.yaml", "G")
        gate.complete(Unit)
        val fromBackground = withTimeout(5_000) { background.await() }
        val fromClick = withTimeout(5_000) { joined.await() }
        assertEquals(1, reads.get())
        assertEquals(1, published.get())
        assertSame(fromBackground, fromClick)
        assertEquals("remote-content", fromClick.body()?.content)
    }

    @Test
    fun `failure backoff blocks background but not a click`() = runBlocking {
        val reads = AtomicInteger(0)
        val reader = reader(
            adapter = StubDetailAdapter(
                detail = config("recovered"),
                errorForAttempt = { attempt ->
                    reads.incrementAndGet()
                    if (attempt == 1) RuntimeException("server unreachable") else null
                }
            )
        )
        val first = reader.background(identity(), "dev", "app.yaml", "G")!!.await()
        assertInstanceOf(CoordinateReadResult.Failed::class.java, first)
        assertNull(reader.background(identity(), "dev", "app.yaml", "G"))
        val click = reader.navigation(identity(), "dev", "app.yaml", "G").await()
        assertEquals("recovered", (click as CoordinateReadResult.Fetched).configuration.content)
        assertEquals(2, reads.get())
    }

    @Test
    fun `AUTO resolution publishes the locked identity not the pre-resolution key space`() = runBlocking {
        val published = AtomicReference<AccessIdentity?>(null)
        val auto = identity(NacosApiGeneration.UNKNOWN)
        val locked = identity(NacosApiGeneration.V3)
        val reader = reader(
            captureContext = { autoContext(auto) },
            resolveTarget = { context, namespaceId ->
                assertEquals(NacosApiGeneration.UNKNOWN, context.resolvedGeneration)
                Result.success(OperationTarget(autoContext(locked), namespaceId))
            },
            onRemoteFetched = { published.set(it) }
        )
        val result = reader.navigation(auto, "dev", "app.yaml", "G").await()
        val fetched = result as CoordinateReadResult.Fetched
        assertEquals(NacosApiGeneration.V3, fetched.identity.resolvedGeneration)
        assertEquals(NacosApiGeneration.V3, published.get()?.resolvedGeneration)
        assertEquals(NacosApiGeneration.UNKNOWN, auto.resolvedGeneration)
    }

    private fun reader(
        adapter: StubDetailAdapter = StubDetailAdapter(),
        captureContext: suspend (String) -> NacosOperationContext? = { autoContext(identity()) },
        resolveTarget: suspend (NacosOperationContext, String) -> Result<OperationTarget> =
            { context, namespaceId -> Result.success(OperationTarget(context, namespaceId)) },
        lookupCached: (AccessIdentity, String?, String, String, Boolean) -> NacosConfiguration? =
            { _, _, _, _, _ -> null },
        onRemoteFetched: (AccessIdentity) -> Unit = {},
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    ) = ConfigurationCoordinateRead(
        gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to adapter, NacosApiGeneration.V3 to adapter),
            observationSequence = ObservationSequence()
        ),
        captureContext = captureContext,
        resolveTarget = resolveTarget,
        lookupCached = lookupCached,
        nowMillis = { clock.get() },
        ttlMillis = { 5_000L },
        scope = scope,
        onRemoteFetched = onRemoteFetched
    )

    private val clock = AtomicLong(1_000L)

    private fun identity(generation: NacosApiGeneration = NacosApiGeneration.V1) =
        AccessIdentity.ofProfile(
            profileId = "p1",
            accessRevision = 1,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = generation,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )

    private fun autoContext(id: AccessIdentity): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        return NacosOperationContext(
            identity = id,
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = id.resolvedGeneration
        )
    }

    private fun config(content: String) = NacosConfiguration(
        dataId = "app.yaml",
        group = "G",
        tenantId = "dev",
        content = content,
        type = "yaml"
    )

    private class StubDetailAdapter(
        private val detail: NacosConfiguration? = NacosConfiguration(
            dataId = "app.yaml",
            group = "G",
            tenantId = "dev",
            content = "remote-content",
            type = "yaml"
        ),
        private val onRead: (suspend () -> Unit)? = null,
        private val errorForAttempt: ((Int) -> Throwable?)? = null
    ) : ProtocolAdapter {
        private val attempts = AtomicInteger(0)
        override val capabilities = ProtocolCapabilities.NONE.copy(history = CapabilityCoverage.COMPLETE)
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> {
            onRead?.invoke()
            val attempt = attempts.incrementAndGet()
            errorForAttempt?.invoke(attempt)?.let { return Result.failure(it) }
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
