package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosApiGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResolvedGenerationLocatorTest {

    private val keys = ProfileAccessKeys(
        profileId = "dev",
        profileRevision = 2,
        accessRevision = 3,
        canonicalEndpoint = "https://nacos.example"
    )

    @Test
    fun `the session's resolved generation outranks the persisted last-known value`() {
        val locator = ResolvedGenerationLocator(
            sessionResolved = { NacosApiGeneration.V3 },
            lastKnown = { NacosApiGeneration.V1 }
        )

        assertEquals(NacosApiGeneration.V3, locator.locate(keys))
    }

    @Test
    fun `the last-known value locates the cache when this session has not resolved`() {
        val locator = ResolvedGenerationLocator(
            sessionResolved = { null },
            lastKnown = { NacosApiGeneration.V1 }
        )

        assertEquals(NacosApiGeneration.V1, locator.locate(keys))
    }

    @Test
    fun `neither source leaves the generation unknown`() {
        val locator = ResolvedGenerationLocator(
            sessionResolved = { null },
            lastKnown = { null }
        )

        assertEquals(NacosApiGeneration.UNKNOWN, locator.locate(keys))
    }

    @Test
    fun `an UNKNOWN answer from a source is not a resolution`() {
        val locator = ResolvedGenerationLocator(
            sessionResolved = { NacosApiGeneration.UNKNOWN },
            lastKnown = { NacosApiGeneration.V3 }
        )

        assertEquals(NacosApiGeneration.V3, locator.locate(keys))
    }

    @Test
    fun `both sources are asked with the complete access keys`() {
        val asked = mutableListOf<ProfileAccessKeys>()
        val locator = ResolvedGenerationLocator(
            sessionResolved = { asked += it; null },
            lastKnown = { asked += it; null }
        )

        locator.locate(keys)

        assertEquals(listOf(keys, keys), asked)
    }
}
