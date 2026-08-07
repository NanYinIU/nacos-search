package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

internal class FileCacheStoreTest : CacheStoreContractTest() {

    @TempDir
    lateinit var baseDir: Path

    // No shared application-properties instance: the contract cases get their own
    // empty legacy records rather than the IDE's.
    override fun newStore(): CacheStore = FileCacheStore(baseDir, FakeLegacyCacheProperties())

    private val config = NacosConfiguration("app.yaml", "DEFAULT_GROUP", "dev", "feature=true", "yaml")
    private val detailEntry = CacheService.CacheEntry(
        type = CacheService.CacheEntryType.CONFIG_DETAIL,
        data = config,
        createdAt = 1000L,
        ttlMs = 60000L,
        source = CacheService.CacheSource.REMOTE
    )
    private val listEntry = CacheService.CacheEntry(
        type = CacheService.CacheEntryType.LIST_PAGE,
        data = ConfigListResponse(0, 1, 1, emptyList()),
        createdAt = 1000L,
        ttlMs = 60000L,
        source = CacheService.CacheSource.REMOTE
    )

    @Test
    fun `entries survive a store reopened over the same directory`() = kotlinx.coroutines.runBlocking {
        FileCacheStore(baseDir, FakeLegacyCacheProperties()).putDetail("server|ns|app.yaml|DEFAULT_GROUP", detailEntry)

        val reopened = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        assertEquals("feature=true", reopened.loadDetail("server|ns|app.yaml|DEFAULT_GROUP")?.data?.content)
        assertEquals(setOf("server|ns|app.yaml|DEFAULT_GROUP"), reopened.loadDetails().keys)
    }

    @Test
    fun `overwrite replaces the existing entry`() = kotlinx.coroutines.runBlocking {
        val store = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        val key = "server|ns|app.yaml|DEFAULT_GROUP"
        store.putDetail(key, detailEntry)
        store.putDetail(key, detailEntry.copy(data = config.copy(content = "feature=false")))

        assertEquals("feature=false", store.loadDetail(key)?.data?.content)
    }

    @Test
    fun `a store writes atomically and leaves no temp file behind`() = kotlinx.coroutines.runBlocking {
        val store = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        store.putDetail("server|ns|app.yaml|DEFAULT_GROUP", detailEntry)

        val tmpFiles = baseDir.resolve("details").toFile().listFiles { _, name -> name.endsWith(".tmp") }
        assertTrue(tmpFiles?.isEmpty() ?: true, "temp files should not remain after atomic write")
        assertNotNull(store.loadDetail("server|ns|app.yaml|DEFAULT_GROUP"))
    }

