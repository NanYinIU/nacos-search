package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The cache hands out a versioned snapshot instead of lending callers the
 * pieces they used to assemble their own invalidation rules — a modification
 * counter, a clock accessor, and a coroutine scope (issue #64).
 */
class CacheSnapshotTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)

    private fun config(dataId: String, content: String, tenantId: String? = null) =
        NacosConfiguration(dataId, "DEFAULT_GROUP", tenantId, content, "properties")

    /**
     * The background persistent load publishes once it finishes, which advances
     * the version. Await it so a case's assertions are about the mutations it
     * makes rather than about when startup happened to land.
     */
    private suspend fun loadedCache(
        clock: () -> Long = System::currentTimeMillis,
        tombstones: ProfileTombstoneRegistry = ProfileTombstoneRegistry()
    ): CacheService = CacheService(clock, tombstones, InMemoryCacheStore()).also { it.awaitLoadCompleted() }

    private fun emptyListPage() = ConfigListResponse(
        totalCount = 0,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = emptyList()
    )

    // ── Version ──

    @Test
    fun `version advances when a mutation is accepted`() = runBlocking {
        val cacheService = loadedCache()
        val before = cacheService.snapshot(identity).version

        assertTrue(cacheService.writeDetail(identity, null, config("app.properties", "k=v"), observation = 5))

        assertTrue(cacheService.snapshot(identity).version > before)
    }

    @Test
    fun `version holds when a mutation is gated away`() = runBlocking {
        val cacheService = loadedCache()
        cacheService.writeDetail(identity, null, config("app.properties", "k=new"), observation = 9)
        val afterAccepted = cacheService.snapshot(identity).version

        // Started earlier, arrives later: the gate drops it (ADR-0020).
        assertFalse(cacheService.writeDetail(identity, null, config("app.properties", "k=old"), observation = 4))

        assertEquals(afterAccepted, cacheService.snapshot(identity).version)
    }

    @Test
    fun `version holds when a tombstone rejects a mutation`() = runBlocking {
        val tombstones = ProfileTombstoneRegistry()
        val cacheService = loadedCache(tombstones = tombstones)
        val before = cacheService.snapshot(identity).version
        tombstones.entomb(identity.profileId, identity.accessRevision)

        assertFalse(cacheService.writeDetail(identity, null, config("app.properties", "k=v"), observation = 7))

        assertEquals(before, cacheService.snapshot(identity).version)
    }

    @Test
    fun `every kind of accepted mutation advances the version`() = runBlocking {
        val cacheService = loadedCache()
        val versions = mutableListOf(cacheService.snapshot(identity).version)

        cacheService.writeDetail(identity, "dev", config("app.properties", "k=v", "dev"))
        versions += cacheService.snapshot(identity).version
        cacheService.writeListPage(identity, "dev", "page-1", emptyListPage())
        versions += cacheService.snapshot(identity).version
        cacheService.replaceNamespaceIndex(identity, "dev", listOf(config("app.properties", "", "dev")))
        versions += cacheService.snapshot(identity).version
        cacheService.markNamespaceIndexNonAuthoritative(identity, "dev")
        versions += cacheService.snapshot(identity).version
        cacheService.deleteDetailNotFound(identity, "dev", "app.properties", "DEFAULT_GROUP")
        versions += cacheService.snapshot(identity).version
        cacheService.invalidateNamespace(identity, "dev")
        versions += cacheService.snapshot(identity).version
        cacheService.clearAll()
        versions += cacheService.snapshot(identity).version

        assertEquals(
            "each accepted mutation must advance the version exactly once",
            versions.sorted().distinct(),
            versions
        )
        assertEquals(8, versions.size)
    }

    @Test
    fun `a read that reconstitutes an entry advances the version`() = runBlocking {
        val store = LazyOnlyDetailStore()
        CacheService(store).writeDetail(identity, "dev", config("app.properties", "k=v", "dev"))

        // The full load sees nothing in this store, so only a single-key read
        // can reach the entry — and the index must be told, or go-to-declaration
        // would never see what the read just published.
        val cacheService = CacheService(store).also { it.awaitLoadCompleted() }
        val before = cacheService.snapshot(identity).version

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP"))

        assertTrue(cacheService.snapshot(identity).version > before)
    }

    @Test
    fun `two reads racing to reconstitute one entry advance the version once`() = runBlocking {
        val store = LazyOnlyDetailStore()
        CacheService(store).writeDetail(identity, "dev", config("app.properties", "k=v", "dev"))
        val cacheService = CacheService(store).also { it.awaitLoadCompleted() }

        store.parkNextRead()
        coroutineScope {
            val parked = async {
                cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")
            }
            store.awaitParked()

            // A second read gets there first and publishes the entry.
            assertNotNull(cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP"))
            val afterPublished = cacheService.snapshot(identity).version

            // The parked read finds the entry already in memory. It changed
            // nothing, so it must not move the version and rebuild the derived
            // index for no reason.
            store.releaseParkedRead()
            assertNotNull(parked.await())
            assertEquals(afterPublished, cacheService.snapshot(identity).version)
        }
    }

    @Test
    fun `taking a snapshot does not advance the version`() = runBlocking {
        val cacheService = loadedCache()
        cacheService.writeDetail(identity, null, config("app.properties", "k=v"))

        val first = cacheService.snapshot(identity).version
        cacheService.snapshot(identity)

        assertEquals(first, cacheService.snapshot(identity).version)
    }

    // ── As-of instant ──

    @Test
    fun `one snapshot judges every configuration at one instant`() = runBlocking {
        var now = 1_000_000L
        val cacheService = loadedCache({ now })
        // Both entries expire between the two clock reads a per-hit clock would take.
        cacheService.writeDetail(identity, null, config("first.properties", "a=1"), ttl = 100L)
        cacheService.writeDetail(identity, null, config("second.properties", "b=2"), ttl = 100L)

        val snapshot = cacheService.snapshot(identity)
        now += 101L

        assertEquals(
            listOf(CacheService.DetailFreshness.FRESH, CacheService.DetailFreshness.FRESH),
            snapshot.configurations.map { it.freshness }
        )
        assertEquals(2, snapshot.freshConfigurations.size)
    }

    @Test
    fun `a snapshot taken after the clock moved reports the later freshness`() = runBlocking {
        var now = 1_000_000L
        val cacheService = loadedCache({ now })
        cacheService.writeDetail(identity, null, config("app.properties", "a=1"), ttl = 100L)

        now += 101L

        val snapshot = cacheService.snapshot(identity)
        assertEquals(now, snapshot.asOfMillis)
        assertEquals(CacheService.DetailFreshness.STALE, snapshot.configurations.single().freshness)
        assertTrue(snapshot.freshConfigurations.isEmpty())
    }

    // ── Scope ──

    @Test
    fun `a snapshot only sees its own access identity`() = runBlocking {
        val cacheService = loadedCache()
        val other = testIdentity("http://other:8848", "admin", AuthMode.TOKEN)
        cacheService.writeDetail(identity, null, config("mine.properties", "k=v"))
        cacheService.writeDetail(other, null, config("theirs.properties", "k=v"))

        assertEquals(
            listOf("mine.properties"),
            cacheService.snapshot(identity).configurations.map { it.configuration.dataId }
        )
        assertEquals(
            listOf("theirs.properties"),
            cacheService.snapshot(other).configurations.map { it.configuration.dataId }
        )
    }

    @Test
    fun `a snapshot resolves a single detail by coordinate`() = runBlocking {
        val cacheService = loadedCache()
        cacheService.writeDetail(identity, "dev", config("app.properties", "k=v", "dev"))

        val snapshot = cacheService.snapshot(identity)
        assertNotNull(snapshot.detail("dev", "app.properties", "DEFAULT_GROUP"))
        assertNull(snapshot.detail("dev", "absent.properties", "DEFAULT_GROUP"))
        assertNull(snapshot.detail("prod", "app.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `a snapshot carries namespace index authority for absence`() = runBlocking {
        val cacheService = loadedCache()
        cacheService.replaceNamespaceIndex(identity, "dev", listOf(config("app.properties", "", "dev")))

        val complete = cacheService.snapshot(identity).namespaceIndex("dev")
        assertEquals(setOf("app.properties"), complete?.dataIds)
        assertTrue(complete!!.authoritativeForAbsence)

        cacheService.markNamespaceIndexNonAuthoritative(identity, "dev")
        assertFalse(cacheService.snapshot(identity).namespaceIndex("dev")!!.authoritativeForAbsence)
    }

    @Test
    fun `a namespace index restored from the store cannot prove a configuration absent`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = CacheService(store)
        first.replaceNamespaceIndex(identity, "dev", listOf(config("app.properties", "", "dev")))
        assertTrue(first.snapshot(identity).namespaceIndex("dev")!!.authoritativeForAbsence)

        val restarted = CacheService(store)
        restarted.awaitLoadCompleted()

        assertNull(restarted.snapshot(identity).namespaceIndex("dev"))
    }
}

/**
 * Store adapter whose entries the full background load never sees, so only a
 * single-key read can reach them. It can also park one such read after it has
 * taken the entry out of the store but before the cache publishes it, which is
 * the only window in which two reads race to publish the same entry.
 */
private class LazyOnlyDetailStore(
    private val delegate: InMemoryCacheStore = InMemoryCacheStore()
) : CacheStore by delegate {
    private val parked = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    @Volatile
    private var parkNext = false

    fun parkNextRead() {
        parkNext = true
    }

    suspend fun awaitParked() = parked.await()

    fun releaseParkedRead() {
        release.complete(Unit)
    }

    override suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>> = emptyMap()

    override suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>? {
        val entry = delegate.loadDetail(key)
        if (parkNext) {
            parkNext = false
            parked.complete(Unit)
            release.await()
        }
        return entry
    }
}
