package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.testIdentity
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

    private val defaultIdentity = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)

    @Test
    fun `CacheService is Disposable and dispose cancels its scope`() {
        val cacheService = CacheService(InMemoryCacheStore())
        assertTrue(cacheService is com.intellij.openapi.Disposable)
        cacheService.dispose()
    }

    @Test
    fun `configuration snapshot is immediately callable without a coroutine`() {
        val cacheService = CacheService(InMemoryCacheStore())
        assertTrue(cacheService.configurationSnapshot(defaultIdentity).isEmpty())
    }

    @Test
    fun `configuration snapshot excludes expired details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.putConfigDetail(
            defaultIdentity, null,
            NacosConfiguration("expired.properties", "DEFAULT_GROUP", null, "k=v", "properties"),
            ttl = -1L
        )

        assertTrue(cacheService.configurationSnapshot(defaultIdentity).isEmpty())
    }

    @Test
    fun `expired detail survives reload for stale navigation`() = runBlocking {
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
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = cached,
            ttl = -1L
        )

        assertEquals(
            cached,
            first.getConfigDetail(
                defaultIdentity,
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
                defaultIdentity,
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
            identity = defaultIdentity,
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
            cacheService.configurationNavigationSnapshot(defaultIdentity).single().freshness
        )

        now += 101L
        assertEquals(
            CacheService.DetailFreshness.STALE,
            cacheService.configurationNavigationSnapshot(defaultIdentity).single().freshness
        )

        now = 1_000_000L + 8L * 24 * 60 * 60 * 1000
        assertEquals(
            CacheService.DetailFreshness.DEEP_STALE,
            cacheService.configurationNavigationSnapshot(defaultIdentity).single().freshness
        )
    }

    @Test
    fun `configuration snapshot scopes by access identity`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        val one = testIdentity("http://one:8848/")
        val two = testIdentity("http://two:8848")
        cacheService.putConfigDetail(
            one, null,
            NacosConfiguration("one.properties", "DEFAULT_GROUP", null, "k=one", "properties")
        )
        cacheService.putConfigDetail(
            two, null,
            NacosConfiguration("two.properties", "DEFAULT_GROUP", null, "k=two", "properties")
        )

        assertEquals(listOf("one.properties"), cacheService.configurationSnapshot(one).map { it.dataId })
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
                cacheService.putNamespaceIndex(defaultIdentity, "dev", configurations)
            }
            while (!writer.isCompleted) {
                observedSizes += cacheService.getNamespaceIndex(defaultIdentity, "dev")?.size ?: 0
                yield()
            }
            writer.join()
        }
        observedSizes += cacheService.getNamespaceIndex(defaultIdentity, "dev")?.size ?: 0

        // Issue #52: index write is atomic w.r.t. the summary set, and does not
        // seed the detail snapshot.
        assertTrue(observedSizes.all { it == 0 || it == configurations.size })
        assertEquals(configurations.size, cacheService.getNamespaceIndex(defaultIdentity, "dev")?.size)
        assertEquals(0, cacheService.configurationSnapshot(defaultIdentity).size)
    }

    @Test
    fun `complete namespace index replacement does not delete or seed details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.putConfigDetail(
            defaultIdentity,
            "dev",
            NacosConfiguration("keep.properties", "DEFAULT_GROUP", "dev", "v=old"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            defaultIdentity,
            "dev",
            NacosConfiguration("orphan.properties", "DEFAULT_GROUP", "dev", "gone=true"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            defaultIdentity,
            "prod",
            NacosConfiguration("prod.properties", "DEFAULT_GROUP", "prod", "safe=true"),
            ttl = 60_000L
        )

        cacheService.putNamespaceIndex(
            defaultIdentity,
            "dev",
            listOf(NacosConfiguration("keep.properties", "DEFAULT_GROUP", "dev", "v=new")),
            ttl = 60_000L
        )

        assertEquals(
            "v=old",
            cacheService.getConfigDetail(
                defaultIdentity,
                "dev",
                "keep.properties",
                "DEFAULT_GROUP"
            )?.content
        )
        assertEquals(
            "gone=true",
            cacheService.getConfigDetail(
                defaultIdentity,
                "dev",
                "orphan.properties",
                "DEFAULT_GROUP",
                allowStale = true
            )?.content
        )
        assertEquals(
            "safe=true",
            cacheService.getConfigDetail(
                defaultIdentity,
                "prod",
                "prod.properties",
                "DEFAULT_GROUP"
            )?.content
        )
        assertEquals(
            setOf("keep.properties"),
            cacheService.namespaceIndexState(defaultIdentity, "dev")?.dataIds
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

        val stale = fresh.copy(createdAt = now - 400_000L)
        assertTrue(stale.isExpired())
        assertTrue(stale.isStale())
        assertTrue(!stale.isDeepStale())

        val ancient = fresh.copy(createdAt = now - 8L * 24 * 60 * 60 * 1000)
        assertTrue(ancient.isDeepStale())
    }

    @Test
    fun `configuration detail cache uses identity namespace dataId and group`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        val config = NacosConfiguration(
            dataId = "app.yaml",
            group = "DEFAULT_GROUP",
            tenantId = "dev",
            content = "feature=true",
            type = "yaml"
        )

        cacheService.putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = config,
            ttl = 60_000L
        )

        val cached = cacheService.getConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            dataId = "app.yaml",
            group = "DEFAULT_GROUP"
        )

        assertEquals(config, cached)
        assertNull(
            cacheService.getConfigDetail(
                identity = testIdentity("http://other:8848"),
                namespaceId = "dev",
                dataId = "app.yaml",
                group = "DEFAULT_GROUP"
            )
        )
    }

    @Test
    fun `all cached configurations can be scoped to one access identity`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        val dev = testIdentity("http://dev-nacos:8848")
        val prod = testIdentity("http://prod-nacos:8848")

        cacheService.putConfigDetail(
            identity = dev,
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=dev", "properties"),
            ttl = 60_000L
        )
        cacheService.putConfigDetail(
            identity = prod,
            namespaceId = "public",
            configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=prod", "properties"),
            ttl = 60_000L
        )

        val devConfigs = cacheService.getAllCachedConfigurations(dev)

        assertEquals(1, devConfigs.size)
        assertEquals("app.name=dev", devConfigs.single().content)
    }

    @Test
    fun `list page cache expires independently from detail cache`() = runBlocking {
        var now = 1_000L
        val cacheService = CacheService({ now }, InMemoryCacheStore())

        val page = ConfigListResponse(
            totalCount = 1,
            pageNumber = 1,
            pagesAvailable = 1,
            pageItems = listOf(
                ConfigItem(
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
            identity = defaultIdentity,
            namespaceId = "dev",
            requestKey = "page=1",
            response = page,
            ttl = 1L
        )
        cacheService.putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = detail,
            ttl = 60_000L
        )

        now += 5L

        assertNull(cacheService.getListPage(defaultIdentity, "dev", "page=1"))
        assertEquals(
            detail,
            cacheService.getConfigDetail(defaultIdentity, "dev", "app.yaml", "DEFAULT_GROUP")
        )
    }

    @Test
    fun `cache modification count changes when detail cache changes`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        cacheService.awaitLoadCompleted()
        val initial = cacheService.getModificationCount()

        cacheService.putConfigDetail(
            identity = defaultIdentity,
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
    fun `namespace index preheat round-trips and is scoped by identity and namespace`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        val configs = listOf(
            NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true", "yaml"),
            NacosConfiguration("db.properties", "DEFAULT_GROUP", "dev", "db.url=jdbc:test", "properties")
        )

        cacheService.putNamespaceIndex(
            identity = defaultIdentity,
            namespaceId = "dev",
            configurations = configs,
            ttl = 60_000L
        )

        val cached = cacheService.getNamespaceIndex(defaultIdentity, "dev")
        assertEquals(2, cached?.size)

        assertNull(cacheService.getNamespaceIndex(testIdentity("http://other:8848"), "dev"))
        assertNull(cacheService.getNamespaceIndex(defaultIdentity, "prod"))
    }

    @Test
    fun `putNamespaceIndex does not seed individual config details`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())

        cacheService.putNamespaceIndex(
            identity = defaultIdentity,
            namespaceId = null,
            configurations = listOf(
                NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "timeout=30", "properties")
            ),
            ttl = 60_000L
        )

        val detail = cacheService.getConfigDetail(defaultIdentity, null, "app.properties", "DEFAULT_GROUP")
        assertNull(detail)
        val index = cacheService.getNamespaceIndex(defaultIdentity, null)
        assertEquals(1, index?.size)
        assertEquals("", index!![0].content)
    }

    @Test
    fun `reads are not serialized behind a write lock`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        repeat(200) { i ->
            cacheService.putConfigDetail(
                identity = defaultIdentity,
                namespaceId = "ns",
                configuration = NacosConfiguration("app$i.properties", "DEFAULT_GROUP", "ns", "k=$i", "properties"),
                ttl = 60_000L
            )
        }

        val writer = launch {
            repeat(200) { i ->
                cacheService.putConfigDetail(
                    identity = defaultIdentity,
                    namespaceId = "ns",
                    configuration = NacosConfiguration("other$i.properties", "DEFAULT_GROUP", "ns", "v=$i", "properties"),
                    ttl = 60_000L
                )
            }
        }

        withTimeout(20_000L) {
            coroutineScope {
                val readers = (1..50).map {
                    async { cacheService.getAllCachedConfigurations(defaultIdentity) }
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
            identity = defaultIdentity,
            namespaceId = "dev",
            configurations = listOf(
                NacosConfiguration("seed.properties", "DEFAULT_GROUP", "dev", "seeded=true", "properties")
            ),
            ttl = 60_000L
        )

        // Issue #52: summary index must not leave detail payloads behind.
        val reloaded = CacheService(store)
        assertNull(reloaded.getConfigDetail(defaultIdentity, "dev", "seed.properties", "DEFAULT_GROUP"))
        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `clearing the cache leaves no payload in the store`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = CacheService(store)
        first.putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = NacosConfiguration("gone.properties", "DEFAULT_GROUP", "dev", "x=1", "properties"),
            ttl = 60_000L
        )
        first.putListPage(
            identity = defaultIdentity,
            namespaceId = "dev",
            requestKey = "page=1",
            response = ConfigListResponse(0, 1, 1, emptyList()),
            ttl = 60_000L
        )
        // Confirms the payload was persisted and reloadable before clear.
        assertEquals(
            "x=1",
            CacheService(store).getConfigDetail(defaultIdentity, "dev", "gone.properties", "DEFAULT_GROUP")?.content
        )

        first.clearCache()

        // Nothing unreachable is left behind, and a fresh load cannot resurrect
        // an entry the user explicitly discarded.
        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadListPages().isEmpty())
        assertNull(CacheService(store).getConfigDetail(defaultIdentity, "dev", "gone.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `removing a detail reclaims its payload from the store`() = runBlocking {
        val store = InMemoryCacheStore()
        val cacheService = CacheService(store)
        cacheService.putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = NacosConfiguration("removed.properties", "DEFAULT_GROUP", "dev", "x=1", "properties"),
            ttl = 60_000L
        )

        cacheService.removeConfigDetail(defaultIdentity, "dev", "removed.properties", "DEFAULT_GROUP")

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
        migrated.awaitLoadCompleted()

        assertNull(migrated.getConfigDetail(defaultIdentity, "dev", "legacy.properties", "DEFAULT_GROUP"))
        // Discarded rather than left in the store where no sweep would reach it.
        assertTrue(store.loadDetails().isEmpty())
    }

    @Test
    fun `single-key read resolves from the store without waiting for the full background load`() = runBlocking {
        val store = InMemoryCacheStore()
        CacheService(store).putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = NacosConfiguration("quick.properties", "DEFAULT_GROUP", "dev", "v=1", "properties"),
            ttl = 60_000L
        )

        // A fresh instance starts a background load; a single-key read (go-to-declaration
        // path) must return the persisted entry before that load can finish.
        val gated = GatedCacheStore(store)
        val reloaded = CacheService(gated)
        val detail = withTimeout(10_000L) {
            reloaded.getConfigDetail(defaultIdentity, "dev", "quick.properties", "DEFAULT_GROUP")
        }
        assertEquals("v=1", detail?.content)
        gated.releaseFullLoad()
    }

    @Test
    fun `full read reflects the completed background load`() = runBlocking {
        val store = InMemoryCacheStore()
        CacheService(store).putConfigDetail(
            identity = defaultIdentity,
            namespaceId = "dev",
            configuration = NacosConfiguration("full.properties", "DEFAULT_GROUP", "dev", "k=v", "properties"),
            ttl = 60_000L
        )

        val reloaded = CacheService(store)
        val all = reloaded.getAllCachedConfigurations(defaultIdentity)
        assertTrue(all.any { it.dataId == "full.properties" && it.content == "k=v" })
    }

    @Test
    fun `complete namespace replacement is isolated by access identity`() = runBlocking {
        val cacheService = CacheService(InMemoryCacheStore())
        val alice = testIdentity("http://nacos:8848", "alice", AuthMode.BASIC)
        val bob = testIdentity("http://nacos:8848", "bob", AuthMode.BASIC)

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
        assertTrue(cacheService.configurationNavigationSnapshot(alice).isEmpty())
        assertTrue(cacheService.configurationNavigationSnapshot(bob).isEmpty())
    }

    @Test
    fun `list page entry exposes creation timestamp for cache age`() = runBlocking {
        var now = 5_000_000L
        val cache = CacheService({ now }, InMemoryCacheStore())
        val identity = testIdentity("http://age-test", "alice", AuthMode.BASIC)
        val response = ConfigListResponse(
            totalCount = 0,
            pageNumber = 1,
            pagesAvailable = 0,
            pageItems = emptyList()
        )
        cache.putListPage(identity, "public", "key-1", response, ttl = 60_000L)
        val entry = cache.getListPageEntry(identity, "public", "key-1")
        assertEquals(5_000_000L, entry?.createdAtMillis)

        now = 5_000_000L + 30_000L
        val stillFresh = cache.getListPageEntry(identity, "public", "key-1")
        assertEquals(5_000_000L, stillFresh?.createdAtMillis)
        cache.dispose()
    }

    @Test
    fun `namespace index entry exposes creation timestamp for cache age`() = runBlocking {
        var now = 9_000_000L
        val cache = CacheService({ now }, InMemoryCacheStore())
        val identity = testIdentity("http://index-age-test", "alice", AuthMode.BASIC)
        cache.putNamespaceIndex(
            identity,
            "public",
            listOf(NacosConfiguration("app.yaml", "DEFAULT_GROUP", "public", "feature=true", "yaml")),
            ttl = 60_000L
        )

        val fresh = cache.getNamespaceIndexEntry(identity, "public")
        assertEquals(9_000_000L, fresh?.createdAtMillis)
        assertEquals(listOf("app.yaml"), fresh?.data?.map { it.dataId })

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
        val entombed = testIdentity("dev-srv", "alice", AuthMode.BASIC, profileId = "dev-srv")
        val survivor = testIdentity("prod-srv", "bob", AuthMode.BASIC, profileId = "prod-srv")

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

    @Test
    fun `awaitLoadCompleted is explicit and does not rely on statistics side effects`() = runBlocking {
        val cache = CacheService(InMemoryCacheStore())
        cache.awaitLoadCompleted()
        // Second await is a no-op once the deferred is complete.
        cache.awaitLoadCompleted()
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