    @Test
    fun `clearing empties all payload directories`() = kotlinx.coroutines.runBlocking {
        val store = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        store.putDetail("d|1", detailEntry)
        store.putListPage("l|1", listEntry)
        store.putNamespaceIndex(
            "n|1",
            CacheService.CacheEntry(
                type = CacheService.CacheEntryType.NAMESPACE_INDEX,
                data = listOf(config),
                createdAt = 1000L,
                ttlMs = 60000L,
                source = CacheService.CacheSource.REMOTE
            )
        )

        store.clear()

        assertEquals(0, baseDir.resolve("details").toFile().listFiles()?.size ?: 0)
        assertEquals(0, baseDir.resolve("listpages").toFile().listFiles()?.size ?: 0)
        assertEquals(0, baseDir.resolve("indexes").toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun `namespace indexes survive a store reopened over the same directory`() =
        kotlinx.coroutines.runBlocking {
            val key = "v2|p|1|http://nacos:8848|V1|BASIC|alice|dev"
            val indexEntry = CacheService.CacheEntry(
                type = CacheService.CacheEntryType.NAMESPACE_INDEX,
                data = listOf(config.copy(content = "")),
                createdAt = 1000L,
                ttlMs = 60000L,
                source = CacheService.CacheSource.REMOTE
            )
            FileCacheStore(baseDir, FakeLegacyCacheProperties()).putNamespaceIndex(key, indexEntry)

            val reopened = FileCacheStore(baseDir, FakeLegacyCacheProperties())
            assertEquals(listOf("app.yaml"), reopened.loadNamespaceIndexes()[key]?.data?.map { it.dataId })
        }

    @Test
    fun `a payload that cannot be deserialized is a miss and is reclaimed`() = kotlinx.coroutines.runBlocking {
        val store = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        val key = "bad|key"
        store.putDetail(key, detailEntry)
        val file = baseDir.resolve("details").resolve(FileCacheStore.fileName(key))
        file.toFile().writeText("{not valid json")

        assertNull(store.loadDetail(key))
        assertTrue(store.loadDetails().isEmpty())
        assertTrue(!file.toFile().exists(), "an unreadable payload should be reclaimed, not kept forever")
    }

    @Test
    fun `a payload whose key entry is missing is reclaimed rather than orphaned`() = kotlinx.coroutines.runBlocking {
        val store = FileCacheStore(baseDir, FakeLegacyCacheProperties())
        // A payload file that names no key: the crash-between-two-writes shape that
        // used to be invisible to every reclamation sweep.
        baseDir.resolve("details").resolve("deadbeef.json").toFile().writeText("""{"orphan":true}""")

        assertTrue(store.loadDetails().isEmpty())
        assertEquals(0, baseDir.resolve("details").toFile().listFiles()?.size ?: 0)
    }

    @Test
    fun `payloads whose key list lived in application properties are still served`() = kotlinx.coroutines.runBlocking {
        val key = "v2|p|1|http://nacos:8848|V1|BASIC|alice|dev|app.yaml|DEFAULT_GROUP"
        writeLegacyPayload("details", key, detailEntry)
        val pageKey = "v2|p|1|http://nacos:8848|V1|BASIC|alice|dev|page=1"
        writeLegacyPayload("listpages", pageKey, listEntry)
        val properties = FakeLegacyCacheProperties(
            "nacos.cache.detail.keys" to Gson().toJson(listOf(key)),
            "nacos.cache.list.keys" to Gson().toJson(listOf(pageKey))
        )

        val store = FileCacheStore(baseDir, properties)

        assertEquals("feature=true", store.loadDetails()[key]?.data?.content)
        assertEquals(setOf(pageKey), store.loadListPages().keys)
        // Adopted once: the payload now names its own key, so a store that never
        // sees the legacy properties still enumerates it.
        assertEquals(setOf(key), FileCacheStore(baseDir, FakeLegacyCacheProperties()).loadDetails().keys)
    }

    @Test
    fun `the legacy key list and payload blobs are dropped from application properties`() =
        kotlinx.coroutines.runBlocking {
            val key = "v2|p|1|http://nacos:8848|V1|BASIC|alice|dev|app.yaml|DEFAULT_GROUP"
            val properties = FakeLegacyCacheProperties(
                "nacos.cache.detail.keys" to Gson().toJson(listOf(key)),
                "nacos.cache.detail.$key" to """{"stale":"pre-file-storage blob"}"""
            )

            FileCacheStore(baseDir, properties).loadDetails()

            assertNull(properties.value("nacos.cache.detail.keys"))
            assertNull(properties.value("nacos.cache.detail.$key"))
        }

    private fun writeLegacyPayload(dir: String, key: String, entry: CacheService.CacheEntry<*>) {
        // The pre-store schema: a bare entry, with the key held elsewhere.
        baseDir.resolve(dir).toFile().mkdirs()
        baseDir.resolve(dir).resolve(FileCacheStore.fileName(key)).toFile().writeText(Gson().toJson(entry))
    }

    private class FakeLegacyCacheProperties(vararg entries: Pair<String, String>) : LegacyCacheProperties {
        private val values = entries.toMap().toMutableMap()

        override fun value(name: String): String? = values[name]

        override fun unset(name: String) {
            values.remove(name)
        }
    }
}
