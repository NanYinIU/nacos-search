package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.writeDetail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Drives real cache mutations against the real index service: the derived key
 * index must rebuild exactly when the snapshot's version moves, and hold when
 * it does not. Nothing here fakes the index service — the thing under test is
 * the rebuild decision itself (issue #64).
 *
 * Most cases dispatch rebuilds on [Dispatchers.Unconfined] so a scheduled one
 * has run by the time the scheduling call returns; the case that is about what
 * happens *while* a rebuild is outstanding uses [QueueingDispatcher] instead.
 * Either way the service builds and owns the scope, as it does in production.
 */
class NacosKeyIndexServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = testIdentity("http://nacos:8848")

    private fun indexService() = NacosKeyIndexService(Dispatchers.Unconfined)

    /**
     * The background persistent load publishes once it finishes, which advances
     * the version. Await it so a case's assertions are about the mutations it
     * makes rather than about when startup happened to land.
     */
    private suspend fun loadedCache(clock: () -> Long = System::currentTimeMillis): CacheService =
        CacheService(clock, InMemoryCacheStore()).also { it.awaitLoadCompleted() }

    private fun config(dataId: String, content: String, tenantId: String? = null) =
        NacosConfiguration(dataId, "DEFAULT_GROUP", tenantId, content, "properties")

    @Test
    fun `the index rebuilds when the snapshot version moves`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))

        val first = service.currentIndex(cache.snapshot(identity))!!

        cache.writeDetail(identity, null, config("other.properties", "retries=3\n"))
        val second = service.currentIndex(cache.snapshot(identity))!!

        assertNotSame(first, second)
        assertEquals(setOf("timeout"), first.definitionsByKey.keys)
        assertEquals(setOf("timeout", "retries"), second.definitionsByKey.keys)
    }

    @Test
    fun `the index holds when the snapshot version does not move`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"), observation = 20)

        val first = service.currentIndex(cache.snapshot(identity))!!
        val unchanged = service.currentIndex(cache.snapshot(identity))!!

        // A mutation the gate drops changes nothing, so neither does the index.
        assertFalse(cache.writeDetail(identity, null, config("app.properties", "timeout=2\n"), observation = 5))
        val afterGatedMutation = service.currentIndex(cache.snapshot(identity))!!

        assertSame(first, unchanged)
        assertSame(first, afterGatedMutation)
    }

    @Test
    fun `clearing the cache rebuilds the index empty`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        assertTrue(service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.isNotEmpty())

        cache.clearAll()

        assertTrue(service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.isEmpty())
    }

    @Test
    fun `the index service owns its scope and stops rebuilding once disposed`() = runBlocking {
        val cache = loadedCache()
        val service = NacosKeyIndexService(Dispatchers.Unconfined)
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        val built = service.currentIndex(cache.snapshot(identity))!!

        // Clearing the cache cancels nothing here: the index's lifecycle is its
        // own, so its scope survives a cache the user emptied.
        cache.clearAll()
        service.dispose()

        cache.writeDetail(identity, null, config("late.properties", "late=1\n"))
        assertSame(built, service.currentIndex(cache.snapshot(identity)))
    }

    @Test
    fun `an index built for another identity is not served`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        val other = testIdentity("http://other:8848")
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        assertEquals(setOf("timeout"), service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.keys)

        // The index built for `identity` must never stand in for another one:
        // the ask for `other` gets that identity's own (empty) index or nothing.
        val served = service.currentIndex(cache.snapshot(other))
        assertEquals(other, served?.accessIdentity ?: other)
        assertTrue(served?.definitionsByKey.orEmpty().isEmpty())
    }

    @Test
    fun `refreshIndex rebuilds from the snapshot it is handed`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        assertTrue(service.refreshIndex(cache.snapshot(identity)).definitionsByKey.isEmpty())

        cache.writeDetail(identity, null, config("app.properties", "db.url=jdbc:test\n"))

        val rebuilt = service.refreshIndex(cache.snapshot(identity))
        assertEquals(setOf("db.url"), rebuilt.definitionsByKey.keys)
        assertEquals(cache.snapshot(identity).version, rebuilt.version)
    }

    @Test
    fun `a rebuild that started earlier does not displace a newer index`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        val older = cache.snapshot(identity)
        cache.writeDetail(identity, null, config("other.properties", "retries=3\n"))
        val newer = service.refreshIndex(cache.snapshot(identity))

        // The older rebuild finishes last; letting it win would make the next
        // decision see a version that moved backwards and rebuild for nothing.
        service.refreshIndex(older)

        assertSame(newer, service.currentIndex(cache.snapshot(identity)))
    }

    @Test
    fun `an ask that arrives while a rebuild is queued is picked up, not dropped`() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val cache = loadedCache()
        val service = NacosKeyIndexService(dispatcher)
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        service.ensureIndexBuilt(cache.snapshot(identity))

        // Arrives while the first rebuild is still waiting to run. ensureIndexBuilt
        // is fire-and-forget, so a dropped ask has no later pass to heal on.
        cache.writeDetail(identity, null, config("other.properties", "retries=3\n"))
        service.ensureIndexBuilt(cache.snapshot(identity))
        dispatcher.drain()

        assertEquals(
            setOf("timeout", "retries"),
            service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.keys
        )
    }

    @Test
    fun `every hit in one decision is judged at the deciding snapshot's instant`() = runBlocking {
        var now = 2_000_000L
        val cache = loadedCache { now }
        val service = indexService()
        cache.writeDetail(identity, null, config("a.properties", "shared=1\n"), ttl = 100L)
        cache.writeDetail(identity, null, config("b.properties", "shared=2\n"), ttl = 100L)
        service.refreshIndex(cache.snapshot(identity))

        val whileFresh = service.resolveCurrentState(cache.snapshot(identity), "shared")
        now += 101L
        val whenStale = service.resolveCurrentState(cache.snapshot(identity), "shared")

        assertEquals(ConfigReferenceStatus.RESOLVED, whileFresh.status)
        assertEquals(
            listOf(CacheService.DetailFreshness.FRESH, CacheService.DetailFreshness.FRESH),
            whileFresh.hits.map { it.freshness }
        )
        assertEquals(ConfigReferenceStatus.STALE, whenStale.status)
        assertEquals(
            listOf(CacheService.DetailFreshness.STALE, CacheService.DetailFreshness.STALE),
            whenStale.hits.map { it.freshness }
        )
    }

    @Test
    fun `an unbuilt index is unavailable rather than unresolved`() = runBlocking {
        val cache = loadedCache()
        val service = NacosKeyIndexService(Dispatchers.Unconfined)
        service.dispose()

        val resolution = service.resolveCurrentState(cache.snapshot(identity), "db.url")

        assertEquals(ConfigReferenceStatus.UNAVAILABLE, resolution.status)
        assertNull(service.currentIndex(cache.snapshot(identity)))
    }
}

/** Runs nothing until [drain], so a rebuild can be observed while still queued. */
private class QueueingDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queued) { queued.addLast(block) }
    }

    fun drain() {
        while (true) {
            val next = synchronized(queued) { queued.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}
