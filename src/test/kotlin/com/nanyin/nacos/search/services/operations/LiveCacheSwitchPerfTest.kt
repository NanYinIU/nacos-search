@file:OptIn(com.nanyin.nacos.search.services.CacheWriteAccess::class)

package com.nanyin.nacos.search.services.operations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.services.CacheMutation
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.IndexOutcome
import com.nanyin.nacos.search.services.IndexTrigger
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.NamespaceIndexCoordinator
import com.nanyin.nacos.search.services.NamespaceIndexKey
import com.nanyin.nacos.search.services.NamespaceIndexRequest
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import java.util.concurrent.TimeUnit

/**
 * Live performance harness against a real Nacos (gated by
 * [NACOS_LIVE_V1_ENDPOINT]). Measures namespace / environment switch cost and
 * whether the cache absorbs repeat reads.
 *
 * Seed expectation (see docs/research playbook): public≈80, perf-dev/qa≈120,
 * perf-prod≈200 configs. Soft latency ceilings are generous so CI boxes don't
 * flake; the printed BENCH lines are the real artifact.
 */
@TestApplication
class LiveCacheSwitchPerfTest {

    @Test
    fun `namespace and environment switch cache impact`() = runBlocking {
        val endpoint = System.getenv("NACOS_LIVE_V1_ENDPOINT")
        assumeTrue(endpoint != null, "NACOS_LIVE_V1_ENDPOINT not set; skipping live perf")

        val store = InMemoryCacheStore()
        val cache = CacheService(store)
        cache.awaitLoadCompleted()
        val api = NacosApiService(
            sessionGenerationOverride = SessionGenerationState(
                currentEpoch = { 0L },
                isEntombed = { false }
            )
        )
        // Coordinator writes through the application CacheService by default via
        // NacosApiService — for isolated measurement we drive loadNamespace +
        // applyMutation ourselves, mirroring what the coordinator does.
        val envA = context(endpoint!!, profileId = "perf-env-a")
        val envB = context(endpoint, profileId = "perf-env-b")

        val namespaces = listOf(
            null to 80,           // public
            "perf-dev" to 120,
            "perf-qa" to 120,
            "perf-prod" to 200
        )

        val report = StringBuilder()
        report.appendLine("LIVE_PERF endpoint=$endpoint")

        // --- Cold namespace loads under env A ---
        val coldA = mutableMapOf<String, Long>()
        for ((ns, expectedMin) in namespaces) {
            val ms = timeMs {
                val load = api.loadNamespace(ns, useCache = false, envA).getOrThrow()
                assertEquals(DatasetCompleteness.COMPLETE, load.completeness, "cold load $ns")
                assertTrue(load.configurations.size >= expectedMin,
                    "expected >=$expectedMin in $ns, got ${load.configurations.size}")
                val observation = api.operationGateway().beginObservation()
                cache.applyMutation(
                    CacheMutation.ReplaceNamespaceIndex(
                        envA.identity,
                        ns,
                        load.configurations,
                        TTL_MS
                    ),
                    observation
                )
            }
            coldA[nsLabel(ns)] = ms
            report.appendLine("BENCH cold_index env=A ns=${nsLabel(ns)} ms=$ms count>=$expectedMin")
        }

        // --- Warm cache reads (no network) after env-A loads ---
        for ((ns, _) in namespaces) {
            val ms = timeMs {
                val hit = cache.getNamespaceIndex(envA.identity, ns)
                assertTrue(hit != null && hit.isNotEmpty(), "warm miss for ${nsLabel(ns)}")
            }
            report.appendLine("BENCH warm_cache_read env=A ns=${nsLabel(ns)} ms=$ms")
            assertTrue(ms < 50, "warm cache read ${nsLabel(ns)} took ${ms}ms")
        }

        // --- Switch namespaces under env A: re-read cached indexes ---
        val switchOrder = listOf<String?>("perf-prod", "perf-dev", null, "perf-qa", "perf-prod")
        val switchMs = mutableListOf<Long>()
        for (ns in switchOrder) {
            val ms = timeMs {
                val hit = cache.getNamespaceIndex(envA.identity, ns)
                assertTrue(hit != null && hit.isNotEmpty())
            }
            switchMs += ms
            report.appendLine("BENCH ns_switch env=A to=${nsLabel(ns)} ms=$ms")
        }
        assertTrue(switchMs.maxOrNull()!! < 50, "namespace switch cache reads should stay sub-50ms")

        // --- Environment switch: env B must not see env A cache ---
        for ((ns, _) in namespaces) {
            assertNull(
                cache.getNamespaceIndex(envB.identity, ns),
                "env B must not see env A index for ${nsLabel(ns)}"
            )
        }
        report.appendLine("BENCH env_isolation ok (B misses all A indexes)")

        // --- Cold loads under env B (same server, isolated identity) ---
        val coldB = mutableMapOf<String, Long>()
        for ((ns, expectedMin) in namespaces.take(2)) { // public + perf-dev enough
            val ms = timeMs {
                val load = api.loadNamespace(ns, useCache = false, envB).getOrThrow()
                assertTrue(load.configurations.size >= expectedMin)
                val observation = api.operationGateway().beginObservation()
                cache.applyMutation(
                    CacheMutation.ReplaceNamespaceIndex(envB.identity, ns, load.configurations, TTL_MS),
                    observation
                )
            }
            coldB[nsLabel(ns)] = ms
            report.appendLine("BENCH cold_index env=B ns=${nsLabel(ns)} ms=$ms")
        }

        // Env switch back to A: still warm
        val backToA = timeMs {
            assertTrue(cache.getNamespaceIndex(envA.identity, "perf-prod")!!.isNotEmpty())
        }
        report.appendLine("BENCH env_switch_back_to_A warm_read ms=$backToA")
        assertTrue(backToA < 50)

        // Soft ceiling: cold index of 200 configs shouldn't take forever on localhost
        val prodCold = coldA["perf-prod"]!!
        assertTrue(prodCold < 30_000, "perf-prod cold index ${prodCold}ms exceeds 30s soft ceiling")

        // Coordinator path smoke: single-flight + MANUAL_REFRESH cutoff still work live
        val appCache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val coordinator = NamespaceIndexCoordinator(api, appCache)
        val coordReq = NamespaceIndexRequest(
            key = NamespaceIndexKey(envA.identity, "perf-qa"),
            cacheTtlMillis = TTL_MS,
            operationContext = envA
        )
        val coordMs = timeMs {
            val outcome = coordinator.requestIndex(coordReq, IndexTrigger.MANUAL_REFRESH)
            assertTrue(outcome is IndexOutcome.Complete || outcome is IndexOutcome.Partial,
                "coordinator outcome=$outcome")
        }
        report.appendLine("BENCH coordinator_manual_refresh ns=perf-qa ms=$coordMs")

        println(report.toString())
        // Keep a copy under build/ for the agent to harvest
        val out = java.io.File("build/reports/live-cache-switch-perf.txt")
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

    private fun nsLabel(ns: String?) = ns?.ifEmpty { "public" } ?: "public"

    private inline fun timeMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    companion object {
        private const val TTL_MS = 5L * 60 * 1000
    }
}
