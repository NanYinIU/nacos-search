@file:OptIn(com.nanyin.nacos.search.services.CacheWriteAccess::class)

package com.nanyin.nacos.search.services.operations

import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.psi.ConfigKeyExtractor
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Large-data live perf: 1000+ configs, ~10k lines each (seeded into
 * [NACOS_LIVE_LARGE_NS], default `perf-bulk-1`).
 *
 * Separates what scales with **count** (namespace index = summaries only) from
 * what scales with **body size** (detail fetch, cache payload, key extract).
 */
@TestApplication
class LiveLargeDataPerfTest {

    @Test
    fun `large namespace index detail and env switch`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V1_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V1_ENDPOINT not set; skipping")
        val ns = System.getenv("NACOS_LIVE_LARGE_NS") ?: "perf-bulk-1"
        val minCount = System.getenv("NACOS_LIVE_LARGE_MIN_COUNT")?.toIntOrNull() ?: 1000

        val cache = CacheService(InMemoryCacheStore()).also { it.awaitLoadCompleted() }
        val api = NacosApiService(
            sessionGenerationOverride = SessionGenerationState(
                currentEpoch = { 0L },
                isEntombed = { false }
            )
        )
        val envA = context(endpoint!!, "large-env-a")
        val envB = context(endpoint, "large-env-b")
        val report = StringBuilder()
        report.appendLine("LIVE_LARGE_PERF endpoint=$endpoint ns=$ns minCount=$minCount")

        val rt = Runtime.getRuntime()
        fun heapMb() = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)

        val heapBeforeIndex = heapMb()
        var indexed = 0
        val coldIndexMs = timeMs {
            val load = api.loadNamespace(ns, useCache = false, envA).getOrThrow()
            assertTrue(load.completeness == DatasetCompleteness.COMPLETE, "completeness=${load.completeness}")
            indexed = load.configurations.size
            assumeTrue(indexed >= minCount, "need >=$minCount configs in $ns, got $indexed — seed first")
            cache.applyMutation(
                CacheMutation.ReplaceNamespaceIndex(envA.identity, ns, load.configurations, TTL_MS),
                api.operationGateway().beginObservation()
            )
        }
        report.appendLine(
            "BENCH large_cold_index ns=$ns count=$indexed ms=$coldIndexMs " +
                "heapMb=${heapMb()} deltaMb=${heapMb() - heapBeforeIndex}"
        )

        val warmIndexMs = timeMs {
            assertTrue(cache.getNamespaceIndex(envA.identity, ns)!!.size >= minCount)
        }
        report.appendLine("BENCH large_warm_index_read ms=$warmIndexMs")

        val sampleIds = (1..DETAIL_SAMPLE).map { "bulk-large-%04d.yaml".format(it) }
        val heapBeforeDetails = heapMb()
        var bytesCached = 0L
        val coldDetailMs = timeMs {
            coroutineScope {
                sampleIds.map { dataId ->
                    async {
                        api.getConfiguration(
                            dataId = dataId,
                            group = "DEFAULT_GROUP",
                            namespaceId = ns,
                            useCache = false,
                            forceRefresh = true,
                            operationContext = envA
                        ).getOrThrow().value
                    }
                }.awaitAll().forEach { cfg ->
                    requireNotNull(cfg) { "missing detail" }
                    val lines = cfg.content.count { it == '\n' } + 1
                    assertTrue(lines >= 9_000, "${cfg.dataId} lines=$lines")
                    bytesCached += cfg.content.length.toLong()
                    cache.applyMutation(
                        CacheMutation.WriteDetail(envA.identity, ns, cfg, TTL_MS),
                        api.operationGateway().beginObservation()
                    )
                }
            }
        }
        report.appendLine(
            "BENCH large_cold_detail_x$DETAIL_SAMPLE ms=$coldDetailMs " +
                "contentBytes=$bytesCached heapMb=${heapMb()} deltaMb=${heapMb() - heapBeforeDetails}"
        )

        val warmDetailMs = timeMs {
            for (dataId in sampleIds) {
                val hit = cache.getConfigDetail(envA.identity, ns, dataId, "DEFAULT_GROUP")
                assertTrue(hit != null && hit.content.length > 100_000, "warm miss $dataId")
            }
        }
        report.appendLine("BENCH large_warm_detail_x$DETAIL_SAMPLE ms=$warmDetailMs")

        val one = cache.getConfigDetail(envA.identity, ns, sampleIds.first(), "DEFAULT_GROUP")!!
        var keyCount = 0
        val extractMs = timeMs {
            keyCount = ConfigKeyExtractor.extract(one).size
            assertTrue(keyCount > 1000, "expected many yaml keys, got $keyCount")
        }
        report.appendLine(
            "BENCH large_key_extract lines≈${one.content.count { it == '\n' } + 1} keys=$keyCount ms=$extractMs"
        )

        assertTrue(cache.getNamespaceIndex(envB.identity, ns) == null)
        assertTrue(cache.getConfigDetail(envB.identity, ns, sampleIds.first(), "DEFAULT_GROUP") == null)
        report.appendLine("BENCH large_env_isolation ok")

        val coldBMs = timeMs {
            val load = api.loadNamespace(ns, useCache = false, envB).getOrThrow()
            assertTrue(load.configurations.size >= minCount)
            cache.applyMutation(
                CacheMutation.ReplaceNamespaceIndex(envB.identity, ns, load.configurations, TTL_MS),
                api.operationGateway().beginObservation()
            )
        }
        report.appendLine("BENCH large_cold_index env=B count>=$minCount ms=$coldBMs")

        val backAMs = timeMs {
            assertTrue(cache.getNamespaceIndex(envA.identity, ns)!!.size >= minCount)
            assertTrue(cache.getConfigDetail(envA.identity, ns, sampleIds.first(), "DEFAULT_GROUP") != null)
        }
        report.appendLine("BENCH large_env_switch_back_A warm ms=$backAMs")

        assertTrue(coldIndexMs < 60_000, "cold index ${coldIndexMs}ms")
        assertTrue(warmIndexMs < 100, "warm index ${warmIndexMs}ms")
        assertTrue(warmDetailMs < 500, "warm details ${warmDetailMs}ms")
        assertTrue(backAMs < 100, "switch back ${backAMs}ms")

        println(report.toString())
        val out = java.io.File("build/reports/live-large-data-perf.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
    }

    private fun context(rawEndpoint: String, profileId: String): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse(rawEndpoint).getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
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

    private inline fun timeMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    companion object {
        private const val TTL_MS = 5L * 60 * 1000
        private const val DETAIL_SAMPLE = 32
    }
}
