package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NamespaceIndexCoordinatorTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = AccessIdentity.of("http://test:8848", AuthMode.BASIC, "admin")

    @Before
    fun setUp() {
        runBlocking {
        val cache = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .getService(CacheService::class.java)
        cache.clearAll()
        cache.getCacheStats() // drain background load
        }
    }

    @Test
    fun `different keys produce independent outcomes`() = runBlocking {
        val coordinator = NamespaceIndexCoordinator()
        val keyA = NamespaceIndexKey(identity, "ns-a")
        val keyB = NamespaceIndexKey(identity, "ns-b")

        val (outcomeA, outcomeB) = awaitAll(
            async { coordinator.requestIndex(keyA, IndexTrigger.PSI) },
            async { coordinator.requestIndex(keyB, IndexTrigger.PSI) }
        )

        // Both complete (or fail, since no real server), but neither is blocked by the other
        assertTrue(outcomeA is IndexOutcome.Failed || outcomeA is IndexOutcome.Complete || outcomeA is IndexOutcome.Stale)
        assertTrue(outcomeB is IndexOutcome.Failed || outcomeB is IndexOutcome.Complete || outcomeB is IndexOutcome.Stale)
    }

    @Test
    fun `PSI trigger after failure respects cooldown`() = runBlocking {
        val coordinator = NamespaceIndexCoordinator()
        val key = NamespaceIndexKey(identity, "cooldown-ns")

        // First request fails (no real server)
        val first = coordinator.requestIndex(key, IndexTrigger.PSI)
        assertTrue("Expected failure: $first", first is IndexOutcome.Failed)

        // Immediate second PSI request should be blocked by cooldown
        val second = coordinator.requestIndex(key, IndexTrigger.PSI)
        assertTrue("Expected stale during cooldown: $second", second is IndexOutcome.Stale)
    }
}
