package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.intellij.ide.util.PropertiesComponent
import com.google.gson.Gson
import com.nanyin.nacos.search.models.NacosConfiguration
import com.intellij.openapi.util.Disposer
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

class CacheServiceTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `CacheService is Disposable and dispose cancels its scope`() {
        val cacheService = CacheService()
        assertTrue(cacheService is com.intellij.openapi.Disposable)
        Disposer.dispose(cacheService)
        // Snapshot still readable after dispose (volatile read, no coroutine needed)
        assertTrue(cacheService.configurationSnapshot(null).isEmpty())
    }

    @Test
    fun `configuration snapshot is immediately callable without a coroutine`() {
        val cacheService = CacheService()

        assertTrue(cacheService.configurationSnapshot(null).isEmpty())
    }

    @Test
    fun `configuration snapshot excludes expired details`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()
        cacheService.putConfigDetail(
            "http://nacos:8848", null,
            NacosConfiguration("expired.properties", "DEFAULT_GROUP", null, "k=v", "properties"),
            ttl = -1L
        )

        assertTrue(cacheService.configurationSnapshot(null).isEmpty())
    }

    @Test
    fun `configuration snapshot scopes by normalized server url`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()
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
        val cacheService = CacheService()
        cacheService.clearAll()
        val configurations = (1..100).map {
            NacosConfiguration("app$it.properties", "DEFAULT_GROUP", "dev", "k$it=v", "properties")
        }
        val observedSizes = ConcurrentHashMap.newKeySet<Int>()

        coroutineScope {
            val writer = launch {
                cacheService.putNamespaceIndex("http://nacos:8848", "dev", configurations)
            }
           while (!writer.isCompleted) {
               observedSizes += cacheService.configurationSnapshot("http://nacos:8848").size
               yield()
           }
            writer.join()
        }
        observedSizes += cacheService.configurationSnapshot("http://nacos:8848").size

        assertTrue(observedSizes.all { it == 0 || it == configurations.size })
        assertEquals(configurations.size, cacheService.configurationSnapshot("http://nacos:8848").size)
    }

    @Test
    fun `configuration detail cache uses server namespace dataId and group`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

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
        val cacheService = CacheService()
        cacheService.clearAll()

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
        val cacheService = CacheService()
        cacheService.clearAll()

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

       Thread.sleep(5L)

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
       val cacheService = CacheService()
       cacheService.clearAll()
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
        val cacheService = CacheService()
        cacheService.clearAll()

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
    fun `putNamespaceIndex also seeds individual config details for the resolver`() = runBlocking {
        val cacheService = CacheService()
        cacheService.clearAll()

        cacheService.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = null,
            configurations = listOf(
                NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "timeout=30", "properties")
            ),
            ttl = 60_000L
        )

        // Preheating the namespace index makes individual configs resolvable
        // by detail key, which is what NacosKeyResolver scans.
        val detail = cacheService.getConfigDetail("http://nacos:8848", null, "app.properties", "DEFAULT_GROUP")
        assertEquals("timeout=30", detail?.content)
    }

    @Test
    fun `reads are not serialized behind a write lock`() = runBlocking {
        // getAllCachedConfigurations sits on the search hot path. It must not hold a
        // global write lock that serializes it against concurrent writers.
        val cacheService = CacheService()
        cacheService.clearAll()
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
    fun `putNamespaceIndex persists seeded details for reload`() = runBlocking {
        val first = CacheService()
        first.clearAll()
        first.putNamespaceIndex(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configurations = listOf(
                NacosConfiguration("seed.properties", "DEFAULT_GROUP", "dev", "seeded=true", "properties")
            ),
            ttl = 60_000L
        )

        // A fresh instance reloads from persistence; the seeded detail must survive
        // because putNamespaceIndex now persists it (previously only the keys list was
        // updated, leaving orphan keys pointing at never-written blobs).
        val reloaded = CacheService()
        val detail = reloaded.getConfigDetail("http://nacos:8848", "dev", "seed.properties", "DEFAULT_GROUP")
        assertEquals("seeded=true", detail?.content)
        reloaded.clearAll()
    }

    @Test
    fun `clearCache removes persisted payloads so reload finds nothing`() = runBlocking {
        val first = CacheService()
        first.clearAll()
        first.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("gone.properties", "DEFAULT_GROUP", "dev", "x=1", "properties"),
            ttl = 60_000L
        )
        // Confirms the payload was persisted and reloadable before clear.
        assertEquals("x=1", CacheService().getConfigDetail("http://nacos:8848", "dev", "gone.properties", "DEFAULT_GROUP")?.content)

        first.clearCache()

        // After clear, a fresh load must not resurrect the entry: the persisted file
        // (not just the in-memory map) must have been removed.
        val afterClear = CacheService()
        assertNull(afterClear.getConfigDetail("http://nacos:8848", "dev", "gone.properties", "DEFAULT_GROUP"))
        afterClear.clearAll()
    }

    @Test
    fun `legacy PropertiesComponent detail blob migrates to file and is cleared`() = runBlocking {
        // Clean slate for both file storage and PropertiesComponent.
        val cleaner = CacheService()
        cleaner.clearAll()

        val key = "http://nacos:8848|dev|legacy.properties|DEFAULT_GROUP"
        val entry = CacheService.CacheEntry(
            type = CacheService.CacheEntryType.CONFIG_DETAIL,
            data = NacosConfiguration("legacy.properties", "DEFAULT_GROUP", "dev", "legacy=true", "properties"),
            createdAt = System.currentTimeMillis(),
            ttlMs = 60_000L,
            source = CacheService.CacheSource.REMOTE
        )
        val gson = Gson()
        val props = PropertiesComponent.getInstance()
        // Simulate a pre-file-storage build: entry JSON lives in PropertiesComponent and
        // the keys list references it, but no backing file exists yet.
        props.setValue("nacos.cache.detail.$key", gson.toJson(entry))
        props.setValue("nacos.cache.detail.keys", gson.toJson(listOf(key)))

        // Trigger load + migration by constructing a fresh instance.
        val migrated = CacheService()
        val detail = migrated.getConfigDetail("http://nacos:8848", "dev", "legacy.properties", "DEFAULT_GROUP")
        assertEquals("legacy=true", detail?.content)

        // The legacy blob must have been removed so the state XML stops growing.
        assertNull(props.getValue("nacos.cache.detail.$key"))

        // A second fresh load still resolves it (now from the migrated file).
        assertEquals("legacy=true", CacheService().getConfigDetail("http://nacos:8848", "dev", "legacy.properties", "DEFAULT_GROUP")?.content)

        migrated.clearAll()
    }

    @Test
    fun `single-key read resolves from file without waiting for the full background load`() = runBlocking {
        val seeder = CacheService()
        seeder.clearAll()
        seeder.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("quick.properties", "DEFAULT_GROUP", "dev", "v=1", "properties"),
            ttl = 60_000L
        )

        // A fresh instance starts a background load; a single-key read (go-to-declaration
        // path) must return the persisted entry whether or not the full load has finished.
        val reloaded = CacheService()
        val detail = reloaded.getConfigDetail("http://nacos:8848", "dev", "quick.properties", "DEFAULT_GROUP")
        assertEquals("v=1", detail?.content)
        reloaded.clearAll()
    }

    @Test
    fun `full read reflects the completed background load`() = runBlocking {
        val seeder = CacheService()
        seeder.clearAll()
        seeder.putConfigDetail(
            serverUrl = "http://nacos:8848",
            namespaceId = "dev",
            configuration = NacosConfiguration("full.properties", "DEFAULT_GROUP", "dev", "k=v", "properties"),
            ttl = 60_000L
        )

        val reloaded = CacheService()
        val all = reloaded.getAllCachedConfigurations("http://nacos:8848")
        assertTrue(all.any { it.dataId == "full.properties" && it.content == "k=v" })
        reloaded.clearAll()
    }
}
