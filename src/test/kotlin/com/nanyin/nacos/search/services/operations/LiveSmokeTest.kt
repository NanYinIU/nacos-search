package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.network.NacosRequestExecutor
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Gated live smoke against a real Nacos (ADR-0031 / docs/ci/release-gate.md).
 *
 * Jenkins filters by method-name prefix:
 * - `LiveSmokeTest.V1*` with `NACOS_LIVE_V1_ENDPOINT` (nacos-server 2.5.3)
 * - `LiveSmokeTest.V3*` with `NACOS_LIVE_V3_ENDPOINT` (nacos-server 3.2.3)
 *
 * Without those env vars every test self-skips so the unit suite stays offline.
 */
class LiveSmokeTest {

    @Test
    fun `V1 anonymous read on Nacos two-point-five`() = runBlocking {
        val (_, gateway, target) = liveStack(NacosApiGeneration.V1)

        val probeResult = gateway.probe(target)
        assertTrue(probeResult.isSuccess, "V1 probe failed: ${probeResult.exceptionOrNull()}")

        val summaryResult = gateway.listSummaries(target, SummaryQuery(pageSize = 1), useCache = false)
        assertTrue(summaryResult.isSuccess, "V1 summary failed: ${summaryResult.exceptionOrNull()}")
    }

    @Test
    fun `V1 publish discover select namespace search detail and refresh`() = runBlocking {
        exerciseBrowsePublishRefresh(NacosApiGeneration.V1)
    }

    @Test
    fun `V3 anonymous read on standard Nacos three-point-two`() = runBlocking {
        val (_, gateway, target) = liveStack(NacosApiGeneration.V3)

        val probeResult = gateway.probe(target)
        assertTrue(probeResult.isSuccess, "V3 probe failed: ${probeResult.exceptionOrNull()}")

        val summaryResult = gateway.listSummaries(target, SummaryQuery(pageSize = 1), useCache = false)
        assertTrue(summaryResult.isSuccess, "V3 summary failed: ${summaryResult.exceptionOrNull()}")
    }

