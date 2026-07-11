package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * File-backed store for cache entry payloads (config content). Heavy entry blobs live
 * here instead of in PropertiesComponent so the persisted state XML stays small and
 * IDE startup is not dominated by parsing a single multi-hundred-MB document.
 *
 * The lightweight keys lists remain in PropertiesComponent (managed by [CacheService]);
 * this class only owns the file I/O for individual entries.
 *
 * @param baseDir root directory; defaults to `…/nacos-search-cache` under the IDE
 *                config path. The base dir is injectable so tests can point it at a
 *                temp directory without an application context.
 */
internal class CacheFileStorage(
    baseDir: Path = defaultBaseDir()
) {
    private val logger = thisLogger()
    private val gson = Gson()
    private val detailsDir: Path = baseDir.resolve("details")
    private val listPagesDir: Path = baseDir.resolve("listpages")

   init {
       try {
           Files.createDirectories(detailsDir)
           Files.createDirectories(listPagesDir)
           // Clean up leftover .tmp files from interrupted writes
           cleanupTempFiles(detailsDir)
           cleanupTempFiles(listPagesDir)
       } catch (e: Exception) {
           logger.warn("Failed to create cache storage directories under $baseDir", e)
       }
   }

    suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>? =
        load(detailsDir, key, object : TypeToken<CacheService.CacheEntry<NacosConfiguration>>() {}.type)

    suspend fun storeDetail(key: String, entry: CacheService.CacheEntry<NacosConfiguration>) =
        store(detailsDir, key, entry)

    suspend fun removeDetail(key: String) = remove(detailsDir, key)

    suspend fun loadListPage(key: String): CacheService.CacheEntry<NacosApiService.ConfigListResponse>? =
        load(listPagesDir, key, object : TypeToken<CacheService.CacheEntry<NacosApiService.ConfigListResponse>>() {}.type)

    suspend fun storeListPage(key: String, entry: CacheService.CacheEntry<NacosApiService.ConfigListResponse>) =
        store(listPagesDir, key, entry)

    suspend fun removeListPage(key: String) = remove(listPagesDir, key)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        deleteDirContents(detailsDir)
        deleteDirContents(listPagesDir)
    }

    /** File name for a key (exposed for tests/diagnostics). */
    internal fun fileName(key: String): String = sha256Hex(key) + ".json"

    private suspend fun <T> load(dir: Path, key: String, type: java.lang.reflect.Type): CacheService.CacheEntry<T>? =
        withContext(Dispatchers.IO) {
            val file = dir.resolve(fileName(key))
            if (!Files.exists(file)) return@withContext null
            try {
                val json = Files.readString(file)
                gson.fromJson<CacheService.CacheEntry<T>>(json, type)
            } catch (e: Exception) {
                logger.warn("Corrupt cache file, removing: $file", e)
                try { Files.deleteIfExists(file) } catch (ignore: Exception) {}
                null
            }
        }

   private suspend fun <T> store(dir: Path, key: String, entry: CacheService.CacheEntry<T>) =
       withContext(Dispatchers.IO) {
           try {
               val target = dir.resolve(fileName(key))
               val tmp = dir.resolve(fileName(key) + ".tmp")
               // Atomic write: serialize to a sibling .tmp file, then atomically move
               // it over the target. This prevents partial writes from corrupting
               // the cache if the IDE crashes or the process is interrupted.
               Files.writeString(tmp, gson.toJson(entry), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
               try {
                   Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
               } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                   // Fallback for filesystems that don't support atomic move (rare).
                   Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
               }
           } catch (e: Exception) {
               logger.warn("Failed to persist cache entry: $key", e)
           }
       }

    private suspend fun remove(dir: Path, key: String) = withContext(Dispatchers.IO) {
        try {
            Files.deleteIfExists(dir.resolve(fileName(key)))
        } catch (e: Exception) {
            logger.warn("Failed to remove cache entry: $key", e)
        }
    }

   private fun deleteDirContents(dir: Path) {
       try {
           Files.newDirectoryStream(dir).use { stream ->
               stream.forEach { file -> runCatching { Files.deleteIfExists(file) } }
           }
       } catch (e: Exception) {
           logger.warn("Failed to clear cache directory: $dir", e)
       }
   }

   private fun cleanupTempFiles(dir: Path) {
       try {
           Files.newDirectoryStream(dir, "*.tmp").use { stream ->
               stream.forEach { file -> runCatching { Files.deleteIfExists(file) } }
           }
       } catch (e: Exception) {
           logger.warn("Failed to clean temp files in $dir", e)
       }
   }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private fun defaultBaseDir(): Path {
            val configPath = com.intellij.openapi.application.PathManager.getConfigDir().toString()
            return Path.of(configPath, "nacos-search-cache")
        }
    }
}
