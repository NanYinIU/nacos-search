package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CacheFileStorageTest {

    @TempDir
    lateinit var baseDir: Path

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
        data = NacosApiService.ConfigListResponse(0, 1, 1, emptyList()),
        createdAt = 1000L,
        ttlMs = 60000L,
        source = CacheService.CacheSource.REMOTE
    )

    @Test
    fun `storeDetail then loadDetail round-trips entry`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        storage.storeDetail("server|ns|app.yaml|DEFAULT_GROUP", detailEntry)

        val loaded = storage.loadDetail("server|ns|app.yaml|DEFAULT_GROUP")
        assertNotNull(loaded)
        assertEquals("app.yaml", loaded!!.data.dataId)
        assertEquals("feature=true", loaded.data.content)
        assertEquals(CacheService.CacheSource.REMOTE, loaded.source)
    }

    @Test
    fun `loadDetail returns null when file missing`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        assertNull(storage.loadDetail("missing|key"))
    }

    @Test
    fun `loadDetail returns null and deletes a corrupt file`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        val key = "bad|key"
        storage.storeDetail(key, detailEntry)
        // Corrupt the written file in place.
        val file = baseDir.resolve("details").resolve(storage.fileName(key))
        assertTrue(file.toFile().exists())
        file.toFile().writeText("{not valid json")

        assertNull(storage.loadDetail(key))
        assertFalse(file.toFile().exists(), "corrupt file should be removed")
    }

    @Test
    fun `storeDetail writes atomically and leaves no temp file`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        storage.storeDetail("server|ns|app.yaml|DEFAULT_GROUP", detailEntry)

        // After a successful store, no .tmp files should remain
        val tmpFiles = baseDir.resolve("details").toFile().listFiles { _, name -> name.endsWith(".tmp") }
        assertTrue(tmpFiles?.isEmpty() ?: true, "temp files should not remain after atomic write")

        // The target file should exist and be readable
        val loaded = storage.loadDetail("server|ns|app.yaml|DEFAULT_GROUP")
        assertNotNull(loaded)
    }

    @Test
    fun `overwrite via atomic move replaces existing entry`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        val key = "server|ns|app.yaml|DEFAULT_GROUP"
        storage.storeDetail(key, detailEntry)
        val updated = detailEntry.copy(data = config.copy(content = "feature=false"))
        storage.storeDetail(key, updated)

        val loaded = storage.loadDetail(key)
        assertEquals("feature=false", loaded!!.data.content)
    }

    @Test
    fun `removeDetail deletes the file`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        val key = "k|1"
        storage.storeDetail(key, detailEntry)
        storage.removeDetail(key)
        assertNull(storage.loadDetail(key))
    }

    @Test
    fun `list page entries round-trip`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        storage.storeListPage("server|ns|page=1", listEntry)
        val loaded = storage.loadListPage("server|ns|page=1")
        assertNotNull(loaded)
        assertEquals(CacheService.CacheEntryType.LIST_PAGE, loaded!!.type)
    }

    @Test
    fun `clearAll removes all files in both dirs`() = kotlinx.coroutines.runBlocking {
        val storage = CacheFileStorage(baseDir)
        storage.storeDetail("d|1", detailEntry)
        storage.storeListPage("l|1", listEntry)

        storage.clearAll()

        assertEquals(0, baseDir.resolve("details").toFile().listFiles()?.size ?: 0)
        assertEquals(0, baseDir.resolve("listpages").toFile().listFiles()?.size ?: 0)
    }
}
