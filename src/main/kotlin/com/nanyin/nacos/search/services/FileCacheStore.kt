package com.nanyin.nacos.search.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * File-backed [CacheStore] for production. Heavy entry payloads live in
 * per-entry files rather than in the IDE state XML, so startup is not dominated
 * by parsing a single multi-hundred-MB document.
 *
 * Every payload file names the key it belongs to, so the key list *is* the set
 * of payload files rather than a second record kept in step by convention. A
 * crash between two writes can therefore no longer orphan a payload that every
 * reclamation sweep is blind to: a file that names no readable key is reclaimed
 * on the next scan.
 *
 * @param baseDir root directory; defaults to `…/nacos-search-cache` under the
 *                IDE config path. Injectable so tests can point it at a temp
 *                directory without an application context.
 * @param legacy  the pre-store application properties this adapter migrates
 *                away from, injectable for the same reason.
 */
internal class FileCacheStore(
    baseDir: Path = defaultBaseDir(),
    private val legacy: LegacyCacheProperties = LegacyCacheProperties.ideProperties()
) : CacheStore {
    private val logger = thisLogger()
    private val gson = Gson()

    private val details = StoredKind<NacosConfiguration>(
        dir = baseDir.resolve("details"),
        recordType = object : TypeToken<StoredRecord<NacosConfiguration>>() {}.type,
        entryType = object : TypeToken<CacheService.CacheEntry<NacosConfiguration>>() {}.type,
        legacyKeysProperty = "nacos.cache.detail.keys",
        legacyBlobPrefix = "nacos.cache.detail."
    )
    private val listPages = StoredKind<ConfigListResponse>(
        dir = baseDir.resolve("listpages"),
        recordType = object : TypeToken<StoredRecord<ConfigListResponse>>() {}.type,
        entryType = object : TypeToken<CacheService.CacheEntry<ConfigListResponse>>() {}.type,
        legacyKeysProperty = "nacos.cache.list.keys",
        legacyBlobPrefix = "nacos.cache.list."
    )
    private val kinds = listOf(details, listPages)

    private val migrationMutex = Mutex()

    @Volatile
    private var adopted = false

    init {
        kinds.forEach { kind ->
            try {
                Files.createDirectories(kind.dir)
                // Clean up leftover .tmp files from interrupted writes
                cleanupTempFiles(kind.dir)
            } catch (e: Exception) {
                logger.warn("Failed to create cache storage directory ${kind.dir}", e)
            }
        }
    }

    override suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>> = loadAll(details)

    override suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>? = loadOne(details, key)

    override suspend fun putDetail(key: String, entry: CacheService.CacheEntry<NacosConfiguration>) =
        store(details, key, entry)

    override suspend fun removeDetail(key: String) = remove(details, key)

    override suspend fun loadListPages(): Map<String, CacheService.CacheEntry<ConfigListResponse>> =
        loadAll(listPages)

    override suspend fun putListPage(key: String, entry: CacheService.CacheEntry<ConfigListResponse>) =
        store(listPages, key, entry)

    override suspend fun removeListPage(key: String) = remove(listPages, key)

    override suspend fun clear() {
        // Held against the adoption pass: an adoption that has already passed the
        // `adopted` check would otherwise rewrite payloads after the wipe and
        // resurrect entries the user explicitly discarded.
        migrationMutex.withLock {
            withContext(Dispatchers.IO) {
                kinds.forEach { kind ->
                    forgetLegacyRecords(kind)
                    deleteDirContents(kind.dir)
                }
            }
            adopted = true
        }
    }

    private suspend fun <T> loadAll(kind: StoredKind<T>): Map<String, CacheService.CacheEntry<T>> {
        adoptLegacyRecords()
        return scan(kind)
    }

    /**
     * Reads every payload file of [kind]. A file that cannot be read, or that
     * names no key, is reclaimed: it is unreachable by definition, since the
     * only way to address an entry is by the key the file itself carries.
     */
    private suspend fun <T> scan(kind: StoredKind<T>): Map<String, CacheService.CacheEntry<T>> =
        withContext(Dispatchers.IO) {
            val loaded = mutableMapOf<String, CacheService.CacheEntry<T>>()
            try {
                Files.newDirectoryStream(kind.dir, "*$JSON_SUFFIX").use { stream ->
                    stream.forEach { file ->
                        val record = readRecord(kind, file)
                        if (record == null) reclaim(file) else loaded[record.first] = record.second
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to scan cache directory: ${kind.dir}", e)
            }
            loaded
        }

    private suspend fun <T> loadOne(kind: StoredKind<T>, key: String): CacheService.CacheEntry<T>? =
        withContext(Dispatchers.IO) {
            val file = kind.dir.resolve(fileName(key))
            if (!Files.exists(file)) return@withContext null
            val json = readFile(file) ?: run {
                reclaim(file)
                return@withContext null
            }
            // The key is known here, so a payload written before it carried its own
            // key is still readable — such files are rewritten by [adoptLegacyRecords].
            val entry = parse<StoredRecord<T>>(json, kind.recordType)?.entry
                ?: parse<CacheService.CacheEntry<T>>(json, kind.entryType)
            if (entry == null || !entry.isUsable()) {
                reclaim(file)
                return@withContext null
            }
            entry
        }

    private suspend fun <T> store(kind: StoredKind<T>, key: String, entry: CacheService.CacheEntry<T>) =
        withContext(Dispatchers.IO) {
            try {
                writeRecord(kind, key, entry)
            } catch (e: Exception) {
                logger.warn("Failed to persist cache entry: $key", e)
            }
        }

    private fun <T> writeRecord(kind: StoredKind<T>, key: String, entry: CacheService.CacheEntry<T>) {
        val target = kind.dir.resolve(fileName(key))
        val tmp = kind.dir.resolve(fileName(key) + TMP_SUFFIX)
        // Atomic write: serialize to a sibling .tmp file, then atomically move
        // it over the target. This prevents partial writes from corrupting
        // the cache if the IDE crashes or the process is interrupted.
        Files.writeString(
            tmp,
            gson.toJson(StoredRecord(key, entry), kind.recordType),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Fallback for filesystems that don't support atomic move (rare).
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun remove(kind: StoredKind<*>, key: String) = withContext(Dispatchers.IO) {
        try {
            Files.deleteIfExists(kind.dir.resolve(fileName(key)))
        } catch (e: Exception) {
            logger.warn("Failed to remove cache entry: $key", e)
        }
        Unit
    }

    private fun <T> readRecord(kind: StoredKind<T>, file: Path): Pair<String, CacheService.CacheEntry<T>>? {
        val json = readFile(file) ?: return null
        val record = parse<StoredRecord<T>>(json, kind.recordType) ?: return null
        val key = record.key ?: return null
        val entry = record.entry ?: return null
        return if (entry.isUsable()) key to entry else null
    }

    private fun readFile(file: Path): String? =
        try {
            Files.readString(file)
        } catch (e: Exception) {
            logger.warn("Unreadable cache file: $file", e)
            null
        }

    private fun <T> parse(json: String, type: Type): T? =
        try {
            gson.fromJson<T>(json, type)
        } catch (e: Exception) {
            null
        }

    /**
     * Gson populates fields reflectively, so a file holding unrelated JSON
     * deserializes into an entry with null members despite the declared types.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun <T> CacheService.CacheEntry<T>.isUsable(): Boolean =
        type != null && data != null && source != null

    private fun reclaim(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            logger.warn("Failed to reclaim unreachable cache file: $file", e)
        }
    }

    /**
     * Takes ownership of records persisted before the store owned the key list:
     * payload files whose key lived in application properties are rewritten so
     * they name their own key, and the properties are dropped. Runs once, over
     * a bounded number of records, and never blocks startup (ADR-0018).
     */
    private suspend fun adoptLegacyRecords() {
        if (adopted) return
        migrationMutex.withLock {
            if (adopted) return
            withContext(Dispatchers.IO) {
                kinds.forEach { kind ->
                    recoverPayloads(kind)
                    forgetLegacyRecords(kind)
                }
            }
            adopted = true
        }
    }

    private fun <T> recoverPayloads(kind: StoredKind<T>) {
        val keys = legacyKeys(kind)
        if (keys.size > MAX_ADOPTED_RECORDS) {
            // Bounded work: the surplus keeps the pre-store schema, so the scan
            // reclaims it rather than serving it. Never silently — say what went.
            logger.warn(
                "Adopting only the first $MAX_ADOPTED_RECORDS of ${keys.size} legacy cache records in " +
                    "${kind.dir}; the remainder is reclaimed instead of migrated"
            )
        }
        keys.take(MAX_ADOPTED_RECORDS).forEach { key ->
            val file = kind.dir.resolve(fileName(key))
            if (!Files.exists(file)) return@forEach
            val json = readFile(file) ?: return@forEach
            if (parse<StoredRecord<T>>(json, kind.recordType)?.key != null) return@forEach
            val entry = parse<CacheService.CacheEntry<T>>(json, kind.entryType) ?: return@forEach
            if (!entry.isUsable()) return@forEach
            try {
                writeRecord(kind, key, entry)
            } catch (e: Exception) {
                logger.warn("Failed to adopt legacy cache entry: $key", e)
            }
        }
    }

    /** Drops the pre-store key list and the pre-file-storage payload blobs. */
    private fun forgetLegacyRecords(kind: StoredKind<*>) {
        legacyKeys(kind).forEach { legacy.unset(kind.legacyBlobPrefix + it) }
        legacy.unset(kind.legacyKeysProperty)
    }

    private fun legacyKeys(kind: StoredKind<*>): List<String> {
        val json = legacy.value(kind.legacyKeysProperty) ?: return emptyList()
        return parse<List<String>>(json, KEY_LIST_TYPE) ?: emptyList()
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
            Files.newDirectoryStream(dir, "*$TMP_SUFFIX").use { stream ->
                stream.forEach { file -> runCatching { Files.deleteIfExists(file) } }
            }
        } catch (e: Exception) {
            logger.warn("Failed to clean temp files in $dir", e)
        }
    }

    /** A payload together with the key that names it — one file, one write. */
    private data class StoredRecord<T>(
        val key: String?,
        val entry: CacheService.CacheEntry<T>?
    )

    /** Everything that differs between the two kinds of record this store persists. */
    private class StoredKind<T>(
        val dir: Path,
        val recordType: Type,
        val entryType: Type,
        val legacyKeysProperty: String,
        val legacyBlobPrefix: String
    )

    companion object {
        private const val JSON_SUFFIX = ".json"
        private const val TMP_SUFFIX = ".tmp"

        /** Matches the cache's own entry cap, so adoption cannot outgrow what the cache would keep. */
        private const val MAX_ADOPTED_RECORDS = 1000

        private val KEY_LIST_TYPE: Type = object : TypeToken<List<String>>() {}.type

        /** File name for a key (exposed for tests/diagnostics). */
        internal fun fileName(key: String): String = sha256Hex(key) + JSON_SUFFIX

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun defaultBaseDir(): Path {
            val configPath = com.intellij.openapi.application.PathManager.getConfigDir().toString()
            return Path.of(configPath, "nacos-search-cache")
        }
    }
}

/**
 * The application-properties records the file store migrates away from: the
 * pre-store key lists and the pre-file-storage payload blobs. Injectable so the
 * store can be exercised without an IDE application.
 */
internal interface LegacyCacheProperties {
    fun value(name: String): String?
    fun unset(name: String)

    companion object {
        fun ideProperties(): LegacyCacheProperties = IdeLegacyCacheProperties
    }
}

private object IdeLegacyCacheProperties : LegacyCacheProperties {
    private val properties: PropertiesComponent?
        get() = runCatching { PropertiesComponent.getInstance() }.getOrNull()

    override fun value(name: String): String? = properties?.getValue(name)

    override fun unset(name: String) {
        properties?.unsetValue(name)
    }
}
