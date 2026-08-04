package com.nanyin.nacos.search.services

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.ConfigItem
import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.psi.ConfigReferenceStatus
import com.nanyin.nacos.search.psi.NacosKeyResolver
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Drives the cache module's one gated write entry point through its public
 * interface: given a sequence of mutations with their observation sequences,
 * what does a subsequent read return? Nothing here asserts which internal map
 * was touched or in what order — the ordering machinery is the thing under
 * test, not the thing to reach through (issue #63).
 */
class CacheWriteGateTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private val identity = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)

    private fun config(dataId: String, content: String, group: String = "DEFAULT_GROUP") =
        NacosConfiguration(dataId, group, null, content, "properties")

    private fun listPage(vararg dataIds: String) = ConfigListResponse(
        totalCount = dataIds.size,
        pageNumber = 1,
        pagesAvailable = 1,
        pageItems = dataIds.mapIndexed { index, id ->
            ConfigItem(index.toString(), id, "DEFAULT_GROUP", "k=v", "properties", null)
        }
    )

    private fun cache(
        store: CacheStore = InMemoryCacheStore(),
        tombstones: ProfileTombstoneRegistry = ProfileTombstoneRegistry()
    ) = CacheService(System::currentTimeMillis, tombstones, store)

    // ── Ordering ──

    @Test
    fun `a later observation lands and an earlier one for the same coordinate is dropped`() = runBlocking {
        val cacheService = cache()

        assertTrue(
            cacheService.applyMutation(
                CacheMutation.WriteDetail(identity, "dev", config("app.properties", "v=new"), TTL), 10
            )
        )
        assertFalse(
            cacheService.applyMutation(
                CacheMutation.WriteDetail(identity, "dev", config("app.properties", "v=old"), TTL), 4
            )
        )

        assertEquals(
            "v=new",
            cacheService.getConfigDetail(identity, "dev", "app.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `ordering is per coordinate, so an unrelated key is unaffected`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "a"), TTL), 10)

        assertTrue(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("b.properties", "b"), TTL), 4)
        )
        assertEquals("b", cacheService.getConfigDetail(identity, "dev", "b.properties", "DEFAULT_GROUP")?.content)
    }

    @Test
    fun `a late list page cannot resurrect an older page`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteListPage(identity, "dev", "page-1", listPage("new"), TTL), 10)
        assertFalse(
            cacheService.applyMutation(CacheMutation.WriteListPage(identity, "dev", "page-1", listPage("old"), TTL), 3)
        )

        assertEquals(
            listOf("new"),
            cacheService.getListPage(identity, "dev", "page-1")?.pageItems?.map { it.dataId }
        )
    }

    @Test
    fun `a late failed index cannot mark a newer complete index non-authoritative`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(
            CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("a.properties", "")), TTL), 10
        )
        assertFalse(cacheService.applyMutation(CacheMutation.MarkNamespaceIndexNonAuthoritative(identity, "dev"), 6))

        assertTrue(cacheService.snapshot(identity).namespaceIndex("dev")!!.authoritativeForAbsence)
    }

    @Test
    fun `a later-started detail read restores a configuration an earlier not-found deleted`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "one"), TTL), 5)
        // The not-found observation started after the write, so it wins.
        assertTrue(
            cacheService.applyMutation(CacheMutation.DeleteDetailNotFound(identity, "dev", "a.properties", "DEFAULT_GROUP"), 7)
        )
        assertNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP"))

        // A read that started later still restores the recreated configuration.
        assertTrue(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "two"), TTL), 9)
        )
        assertEquals(
            "two",
            cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `an earlier not-found cannot delete what a later read confirmed`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "one"), TTL), 9)
        assertFalse(
            cacheService.applyMutation(CacheMutation.DeleteDetailNotFound(identity, "dev", "a.properties", "DEFAULT_GROUP"), 4)
        )

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP"))
    }

    // ── Gate scope: the complete access identity ──

    @Test
    fun `two identities differing only in resolved API generation do not gate each other`() = runBlocking {
        val cacheService = cache()
        val v1 = testIdentity(generation = NacosApiGeneration.V1)
        val v3 = testIdentity(generation = NacosApiGeneration.V3)

        assertTrue(cacheService.applyMutation(CacheMutation.WriteDetail(v3, "dev", config("a.properties", "v3"), TTL), 10))
        // Older sequence, different identity: it writes to a distinct coordinate
        // and must not be rejected by the other identity's mark.
        assertTrue(cacheService.applyMutation(CacheMutation.WriteDetail(v1, "dev", config("a.properties", "v1"), TTL), 3))

        assertEquals("v3", cacheService.getConfigDetail(v3, "dev", "a.properties", "DEFAULT_GROUP")?.content)
        assertEquals("v1", cacheService.getConfigDetail(v1, "dev", "a.properties", "DEFAULT_GROUP")?.content)
    }

    @Test
    fun `identities differing in any single component are mutually invisible and independent`() = runBlocking {
        val base = testIdentity()
        val variants = mapOf(
            "generation" to testIdentity(generation = NacosApiGeneration.V3),
            "auth strategy" to testIdentity(authMode = AuthMode.BASIC),
            "principal" to testIdentity(principal = "other"),
            "endpoint" to testIdentity(endpoint = "http://other:8848", profileId = base.profileId),
            "access revision" to testIdentity(accessRevision = 2L)
        )

        variants.forEach { (label, variant) ->
            val cacheService = cache()
            cacheService.applyMutation(CacheMutation.WriteDetail(base, "dev", config("a.properties", "base"), TTL), 10)

            assertTrue(
                "$label must write to its own coordinate",
                cacheService.applyMutation(CacheMutation.WriteDetail(variant, "dev", config("a.properties", label), TTL), 2)
            )
            assertEquals(
                "base", cacheService.getConfigDetail(base, "dev", "a.properties", "DEFAULT_GROUP")?.content
            )
            assertEquals(
                label, cacheService.getConfigDetail(variant, "dev", "a.properties", "DEFAULT_GROUP")?.content
            )
        }
    }

    @Test
    fun `changing a credential makes the previous identity's cached data invisible immediately`() = runBlocking {
        val cacheService = cache()
        val before = testIdentity(accessRevision = 1L)
        val after = testIdentity(accessRevision = 2L)

        cacheService.applyMutation(CacheMutation.WriteDetail(before, "dev", config("a.properties", "old creds"), TTL), 1)

        assertNull(cacheService.getConfigDetail(after, "dev", "a.properties", "DEFAULT_GROUP"))
        assertTrue(cacheService.snapshot(after).freshConfigurations.isEmpty())
    }

    @Test
    fun `two projects working against the same access identity share cached data`() = runBlocking {
        // One application-level cache, two readers: sharing is what identity
        // scoping means, and nothing about the ordering machinery narrows it.
        val cacheService = cache()
        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "shared"), TTL), 1)

        val projectA = cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")
        val projectB = cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")

        assertEquals("shared", projectA?.content)
        assertEquals(projectA, projectB)
    }

    // ── Clear ──

    @Test
    fun `a clear taking a middle sequence rejects an earlier write and accepts a later one`() = runBlocking {
        val cacheService = cache()

        assertTrue(cacheService.applyMutation(CacheMutation.Clear, 5))

        assertFalse(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "in flight"), TTL), 3)
        )
        assertNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP"))

        assertTrue(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "reloaded"), TTL), 8)
        )
        assertEquals(
            "reloaded",
            cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")?.content
        )
    }

    @Test
    fun `a clear leaves no payload behind in the store`() = runBlocking {
        val store = InMemoryCacheStore()
        val cacheService = cache(store)

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "x"), TTL), 1)
        cacheService.applyMutation(CacheMutation.WriteListPage(identity, "dev", "page-1", listPage("a"), TTL), 2)
        cacheService.applyMutation(CacheMutation.Clear, 3)

        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadListPages().isEmpty())
    }

    @Test
    fun `a namespace invalidation drops a write for that namespace that started earlier`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "x"), TTL), 1)
        assertTrue(cacheService.applyMutation(CacheMutation.InvalidateNamespace(identity, "dev"), 5))

        assertFalse(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "late"), TTL), 3)
        )
        assertTrue(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "prod", config("a.properties", "other ns"), TTL), 3)
        )

        assertNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP"))
        assertEquals(
            "other ns",
            cacheService.getConfigDetail(identity, "prod", "a.properties", "DEFAULT_GROUP")?.content
        )
    }

    // ── Reconstituting from the cache's own store is not a mutation ──

    @Test
    fun `a lazy single-entry load cannot republish an entry across a concurrent clear`() = runBlocking {
        val store = BlockingDetailStore()
        val seeded = cache(store)
        seeded.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "discarded"), TTL), 1)

        // A fresh cache over the same store: nothing is in memory, so a single-key
        // read has to reconstitute the entry from the store.
        val cacheService = cache(store)
        cacheService.awaitLoadCompleted()
        store.holdNextLoad()

        coroutineScope {
            val read = async {
                cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP", allowStale = true)
            }
            store.awaitLoadStarted()
            cacheService.applyMutation(CacheMutation.Clear, 9)
            store.releaseLoad()

            assertNull("a reconstituted entry must not survive the clear", read.await())
        }

        assertNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP", allowStale = true))
        assertTrue(cacheService.snapshot(identity).freshConfigurations.isEmpty())
    }

    @Test
    fun `reconstituting an entry does not raise the coordinate's mark`() = runBlocking {
        // Reconstitution is an observation of the cache's own store, not of the
        // server, so it is not a mutation and cannot gate a real one.
        val store = InMemoryCacheStore()
        cache(store).applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "stored"), TTL), 100)

        val cacheService = cache(store)
        cacheService.awaitLoadCompleted()
        assertEquals(
            "stored",
            cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")?.content
        )

        assertTrue(
            cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "fresh"), TTL), 1)
        )
        assertEquals(
            "fresh",
            cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP")?.content
        )
    }

    // ── Namespace index deletion rules ──

    @Test
    fun `a complete index removes a detail it no longer lists`() = runBlocking {
        val store = InMemoryCacheStore()
        val cacheService = cache(store)

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("gone.properties", "x"), TTL), 1)
        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("kept.properties", "y"), TTL), 2)

        cacheService.applyMutation(
            CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("kept.properties", "")), TTL), 5
        )

        assertNull(cacheService.getConfigDetail(identity, "dev", "gone.properties", "DEFAULT_GROUP", allowStale = true))
        assertNotNull(cacheService.getConfigDetail(identity, "dev", "kept.properties", "DEFAULT_GROUP"))
        assertTrue(store.loadDetails().keys.none { it.contains("gone.properties") })
    }

    @Test
    fun `a complete index keeps a detail a newer observation confirmed`() = runBlocking {
        val cacheService = cache()

        // The index started at 5; the detail read that confirmed this key started
        // at 7, so the older index view must not delete it.
        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("recreated.properties", "x"), TTL), 7)
        cacheService.applyMutation(
            CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("kept.properties", "")), TTL), 5
        )

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "recreated.properties", "DEFAULT_GROUP"))
    }

    @Test
    fun `a non-authoritative mark never deletes`() = runBlocking {
        val cacheService = cache()

        cacheService.applyMutation(CacheMutation.WriteDetail(identity, "dev", config("a.properties", "x"), TTL), 1)
        cacheService.applyMutation(
            CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("a.properties", "")), TTL), 2
        )
        assertTrue(cacheService.applyMutation(CacheMutation.MarkNamespaceIndexNonAuthoritative(identity, "dev"), 3))

        assertNotNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP"))
        assertFalse(cacheService.snapshot(identity).namespaceIndex("dev")!!.authoritativeForAbsence)
    }

    @Test
    fun `a restored namespace index cannot prove a configuration absent`() = runBlocking {
        val store = InMemoryCacheStore()
        val first = cache(store)
        first.applyMutation(
            CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("a.properties", "")), TTL), 1
        )
        assertTrue(first.snapshot(identity).namespaceIndex("dev")!!.authoritativeForAbsence)

        // "Restart": a second cache over the same store. Index authority is
        // memory-only on purpose, so absence is undecidable rather than proven.
        val restarted = cache(store)
        restarted.awaitLoadCompleted()

        assertNull(restarted.snapshot(identity).namespaceIndex("dev"))
        // A code reference to a data id the pre-restart index did not list is
        // reported as undecidable, never as an unresolved (confidently absent)
        // one — no amount of ordering machinery turns restored data into a
        // successful check this run.
        assertNotEquals(
            ConfigReferenceStatus.UNRESOLVED,
            NacosKeyResolver.dataIdPresenceResolution(
                dataId = "never.listed",
                snapshot = restarted.snapshot(identity),
                activeNamespaceId = "dev"
            ).status
        )
        // The same check against the live index does prove absence.
        assertEquals(
            ConfigReferenceStatus.UNRESOLVED,
            NacosKeyResolver.dataIdPresenceResolution(
                dataId = "never.listed",
                snapshot = first.snapshot(identity),
                activeNamespaceId = "dev"
            ).status
        )
    }

    // ── The tombstone is absolute ──

    @Test
    fun `no mutation kind lands after the profile is entombed`() = runBlocking {
        val tombstones = ProfileTombstoneRegistry()
        val store = InMemoryCacheStore()
        val cacheService = cache(store, tombstones)

        val identityScoped = identityScopedMutations()
        tombstones.entomb(identity.profileId, identity.accessRevision)

        identityScoped.forEach { mutation ->
            assertFalse(
                "${mutation::class.simpleName} must not land for an entombed profile",
                // A very recent observation must not outrank the tombstone.
                cacheService.applyMutation(mutation, Long.MAX_VALUE)
            )
        }

        assertNull(cacheService.getConfigDetail(identity, "dev", "a.properties", "DEFAULT_GROUP", allowStale = true))
        assertNull(cacheService.getListPage(identity, "dev", "page-1", allowStale = true))
        assertNull(cacheService.snapshot(identity).namespaceIndex("dev"))
        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadListPages().isEmpty())
    }

    @Test
    fun `the tombstone check covers the whole mutation set`() {
        // Enumerated from the sealed hierarchy so a newly added mutation kind
        // cannot silently skip the check: it fails here until it is listed.
        val covered = (identityScopedMutations() + CacheMutation.Clear).map { it::class }.toSet()

        assertEquals(CacheMutation::class.sealedSubclasses.toSet(), covered)
    }

    @Test
    fun `a user's clear still lands for an entombed profile`() = runBlocking {
        // Clear is not profile-scoped: it discards data rather than resurrecting
        // it, so a tombstone has nothing to protect against.
        val tombstones = ProfileTombstoneRegistry()
        val cacheService = cache(tombstones = tombstones)
        tombstones.entomb(identity.profileId, identity.accessRevision)

        assertTrue(cacheService.applyMutation(CacheMutation.Clear, 1))
    }

    private fun identityScopedMutations(): List<CacheMutation> = listOf(
        CacheMutation.WriteDetail(identity, "dev", config("a.properties", "x"), TTL),
        CacheMutation.WriteListPage(identity, "dev", "page-1", listPage("a"), TTL),
        CacheMutation.ReplaceNamespaceIndex(identity, "dev", listOf(config("a.properties", "")), TTL),
        CacheMutation.MarkNamespaceIndexNonAuthoritative(identity, "dev"),
        CacheMutation.DeleteDetailNotFound(identity, "dev", "a.properties", "DEFAULT_GROUP"),
        CacheMutation.InvalidateNamespace(identity, "dev")
    )

    private companion object {
        const val TTL = 300_000L
    }
}

