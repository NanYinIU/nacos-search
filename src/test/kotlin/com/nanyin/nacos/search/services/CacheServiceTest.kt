package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives the real cache module through its public interface against an
 * in-memory [CacheStore]. Every case owns its store, so no case shares an
 * on-disk directory or an application-properties instance with another, and
 * "restart" is simply a second [CacheService] over the same store.
 */
class CacheServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `CacheService is Disposable and dispose cancels its scope`() {
        val cacheService = CacheService(InMemoryCacheStore())
        assertTrue(cacheService is com.intellij.openapi.Disposable)
        cacheService.dispose()
        // After dispose the scope is cancelled; snapshot is still a valid volatile read
        // (it may or may not have data depending on background load timing).
        // The key assertion is that dispose() does not throw and the service
        // does not crash on a subsequent non-suspending read.
    }

    @Test
    fun `configuration snapshot is immediately callable without a coroutine`() {
        val cacheService = CacheService(InMemoryCacheStore())

        assertTrue(cacheService.configurationSnapshot(null).isEmpty())
    }

    @Test
    fun `configuration snapshot excludes expired details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.putConfigDetail(
            "http://nacos:8848", null,
            NacosConfiguration("expired.properties", "DEFAULT_GROUP", null, "k=v", "properties"),
            ttl = -1L
        )

        assertTrue(cacheService.configurationSnapshot(null).isEmpty())
    }

    @Test
    fun `expired detail survives cleanup and reload for stale navigation`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = CacheService(store)
        val cached = NacosConfiguration(
            "stale.properties",
            "DEFAULT_GROUP",
            "dev",
            "feature=old",
            "properties"
        )
        first.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = cached,
            ttl = -1L
        )

        first.cleanupExpiredEntries()

        assertEquals(
            cached,
            first.getConfigDetail(
                "http://nacos:8848",
                "dev",
                "stale.properties",
                "DEFAULT_GROUP",
                allowStale = true
            )
        )

        val reloaded = CacheService(store)
        assertEquals(
            cached,
            reloaded.getConfigDetail(
                "http://nacos:8848",
                "dev",
                "stale.properties",
                "DEFAULT_GROUP",
                allowStale = true
            )
        )
    }

    @Test
    fun `navigation snapshot keeps details across fresh stale and deep-stale ages`() = runBlocking {
        var now = 1_000_000L
        val cacheService = CacheService({ now }, InMemoryCacheStore())
        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration(
                "age.properties",
                "DEFAULT_GROUP",
                "dev",
                "feature=true",
                "properties"
            ),
            ttl = 100L
        )

        assertEquals(
            CacheService.DetailFreshness.FRESH,
            cacheService.configurationNavigationSnapshot("http://nacos:8848").single().freshness
        )

        now += 101L
        assertEquals(
            CacheService.DetailFreshness.STALE,
            cacheService.configurationNavigationSnapshot("http://nacos:8848").single().freshness
        )

        now = 1_000_000L + 8L * 24 * 60 * 60 * 1000
        assertEquals(
            CacheService.DetailFreshness.DEEP_STALE,
            cacheService.configurationNavigationSnapshot("http://nacos:8848").single().freshness
        )
    }

    @Test
    fun `configuration snapshot scopes by normalized server url`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.putConfigDetail(
            "http://one:8848/", null,
            NacosConfiguration("one.properties", "DEFAULT_GROUP", null, "k=one", "properties")
        )
        cacheService.putConfigDetail(
            "http://two:8848", null,
            NacosConfiguration("two.properties", "DEFAULT_GROUP", null, "k=two", "properties")
        )

        assertEquals(listOf("one.properties"), cacheService.configurationSnapshot(" http://one:8848/ ").map { it.dataId })
    }

    @Test
    fun `configuration snapshot publishes namespace batches atomically`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        val configurations = (1..100).map {
            NacosConfiguration("app$it.properties", "DEFAULT_GROUP", "dev", "k$it=v", "properties")
        }
        val observedSizes = ConcurrentHashMap.newKeySet<Int>()

        coroutineScope {
            val writer = launch {
                cacheService.putNamespaceIndex("http://nacos:8848", "dev", configurations)
            }
           while (!writer.isCompleted) {
               observedSizes += cacheService.getNamespaceIndex("http://nacos:8848", "dev")?.size ?: 0
               yield()
           }
            writer.join()
        }
        observedSizes += cacheService.getNamespaceIndex("http://nacos:8848", "dev")?.size ?: 0

        // Issue #52: index write is atomic w.r.t. the summary set, and does not
        // seed the detail snapshot.
        assertTrue(observedSizes.all { it == 0 || it == configurations.size })
        assertEquals(configurations.size, cacheService.getNamespaceIndex("http://nacos:8848", "dev")?.size)
        assertEquals(0, cacheService.configurationSnapshot("http://nacos:8848").size)
    }

    @Test
    fun `complete namespace index replacement does not delete or seed details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        // Explicit detail writes (e.g. prior prefetch / browse) stay independent
        // of the summary index (issue #52).
        cacheService.putConfigDetail(
            "http://nacos:8848",
            "dev",
            NacosConfiguration("keep.properties", "DEFAULT_GROUP", "dev", "v=old"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            "http://nacos:8848",
            "dev",
            NacosConfiguration("orphan.properties", "DEFAULT_GROUP", "dev", "gone=true"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            "http://nacos:8848",
            "prod",
            NacosConfiguration("prod.properties", "DEFAULT_GROUP", "prod", "safe=true"),
            ttl = 60_000L
        )

        cacheService.putNamespaceIndex(
            "http://nacos:8848",
            "dev",
            listOf(NacosConfiguration("keep.properties", "DEFAULT_GROUP", "dev", "v=new")),
            ttl = 60_000L
        )

        // Index write neither seeds nor purges details.
        assertEquals(
            "v=old",
            cacheService.getConfigDetail(
                "http://nacos:8848",
                "dev",
                "keep.properties",
                "DEFAULT_GROUP"
            )?.content
        )
        assertEquals(
            "gone=true",
            cacheService.getConfigDetail(
                "http://nacos:8848",
                "dev",
                "orphan.properties",
                "DEFAULT_GROUP",
                allowStale = true
            )?.content
        )
        assertEquals(
            "safe=true",
            cacheService.getConfigDetail(
                "http://nacos:8848",
                "prod",
                "prod.properties",
                "DEFAULT_GROUP"
            )?.content
        )
        assertEquals(
            setOf("keep.properties"),
            cacheService.namespaceIndexState("http://nacos:8848", "dev")?.dataIds
        )
    }

   @Test
   fun `entry is fresh before TTL, stale after, and deep-stale after seven days`() {
       val now = System.currentTimeMillis()
       val fresh = CacheService.CacheEntry(
           type = CacheService.CacheEntryType.CONFIG_DETAIL,
           data = NacosConfiguration("app", "g", null, "k=v", "properties"),
           createdAt = now,
           ttlMs = 300_000L,
           source = CacheService.CacheSource.REMOTE
       )
       assertTrue(!fresh.isExpired())
       assertTrue(!fresh.isStale())
       assertTrue(!fresh.isDeepStale())

       val stale = fresh.copy(createdAt = now - 400_000L) // past TTL, within 7 days
       assertTrue(stale.isExpired())
       assertTrue(stale.isStale())
       assertTrue(!stale.isDeepStale())

       val ancient = fresh.copy(createdAt = now - 8L * 24 * 60 * 60 * 1000) // past 7 days
       assertTrue(ancient.isDeepStale())
   }

   @Test
   fun `configuration detail cache uses server namespace dataId and group`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        val config = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "dev",
            content = "feature=true",
            type = "yaml"
        )

        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = config,
            ttl = 60_000L
        )

        val cached = cacheService.getConfigDetail(
            serverUrl = "http://nacos:8848/",
            namespaceId = "dev",
            dataId = "app.yaml",
            group = "DEFAULT_GROUP"
        )

        assertEquals(config, cached)
        assertNull(
            cacheService.getConfigDetail(
                serverUrl = "http://other:8848",
                namespaceId = "dev",
                dataId = "app.yaml",
                group = "DEFAULT_GROUP"
            )
        )
    }

    @Test
    fun `all cached configurations can be scoped to one server`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        cacheService.putConfigDetail(
            serverUrl = "http://dev-nacos:8848",
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=dev", "properties"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            serverUrl = "http://prod-nacos:8848",
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=prod", "properties"),
            ttl = 60_000L
        )

        val devConfigs = cacheService.getAllCachedConfigurations("http://dev-nacos:8848/")

        assertEquals(1, devConfigs.size)
        assertEquals("app.name=dev", devConfigs.single().content)
    }

    @Test
    fun `list page cache expires independently from detail cache`() = runBlocking {
        var now = 1_000L
        val cacheService = CacheService({ now }, InMemoryCacheStore())

        val page = NacosApiService.ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                NacosApiService.ConfigItem(
                    id = "1",
                    dataId = "app.yaml",
                    group = "DEFAULT_GROUP",
                    content = "feature=true",
                    type = "yaml",
                    tenant = "dev"
                )
            )
        )
        val detail = NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true")

        cacheService.putListPage(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            requestKey = "page=1",
            response = page,
            ttl = 1L
        )
        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = detail,
            ttl = 60_000L
        )

       now += 5L

       assertNull(cacheService.getListPage("http://nacos:8848", "dev", "page=1"))
       assertEquals(
           detail,
           cacheService.getConfigDetail("http://nacos:8848", "dev", "app.yaml", "DEFAULT_GROUP")
       )

       val stats = cacheService.getCacheStats()
       assertEquals(1, stats.detailEntries)
        // Reads return null for the expired page (miss) and the live detail (hit).
        assertEquals(1, stats.cacheHits)
        assertEquals(1, stats.cacheMisses)

        // Reclamation of expired entries is delegated to the background/writer cleanup
        // path, so an explicit sweep is required to drop the stale page from memory.
        cacheService.cleanupExpiredEntries()
        val afterCleanup = cacheService.getCacheStats()
        assertEquals(1, afterCleanup.detailEntries)
        assertEquals(0, afterCleanup.listPageEntries)
   }

    @Test
    fun `cache modification count changes when detail cache changes`() = runBlocking {
       val cacheService = CacheService(InMemoryCacheStore())
       cacheService.getCacheStats() // drain background load before asserting
       val initial = cacheService.getModificationCount()

        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true"),
            ttl = 60_000L
        )

        val afterPut = cacheService.getModificationCount()
        assertEquals(initial + 1, afterPut)

        cacheService.clearAll()

        assertEquals(afterPut + 1, cacheService.getModificationCount())
    }

    @Test
    fun `namespace index preheat round-trips and is scoped by server and namespace`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        val configs = listOf(
            NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true", "yaml"),
            NacosConfiguration("db.properties", "DEFAULT_GROUP", "dev", "db.url=jdbc:test", "properties")
        )

        // Preheat: store the full namespace index (as NacosSearchPlugin does).
        cacheService.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configurations = configs,
            ttl = 60_000L
        )

        // searchWithLocalIndex reads it back as a single hit — no remote pull.
        val cached = cacheService.getNamespaceIndex("http://nacos:8848", "dev")
        assertEquals(2, cached?.size)

        // A different server or namespace must not leak the preheated index.
        assertNull(cacheService.getNamespaceIndex("http://other:8848", "dev"))
        assertNull(cacheService.getNamespaceIndex("http://nacos:8848", "prod"))
    }

    @Test
    fun `putNamespaceIndex does not seed individual config details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        cacheService.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = null,
            configurations = listOf(
                NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "timeout=30", "properties")
            ),
            ttl = 60_000L
        )

        // Issue #52: namespace index is summary-only; details arrive via
        // navigation prefetch or explicit reads, not from index writes.
        val detail = cacheService.getConfigDetail("http://nacos:8848", null, "app.properties", "DEFAULT_GROUP")
        assertNull(detail)
        val index = cacheService.getNamespaceIndex("http://nacos:8848", null)
        assertEquals(1, index?.size)
        assertEquals("", index!![0].content)
    }

    @Test
    fun `reads are not serialized behind a write lock`() = runBlocking {
        // getAllCachedConfigurations sits on the search hot path. It must not hold a
        // global write lock that serializes it against concurrent writers.
        val cacheService = CacheService(InMemoryCacheStore())
        repeat(200) { i ->
            cacheService.putConfigDetail(
                serverUrl = "http://nacos:8848",
                namespaceId = "ns",
                configuration = NacosConfiguration("app${'$'}i.properties", "DEFAULT_GROUP", "ns", "k=${'$'}i", "properties"),
                ttl = 60_000L
            )
        }

        val writer = launch {
            repeat(200) { i ->
                cacheService.putConfigDetail(
                    serverUrl = "http://nacos:8848",
                    namespaceId = "ns",
                    configuration = NacosConfiguration("other${'$'}i.properties", "DEFAULT_GROUP", "ns", "v=${'$'}i", "properties"),
                    ttl = 60_000L
                )
            }
        }

        withTimeout(20_000L) {
            coroutineScope {
                val readers = (1..50).map {
                    async { cacheService.getAllCachedConfigurations("http://nacos:8848") }
                }
                readers.awaitAll()
                writer.join()
            }
        }
    }

    @Test
    fun `putNamespaceIndex does not persist details for reload`() = runBlocking {
        val store = InMemoryCacheStore()
        CacheService(store).putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configurations = listOf(
                NacosConfiguration("seed.properties", "DEFAULT_GROUP", "dev", "seeded=true", "properties")
            ),
            ttl = 60_000L
        )

        // Issue #52: summary index must not leave detail payloads behind.
        val reloaded = CacheService(store)
        assertNull(reloaded.getConfigDetail("http://nacos:8848", "dev", "seed.properties", "DEFAULT_GROUP"))
        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `clearing the cache leaves no payload in the store`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = CacheService(store)
        first.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("gone.properties", "DEFAULT_GROUP", "dev", "x=1", "properties"),
            ttl = 60_000L
        )
        first.putListPage(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            requestKey = "page=1",
            response = NacosApiService.ConfigListResponse(0, 1, 1, emptyList()),
            ttl = 60_000L
        )
        // Confirms the payload was persisted and reloadable before clear.
        assertEquals("x=1", CacheService(store).getConfigDetail("http://nacos:8848", "dev", "gone.properties", "DEFAULT_GROUP")?.content)

        first.clearCache()

        // Nothing unreachable is left behind, and a fresh load cannot resurrect
        // an entry the user explicitly discarded.
        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadListPages().isEmpty())
        assertNull(CacheService(store).getConfigDetail("http://nacos:8848", "dev", "gone.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `removing a detail reclaims its payload from the store`() = runBlocking {
        val store = InMemoryCacheStore()
        val cacheService = CacheService(store)
        cacheService.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("removed.properties", "DEFAULT_GROUP", "dev", "x=1", "properties"),
            ttl = 60_000L
        )

        cacheService.removeConfigDetail("http://nacos:8848", "dev", "removed.properties", "DEFAULT_GROUP")

        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `a persisted detail whose key cannot prove ownership is discarded`() = runBlocking {
        // A pre-profile key names no profile id or access revision, so loading it
        // would make data visible to whichever profile happens to be active (ADR-0018).
        val store = InMemoryCacheStore()
        store.putDetail(
            "http://nacos:8848|dev|legacy.properties|DEFAULT_GROUP",
            CacheService.CacheEntry(
                type = CacheService.CacheEntryType.CONFIG_DETAIL,
                data = NacosConfiguration("legacy.properties", "DEFAULT_GROUP", "dev", "legacy=true", "properties"),
                createdAt = System.currentTimeMillis(),
                ttlMs = 60_000L,
                source = CacheService.CacheSource.REMOTE
            )
        )

        val migrated = CacheService(store)
        migrated.getCacheStats() // drain the background load

        assertNull(migrated.getConfigDetail("http://nacos:8848", "dev", "legacy.properties", "DEFAULT_GROUP"))
        // Discarded rather than left in the store where no sweep would reach it.
        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `single-key read resolves from the store without waiting for the full load`() = runBlocking {
        val store = InMemoryCacheStore()
        CacheService(store).putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("quick.properties", "DEFAULT_GROUP", "dev", "v=1", "properties"),
            ttl = 60_000L
        )

        // A fresh instance starts a background load; a single-key read (go-to-declaration
        // path) must return the persisted entry before that load can finish.
        val gated = GatedCacheStore(store)
        val reloaded = CacheService(gated)
        val detail = withTimeout(10_000L) {
            reloaded.getConfigDetail("http://nacos:8848", "dev", "quick.properties", "DEFAULT_GROUP")
        }
        assertEquals("v=1", detail?.content)
        gated.releaseFullLoad()
    }

    @Test
    fun `full read reflects the completed background load`() = runBlocking {
        val store = InMemoryCacheStore()
        CacheService(store).putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("full.properties", "DEFAULT_GROUP", "dev", "k=v", "properties"),
            ttl = 60_000L
        )

        val reloaded = CacheService(store)
        val all = reloaded.getAllCachedConfigurations("http://nacos:8848")
        assertTrue(all.any { it.dataId == "full.properties" && it.content == "k=v" })
    }

    @Test
    fun `complete namespace replacement is isolated by access identity`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        val alice = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "alice")
        val bob = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "bob")

        cacheService.putNamespaceIndex(
            alice,
            "dev",
            listOf(NacosConfiguration("alice.yaml", "DEFAULT_GROUP", "dev", "owner=alice", "yaml"))
        )
        cacheService.putNamespaceIndex(
            bob,
            "dev",
            listOf(NacosConfiguration("bob.yaml", "DEFAULT_GROUP", "dev", "owner=bob", "yaml"))
        )

        assertEquals(listOf("alice.yaml"), cacheService.getNamespaceIndex(alice, "dev")?.map { it.dataId })
        assertEquals(listOf("bob.yaml"), cacheService.getNamespaceIndex(bob, "dev")?.map { it.dataId })
        // Issue #52: namespace index no longer seeds the detail/navigation snapshot.
        // Isolation is proven by the summary index itself; detail writes remain
        // identity-scoped via putConfigDetail (covered elsewhere).
        assertTrue(cacheService.configurationNavigationSnapshot(alice).isEmpty())
        assertTrue(cacheService.configurationNavigationSnapshot(bob).isEmpty())
    }

    @Test
    fun `list page entry exposes creation timestamp for cache age`() = runBlocking {
        var now = 5_000_000L
        val cache = CacheService({ now }, InMemoryCacheStore())
        val identity = AccessIdentity.of("http://age-test", AuthMode.BASIC, "alice")
        val response = NacosApiService.ConfigListResponse(
            totalCount = 0,
            pageNumber = 1,
            pagesAvailable = 0,
            pageItems = emptyList()
        )
        cache.putListPage(identity, "public", "key-1", response, ttl = 60_000L)
        val entry = cache.getListPageEntry(identity, "public", "key-1")
        assertEquals(5_000_000L, entry?.createdAtMillis)

        now = 5_000_000L + 30_000L // still within TTL
        val stillFresh = cache.getListPageEntry(identity, "public", "key-1")
        assertEquals(5_000_000L, stillFresh?.createdAtMillis)
        cache.dispose()
    }

    @Test
    fun `namespace index entry exposes creation timestamp for cache age`() = runBlocking {
        // The local-index search path reports age from this timestamp; without it
        // a stale index rendered as WITHIN_TTL (issue #42).
        var now = 9_000_000L
        val cache = CacheService({ now }, InMemoryCacheStore())
        val identity = AccessIdentity.of("http://index-age-test", AuthMode.BASIC, "alice")
        cache.putNamespaceIndex(
            identity,
            "public",
            listOf(NacosConfiguration("app.yaml", "DEFAULT_GROUP", "public", "feature=true", "yaml")),
            ttl = 60_000L
        )

        val fresh = cache.getNamespaceIndexEntry(identity, "public")
        assertEquals(9_000_000L, fresh?.createdAtMillis)
        assertEquals(listOf("app.yaml"), fresh?.data?.map { it.dataId })

        // Past the entry TTL the read still reports the original creation time
        // when stale is allowed, so age is derived from the entry, not from now.
        now = 9_000_000L + 90_000L
        assertNull(cache.getNamespaceIndexEntry(identity, "public"))
        assertEquals(
            9_000_000L,
            cache.getNamespaceIndexEntry(identity, "public", allowStale = true)?.createdAtMillis
        )
        cache.dispose()
    }

    @Test
    fun `entombed profile rejects late cache writes while other identities persist`() = runBlocking {
        val tombstones = ProfileTombstoneRegistry()
        val cache = CacheService({ 0L }, tombstones, InMemoryCacheStore())
        val entombed = AccessIdentity.of("dev-srv", AuthMode.BASIC, "alice")
        val survivor = AccessIdentity.of("prod-srv", AuthMode.BASIC, "bob")

        tombstones.entomb(entombed.profileId, 1)

        cache.putConfigDetail(entombed, "ns", NacosConfiguration("d", "g", "ns", "late", "text"))
        cache.putNamespaceDetails(
            entombed,
            "ns",
            listOf(NacosConfiguration("partial", "g", "ns", "resurrected", "text"))
        )
        cache.putConfigDetail(survivor, "ns", NacosConfiguration("d", "g", "ns", "kept", "text"))
        cache.putNamespaceDetails(
            survivor,
            "ns",
            listOf(NacosConfiguration("partial", "g", "ns", "survivor-partial", "text"))
        )

        assertNull(cache.getConfigDetail(entombed, "ns", "d", "g"))
        assertNull(cache.getConfigDetail(entombed, "ns", "partial", "g"))
        assertEquals("kept", cache.getConfigDetail(survivor, "ns", "d", "g")?.content)
        assertEquals("survivor-partial", cache.getConfigDetail(survivor, "ns", "partial", "g")?.content)
        cache.dispose()
    }

    /** Holds the full load open so the single-key read path can be observed on its own. */
    private class GatedCacheStore(private val delegate: CacheStore) : CacheStore by delegate {
        private val opened = CompletableDeferred<Unit>()

        override suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>> {
            opened.await()
            return delegate.loadDetails()
        }

        fun releaseFullLoad() {
            opened.complete(Unit)
        }
    }
}
