package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.QueueingDispatcher
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.replaceNamespaceIndex
import com.nanyin.nacos.search.services.reportVisibility
import com.nanyin.nacos.search.services.writeDetail
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
    fun `publishing a rebuilt index notifies so gutters can leave the hollow state`() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val cache = loadedCache()
        var published = 0
        val service = NacosKeyIndexService(dispatcher) { published++ }

        // Gutter-shaped path: schedule against an empty cache, then land a detail
        // before the rebuild runs — the hollow icon was painted against the
        // previous empty index and must be told to re-analyze.
        service.ensureIndexBuilt(cache.snapshot(identity))
        cache.writeDetail(identity, null, config("theme.properties", "material.video.h5.url=https://x\n"))
        service.ensureIndexBuilt(cache.snapshot(identity))
        assertEquals(0, published)

        dispatcher.drain()

        assertTrue(published >= 1)
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            service.resolveCurrentState(
                cache.snapshot(identity),
                "material.video.h5.url",
                preferredDataId = "theme.properties"
            ).status
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

    // ── Visibility-aware reuse (issue #126) ──

    @Test
    fun `an index built before an identity block is not served and hides every hit`() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val cache = loadedCache()
        val service = NacosKeyIndexService(dispatcher)
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        service.ensureIndexBuilt(cache.snapshot(identity))
        dispatcher.drain()
        assertEquals(setOf("timeout"), service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.keys)

        reportVisibility(cache, identity, observation = 10_000, error = RemoteOperationError.Authentication(401))

        val blocked = cache.snapshot(identity)
        assertTrue(blocked.isAccessBlocked)
        assertTrue(blocked.configurations.isEmpty())

        // The stale index still holds the definition; serving it while the
        // rebuild is queued would be a temporary bypass.
        assertNull(service.currentIndex(blocked))
        assertTrue(service.resolve(blocked, "timeout").isEmpty())
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, service.resolveCurrentState(blocked, "timeout").status)

        dispatcher.drain()
        val rebuilt = service.currentIndex(blocked)!!
        assertTrue(rebuilt.accessBlocked)
        assertTrue(rebuilt.definitionsByKey.isEmpty())
        assertEquals(emptySet<String>(), rebuilt.blockedNamespaces)
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, service.resolveCurrentState(blocked, "timeout").status)
    }

    @Test
    fun `a matching success rebuilds the derivation and restores retained payloads`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, null, config("app.properties", "timeout=1\n"))
        service.refreshIndex(cache.snapshot(identity))
        assertTrue(service.resolve(cache.snapshot(identity), "timeout").isNotEmpty())

        reportVisibility(cache, identity, observation = 10_000, error = RemoteOperationError.Authentication(401))
        val blocked = cache.snapshot(identity)
        assertTrue(service.resolve(blocked, "timeout").isEmpty())
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, service.resolveCurrentState(blocked, "timeout").status)

        reportVisibility(cache, identity, observation = 10_001)
        val recovered = cache.snapshot(identity)
        assertFalse(recovered.isAccessBlocked)
        assertEquals(1, service.resolve(recovered, "timeout").size)
        assertEquals(ConfigReferenceStatus.RESOLVED, service.resolveCurrentState(recovered, "timeout").status)
    }

    @Test
    fun `an index built before a namespace block stops serving that namespace only`() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val cache = loadedCache()
        val service = NacosKeyIndexService(dispatcher)
        cache.writeDetail(identity, "team-a", config("a.properties", "a.key=1\n", tenantId = "team-a"))
        cache.writeDetail(identity, "team-b", config("b.properties", "b.key=2\n", tenantId = "team-b"))
        service.ensureIndexBuilt(cache.snapshot(identity))
        dispatcher.drain()
        assertEquals(
            setOf("a.key", "b.key"),
            service.currentIndex(cache.snapshot(identity))!!.definitionsByKey.keys
        )

        reportVisibility(cache, identity, observation = 10_000, namespaceId = "team-a", error = RemoteOperationError.Authorization(403))

        val blocked = cache.snapshot(identity)
        assertFalse(blocked.isAccessBlocked)
        assertEquals(setOf("team-a"), blocked.blockedNamespaces)
        assertTrue(blocked.configurations.none { it.configuration.tenantId == "team-a" })

        // While the rebuild is queued the stale index is not served at all.
        assertNull(service.currentIndex(blocked))

        dispatcher.drain()
        val rebuilt = service.currentIndex(blocked)!!
        assertEquals(setOf("b.key"), rebuilt.definitionsByKey.keys)
        assertEquals(setOf("team-a"), rebuilt.blockedNamespaces)

        // team-a: no cached hits and undecidable; team-b: unchanged.
        assertTrue(service.resolve(blocked, "a.key").isEmpty())
        val inBlocked = service.resolveCurrentState(blocked, "a.key", activeNamespaceId = "team-a")
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, inBlocked.status)
        assertTrue(inBlocked.hits.isEmpty())
        assertTrue(inBlocked.visibilityBlocked)
        assertEquals(1, service.resolve(blocked, "b.key").size)
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            service.resolveCurrentState(blocked, "b.key", activeNamespaceId = "team-b").status
        )
    }

    @Test
    fun `cross namespace resolution never yields a hidden namespace's hit`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, "team-a", config("a.properties", "shared=1\n", tenantId = "team-a"))
        cache.writeDetail(identity, "team-b", config("b.properties", "shared=2\n", tenantId = "team-b"))
        service.refreshIndex(cache.snapshot(identity))
        assertEquals(2, service.resolve(cache.snapshot(identity), "shared").size)

        reportVisibility(cache, identity, observation = 10_000, namespaceId = "team-a", error = RemoteOperationError.Authorization(403))
        val blocked = cache.snapshot(identity)

        val hits = service.resolve(blocked, "shared", activeNamespaceId = "team-b")
        assertEquals(1, hits.size)
        assertEquals("team-b", hits.single().namespaceId)
    }

    @Test
    fun `a fresh complete namespace index proves absence only when the namespace is visible`() = runBlocking {
        val cache = loadedCache()
        val service = indexService()
        cache.writeDetail(identity, "team-a", config("a.properties", "a.key=1\n", tenantId = "team-a"))
        cache.replaceNamespaceIndex(identity, "team-a", listOf(config("a.properties", "a.key=1\n", tenantId = "team-a")))
        service.refreshIndex(cache.snapshot(identity))
        assertFalse(service.isDataIdKnown(cache.snapshot(identity), "gone.properties", activeNamespaceId = "team-a"))

        reportVisibility(cache, identity, observation = 10_000, namespaceId = "team-a", error = RemoteOperationError.Authorization(403))
        val blocked = cache.snapshot(identity)

        // Absence can no longer be proven: the summary view for the blocked
        // namespace is filtered, so the data id stays "possibly present".
        assertTrue(service.isDataIdKnown(blocked, "gone.properties", activeNamespaceId = "team-a"))
        assertEquals(
            ConfigReferenceStatus.UNDECIDABLE,
            service.resolveCurrentState(blocked, "a.key", preferredDataId = "a.properties", activeNamespaceId = "team-a").status
        )
    }
}
