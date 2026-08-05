package com.nanyin.nacos.search.services.operations

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.writeDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The operation layer's record of what a read observed about one configuration
 * detail — today, that it is gone. The subject is which observation sequence
 * the mutation carries: the one the read took, never a fresh one (issue #65 /
 * ADR-0047).
 */
class ObservedDetailRecorderTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = testIdentity()

    private fun config(dataId: String, content: String) =
        NacosConfiguration(dataId, "DEFAULT_GROUP", "dev", content, "properties")

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