    @Test
    fun `V3 standard three-point-two does not need legacy adapter`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V3_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V3_ENDPOINT not set; skipping live smoke")

        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor())
        val resolver = GenerationResolver(V3ProtocolAdapter(transport), V1ProtocolAdapter(transport))
        val target = buildLiveTarget(endpoint!!, NacosApiGeneration.UNKNOWN)

        val generation = resolver.resolve(target).getOrThrow()
        assertEquals(NacosApiGeneration.V3, generation, "Expected V3, got $generation")
    }

    @Test
    fun `V3 publish discover select namespace search detail and refresh`() = runBlocking {
        exerciseBrowsePublishRefresh(NacosApiGeneration.V3)
    }

    /**
     * One live path that needs a real server: create a Namespace, select it,
     * publish a config, find it via list/search, jump to detail, then force-refresh.
     */
    private suspend fun exerciseBrowsePublishRefresh(generation: NacosApiGeneration) {
        val (transport, gateway, publicTarget) = liveStack(generation)
        val runId = UUID.randomUUID().toString().replace("-", "").take(12)
        val namespaceId = "live-smoke-$runId"
        val dataId = "live-smoke-$runId.properties"
        val group = "DEFAULT_GROUP"
        val content = "live.smoke.key=$runId"

        createNamespace(transport, publicTarget, generation, namespaceId)
        val namespaces = gateway.discoverNamespaces(publicTarget).getOrThrow()
        assertTrue(
            namespaces.any { it.namespaceId == namespaceId || it.namespaceId == "public" },
            "discoverNamespaces returned neither $namespaceId nor public: $namespaces"
        )
        assertTrue(
            namespaces.any { it.namespaceId == namespaceId },
            "created Namespace $namespaceId missing from discovery: $namespaces"
        )

        // Select the discovered Namespace (same gesture the tool window makes).
        val selected = OperationTarget(publicTarget.context, namespaceId)

        val published = gateway.publish(
            selected,
            PublishCommand(
                dataId = dataId,
                group = group,
                content = content,
                type = "properties",
                namespaceId = namespaceId
            )
        ).getOrThrow()
        assertTrue(published is PublishOutcome.Written, "publish failed: $published")

        val accurate = gateway.listSummaries(
            selected,
            SummaryQuery(pageSize = 20, dataId = dataId, group = group, search = "accurate"),
            useCache = false
        ).getOrThrow().value
        assertTrue(
            accurate.items.any { it.dataId == dataId && it.group == group },
            "accurate list missing $dataId: ${accurate.items}"
        )

        val blur = gateway.listSummaries(
            selected,
            SummaryQuery(pageSize = 20, dataId = "live-smoke-$runId*", search = "blur"),
            useCache = false
        ).getOrThrow().value
        assertTrue(
            blur.items.any { it.dataId == dataId },
            "blur search missing $dataId: ${blur.items}"
        )

        // Config jump: summary → coordinate → detail (UI-free navigation path).
        val coordinate = ConfigurationCoordinate(dataId, group)
        val detail = gateway.readDetail(selected, coordinate, useCache = false).getOrThrow()
        assertNotNull(detail.value, "detail missing for $namespaceId/$dataId")
        assertEquals(content, detail.value!!.content)
        assertNotEquals(Observed.NO_OBSERVATION, detail.observation, "remote detail must carry an observation")

        val refreshed = gateway.readDetail(
            selected,
            coordinate,
            forceRefresh = true,
            useCache = true
        ).getOrThrow()
        assertNotNull(refreshed.value)
        assertEquals(content, refreshed.value!!.content)
        assertNotEquals(
            Observed.NO_OBSERVATION,
            refreshed.observation,
            "forceRefresh must re-observe the server"
        )

        // List refresh after a content update — the tool-window "refresh" gesture.
        val updated = "$content\nlive.smoke.refreshed=true"
        gateway.publish(
            selected,
            PublishCommand(
                dataId = dataId,
                group = group,
                content = updated,
                type = "properties",
                namespaceId = namespaceId
            )
        ).getOrThrow()
        val afterRefresh = gateway.readDetail(
            selected,
            coordinate,
            forceRefresh = true
        ).getOrThrow().value
        assertEquals(updated, afterRefresh!!.content)
    }

    private fun liveStack(
        generation: NacosApiGeneration
    ): Triple<ProtocolTransport, OperationGateway, OperationTarget> {
        val env = when (generation) {
            NacosApiGeneration.V1 -> "NACOS_LIVE_V1_ENDPOINT"
            NacosApiGeneration.V3 -> "NACOS_LIVE_V3_ENDPOINT"
            else -> error("liveStack requires V1 or V3, got $generation")
        }
        val endpoint = System.getenv(env)
        assumeTrue(endpoint != null, "$env not set; skipping live smoke")

        val transport = NacosRequestExecutorProtocolTransport(NacosRequestExecutor())
        val gateway = OperationGateway(
            mapOf(
                NacosApiGeneration.V1 to V1ProtocolAdapter(transport),
                NacosApiGeneration.V3 to V3ProtocolAdapter(transport)
            )
        )
        return Triple(transport, gateway, buildLiveTarget(endpoint!!, generation))
    }

    private suspend fun createNamespace(
        transport: ProtocolTransport,
        target: OperationTarget,
        generation: NacosApiGeneration,
        namespaceId: String
    ) {
        val endpoint = target.context.endpoint.value
        val (path, fields) = when (generation) {
            NacosApiGeneration.V1 -> "/nacos/v1/console/namespaces" to listOf(
                "customNamespaceId" to namespaceId,
                "namespaceName" to namespaceId,
                "namespaceDesc" to "live-smoke"
            )
            NacosApiGeneration.V3 -> "/nacos/v3/admin/core/namespace" to listOf(
                "namespaceId" to namespaceId,
                "namespaceName" to namespaceId,
                "namespaceDesc" to "live-smoke"
            )
            else -> error("unsupported generation $generation")
        }
        val body = fields.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8.name())
        }
        val response = transport.execute(
            ProtocolRequest(
                method = "POST",
                endpoint = endpoint,
                path = path,
                query = emptyList(),
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                body = body
            )
        )
        assertTrue(
            response.status in 200..299,
            "create Namespace $namespaceId failed: HTTP ${response.status} ${response.body}"
        )
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
