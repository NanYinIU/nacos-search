package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class GenerationProbeFlightTest {

    @Test
    fun `concurrent joiners share a single probe`() = runBlocking {
        val flight = GenerationProbeFlight()
        val probes = AtomicInteger(0)
        val key = GenerationProbeKey(
            profileId = "p",
            profileRevision = 1,
            accessRevision = 1,
            canonicalEndpoint = "https://nacos.example",
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )

        val results = (1..8).map {
            async {
                flight.joinOrProbe(key) {
                    probes.incrementAndGet()
                    delay(40)
                    Result.success(NacosApiGeneration.V3)
                }
            }
        }.awaitAll()

        assertEquals(1, probes.get())
        assertTrue(results.all { it.getOrThrow() == NacosApiGeneration.V3 })
    }

    @Test
    fun `sequential probes after flight release re-run when session does not retain the result`() = runBlocking {
        val flight = GenerationProbeFlight()
        val probes = AtomicInteger(0)
        val key = GenerationProbeKey(
            profileId = "p",
            profileRevision = 1,
            accessRevision = 1,
            canonicalEndpoint = "https://nacos.example",
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )

        flight.joinOrProbe(key) {
            probes.incrementAndGet()
            Result.success(NacosApiGeneration.V3)
        }
        flight.joinOrProbe(key) {
            probes.incrementAndGet()
            Result.success(NacosApiGeneration.V3)
        }

        assertEquals(2, probes.get())
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.jupiter.api.Assertions.assertTrue(condition)
    }
}
