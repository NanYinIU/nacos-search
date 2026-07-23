package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestError
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationResolverTest {

    @Test
    fun `AUTO resolves V3 when V3 state probe succeeds`() = runBlocking {
        val v3Adapter = StubAdapter(NacosApiGeneration.V3, probeResult = Result.success(Unit))
        val v1Adapter = StubAdapter(NacosApiGeneration.V1, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(
            v3Adapter = v3Adapter,
            v1Adapter = v1Adapter
        )

        val result = resolver.resolve(autoTarget())

        assertEquals(NacosApiGeneration.V3, result.getOrThrow())
        assertEquals(1, v3Adapter.probeCount)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO resolves V1 only after typed GenerationUnsupported from V3`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.GenerationUnsupported("V3 not available"))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val result = resolver.resolve(autoTarget())

        assertEquals(NacosApiGeneration.V1, result.getOrThrow())
        assertEquals(1, v3Adapter.probeCount)
        assertEquals(1, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 authentication failure`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Authentication(401))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1, probeResult = Result.success(Unit))
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authentication::class.java, error)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 permission failure`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Authorization(403))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 bare four-zero-four`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.NotFound())
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 server error`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Server(500))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Server::class.java, error)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 timeout`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Protocol("read timeout"))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 rate limited`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.RateLimited())
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.RateLimited::class.java, error)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO does not fall back to V1 on V3 connection failure`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.Connection(RuntimeException("dns failure")))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Connection::class.java, error)
        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO propagates V1 probe failure after V3 generation unsupported`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.GenerationUnsupported("V3 not available"))
        )
        val v1Adapter = StubAdapter(
            NacosApiGeneration.V1,
            probeResult = Result.failure(RemoteOperationError.Server(500))
        )
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Server::class.java, error)
    }

    @Test
    fun `AUTO does not fall back to V1 when V3 probe returns capability unsupported`() = runBlocking {
        val v3Adapter = StubAdapter(
            NacosApiGeneration.V3,
            probeResult = Result.failure(RemoteOperationError.CapabilityUnsupported("state not supported"))
        )
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        resolver.resolve(autoTarget()).exceptionOrNull()

        assertEquals(0, v1Adapter.probeCount)
    }

    @Test
    fun `AUTO probe does not call the formal auth registry`() = runBlocking {
        val v3Adapter = RecordingAuthAdapter(NacosApiGeneration.V3)
        val v1Adapter = StubAdapter(NacosApiGeneration.V1)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        resolver.resolve(autoTarget()).getOrThrow()

        assertEquals(0, v3Adapter.authenticatorInvocations)
    }

    private fun autoTarget(): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "auto-profile",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.UNKNOWN,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.UNKNOWN
        )
        return OperationTarget(context, "public")
    }

    private class StubAdapter(
        val generation: NacosApiGeneration,
        val probeResult: Result<Unit> = Result.success(Unit)
    ) : ProtocolAdapter {
        var probeCount = 0

        override suspend fun probe(target: OperationTarget): Result<Unit> {
            probeCount++
            return probeResult
        }

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
    }

    private class RecordingAuthAdapter(
        val generation: NacosApiGeneration
    ) : ProtocolAdapter {
        var authenticatorInvocations = 0

        override suspend fun probe(target: OperationTarget): Result<Unit> {
            // Simulate that the probe does not go through the formal V1Authenticator.
            return Result.success(Unit)
        }

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate) =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand) =
            Result.success(PublishOutcome.Written("true"))
    }
    // ---- S2 integration: the real production transport must let a V1-only server
    // (404 on the V3 state path) authorise a V1 fallback, not collapse to Connection ----

    @Test
    fun `AUTO falls back to V1 when the production transport returns 404 on V3 state`() = runBlocking {
        val http = StateThrowingHttp(NacosRequestError.Client(404, "Not Found"))
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val v3Adapter = V3ProtocolAdapter(transport)
        val v1Adapter = V1ProtocolAdapter(transport)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val result = resolver.resolve(autoTarget())

        assertEquals(NacosApiGeneration.V1, result.getOrThrow())
    }

    @Test
    fun `AUTO does not fall back when the production transport returns 403 on V3 state`() = runBlocking {
        val http = StateThrowingHttp(NacosRequestError.Authentication(403))
        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor(http))
        val v3Adapter = V3ProtocolAdapter(transport)
        val v1Adapter = V1ProtocolAdapter(transport)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)

        val error = resolver.resolve(autoTarget()).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Authorization::class.java, error)
    }

    /**
     * Models a V1-only server: the V3 admin state path is absent (404), while
     * every legacy V1 path answers normally. This is the case that must authorise
     * a V1 fallback under AUTO.
     */
    private class StateThrowingHttp(private val error: NacosRequestError) : NacosRequestExecutor.HttpTransport {
        override fun get(request: NacosRequestExecutor.TransportRequest): String =
            if (request.url.contains("/v3/admin/core/state")) throw error else "[{\"namespace\":\"public\"}]"
        override fun post(request: NacosRequestExecutor.TransportRequest): String =
            if (request.url.contains("/v3/admin/core/state")) throw error else "true"
    }

}
