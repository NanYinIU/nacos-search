package com.nanyin.nacos.search.services.operations

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.writeDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The operation layer's record of what a read observed about one configuration
 * detail. The subject is which observation sequence the mutation carries: the
 * one the read took, never a fresh one (issue #65 / ADR-0047).
 */
class ObservedDetailRecorderTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = testIdentity()
    private val ttl = 300_000L

    private fun config(dataId: String, content: String) =
        NacosConfiguration(dataId, "DEFAULT_GROUP", "dev", content, "properties")

    @Test
    fun `a returned detail is recorded at its coordinate`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        assertTrue(
            ObservedDetailRecorder(cacheService)
                .recordDetail(identity, "dev", config("app.properties", "v=1"), ttl, observation = 4)
        )

        assertEquals(
            "v=1",
            cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `a detail read that started earlier does not displace a newer one`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.writeDetail(identity, "dev", config("app.properties", "v=new"), observation = 7)

        assertFalse(
            ObservedDetailRecorder(cacheService)
                .recordDetail(identity, "dev", config("app.properties", "v=old"), ttl, observation = 3)
        )

        assertEquals(
            "v=new",
            cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `recording a detail the gateway already wrote under the same sequence is dropped`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        // The gateway's own write for this read.
        cacheService.writeDetail(identity, "dev", config("app.properties", "v=gateway"), observation = 5)

        // The same read reported again from a layer that reads under this
        // identity. It cannot restamp the entry, so a genuinely newer read that
        // started at 6 still wins.
        assertFalse(
            ObservedDetailRecorder(cacheService)
                .recordDetail(identity, "dev", config("app.properties", "v=again"), ttl, observation = 5)
        )

        assertEquals(
            "v=gateway",
            cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `an authoritative not-found deletes the cached detail`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.writeDetail(identity, "dev", config("gone.properties", "# cached"), observation = 5)

        ObservedDetailRecorder(cacheService)
            .recordMissing(identity, "dev", "gone.properties", "DEFAULT_GROUP", observation = 6)

        assertNull(cacheService.getConfigDetail(identity, "dev", "gone.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `a read that started after the not-found restores a recreated configuration`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.writeDetail(identity, "dev", config("recreated.properties", "# old"), observation = 3)

        // The delete carries the not-found read's own sequence, so a read that
        // started later still lands. Taking a fresh sequence here would lock the
        // recreated configuration out of the cache.
        ObservedDetailRecorder(cacheService)
            .recordMissing(identity, "dev", "recreated.properties", "DEFAULT_GROUP", observation = 4)
        cacheService.writeDetail(identity, "dev", config("recreated.properties", "# new"), observation = 9)

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "recreated.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `a not-found that started before a newer read does not delete what that read confirmed`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.writeDetail(identity, "dev", config("live.properties", "# confirmed"), observation = 8)

        ObservedDetailRecorder(cacheService)
            .recordMissing(identity, "dev", "live.properties", "DEFAULT_GROUP", observation = 2)

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "live.properties", "DEFAULT_GROUP"))
    }
}
