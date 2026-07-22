package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class LiveSmokeTest {

    @Test
    fun `V1 anonymous read on Nacos two-point-five`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V1_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V1_ENDPOINT not set; skipping live smoke")

        val transport = NacosRequestExecutorProtocolTransport(
            com.nanyin.nacos.search.services.network.NacosRequestExecutor()
        )
        val adapter = V1ProtocolAdapter(transport)
        val target = buildLiveTarget(endpoint!!, NacosApiGeneration.V1)

        val probeResult = adapter.probe(target)
        assertTrue(probeResult.isSuccess, "V1 probe failed: ${probeResult.exceptionOrNull()}")

        val summaryResult = adapter.listSummaries(target, SummaryQuery(pageSize = 1))
        assertTrue(summaryResult.isSuccess, "V1 summary failed: ${summaryResult.exceptionOrNull()}")
    }

    @Test
    fun `V3 anonymous read on standard Nacos three-point-two`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V3_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V3_ENDPOINT not set; skipping live smoke")

        val transport = NacosRequestExecutorProtocolTransport(
            com.nanyin.nacos.search.services.network.NacosRequestExecutor()
        )
        val adapter = V3ProtocolAdapter(transport)
        val target = buildLiveTarget(endpoint!!, NacosApiGeneration.V3)

        val probeResult = adapter.probe(target)
        assertTrue(probeResult.isSuccess, "V3 probe failed: ${probeResult.exceptionOrNull()}")

        val summaryResult = adapter.listSummaries(target, SummaryQuery(pageSize = 1))
        assertTrue(summaryResult.isSuccess, "V3 summary failed: ${summaryResult.exceptionOrNull()}")
    }

    @Test
    fun `V3 standard three-point-two does not need legacy adapter`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V3_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V3_ENDPOINT not set; skipping live smoke")

        val transport = NacosRequestExecutorProtocolTransport(
            com.nanyin.nacos.search.services.network.NacosRequestExecutor()
        )
        val v3Adapter = V3ProtocolAdapter(transport)
        val v1Adapter = V1ProtocolAdapter(transport)
        val resolver = GenerationResolver(v3Adapter, v1Adapter)
        val target = buildLiveTarget(endpoint!!, NacosApiGeneration.UNKNOWN)

        val generation = resolver.resolve(target).getOrThrow()
        assertTrue(generation == NacosApiGeneration.V3, "Expected V3, got $generation")
    }

    private fun buildLiveTarget(rawEndpoint: String, generation: NacosApiGeneration): OperationTarget {
        val endpoint = CanonicalNacosEndpoint.parse(rawEndpoint).getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "live-smoke",
                accessRevision = 0,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = generation,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 0,
            accessRevision = 0,
            resolvedGeneration = generation
        )
        return OperationTarget(context, "public")
    }
}