/**
 * Store adapter for the lazy single-entry load path. Its entries are invisible
 * to the full background load, so only a single-key read can reach them, and it
 * can park that read after it has taken the entry out of the store but before
 * the cache publishes it — the window a clear used to slip through.
 */
private class BlockingDetailStore(
    private val delegate: InMemoryCacheStore = InMemoryCacheStore()
) : CacheStore by delegate {
    private var gate: CompletableDeferred<Unit>? = null
    private val started = CompletableDeferred<Unit>()

    fun holdNextLoad() {
        gate = CompletableDeferred()
    }

    suspend fun awaitLoadStarted() = started.await()

    fun releaseLoad() {
        gate?.complete(Unit)
    }

    override suspend fun loadDetails(): Map<String, CacheService.CacheEntry<NacosConfiguration>> = emptyMap()

    override suspend fun loadDetail(key: String): CacheService.CacheEntry<NacosConfiguration>? {
        val entry = delegate.loadDetail(key)
        gate?.let {
            started.complete(Unit)
            it.await()
        }
        return entry
    }
}

private fun testIdentity(
    endpoint: String = "http://nacos:8848",
    principal: String = "admin",
    authMode: AuthMode = AuthMode.TOKEN,
    profileId: String = "profile-a",
    accessRevision: Long = 1L,
    generation: NacosApiGeneration = NacosApiGeneration.V1
): AccessIdentity = AccessIdentity.ofProfile(
    profileId = profileId,
    accessRevision = accessRevision,
    canonicalEndpoint = endpoint,
    resolvedGeneration = generation,
    authMode = authMode,
    principal = principal
)
