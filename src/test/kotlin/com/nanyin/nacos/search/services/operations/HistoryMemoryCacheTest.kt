package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HistoryMemoryCacheTest {

    @Test
    fun `history page is isolated by access identity`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identityA = identity("profile-a")
        val identityB = identity("profile-b")
        val coordinate = ConfigurationCoordinate("app.yaml", "G")
        val query = HistoryQuery(coordinate)

        val pageA = HistoryPage(1, 1, 1, listOf(HistoryEntry("1", "app.yaml", "G", null, "yaml", "m1", 1000L, "PUBLISH")))
        cache.putHistoryPage(identityA, "public", query.cacheKey(), pageA, observation = 1)

        assertSame(pageA, cache.getHistoryPage(identityA, "public", query.cacheKey()))
        assertNull(cache.getHistoryPage(identityB, "public", query.cacheKey()))
    }

    @Test
    fun `history page is isolated by namespace`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identity = identity("profile-a")
        val coordinate = ConfigurationCoordinate("app.yaml", "G")
        val query = HistoryQuery(coordinate)

        val page = HistoryPage(1, 1, 1, emptyList())
        cache.putHistoryPage(identity, "dev", query.cacheKey(), page, observation = 2)

        assertSame(page, cache.getHistoryPage(identity, "dev", query.cacheKey()))
        assertNull(cache.getHistoryPage(identity, "prod", query.cacheKey()))
    }

    @Test
    fun `history detail is isolated by access identity and historyId`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identity = identity("profile-a")

        val detail = HistoryDetail("1", "app.yaml", "G", null, "content-v1", "yaml", "md5", 1000L, "PUBLISH")
        cache.putHistoryDetail(identity, "public", "1", detail, observation = 3)

        assertSame(detail, cache.getHistoryDetail(identity, "public", "1"))
        assertNull(cache.getHistoryDetail(identity, "public", "2"))
        assertNull(cache.getHistoryDetail(identity("profile-b"), "public", "1"))
    }

    @Test
    fun `eviction drops oldest page entries when capacity is exceeded`() {
        val cache = HistoryMemoryCache(maxPages = 2, maxDetails = 10)
        val identity = identity("profile-a")
        val coordinate = ConfigurationCoordinate("app", "G")

        val page1 = HistoryPage(1, 1, 1, emptyList())
        val page2 = HistoryPage(2, 1, 1, emptyList())
        val page3 = HistoryPage(3, 1, 1, emptyList())

        cache.putHistoryPage(identity, "public", "k1", page1, observation = 4)
        cache.putHistoryPage(identity, "public", "k2", page2, observation = 5)
        cache.putHistoryPage(identity, "public", "k3", page3, observation = 6)

        assertNull(cache.getHistoryPage(identity, "public", "k1"))
        assertNotNull(cache.getHistoryPage(identity, "public", "k2"))
        assertNotNull(cache.getHistoryPage(identity, "public", "k3"))
    }

    @Test
    fun `eviction drops oldest detail entries when capacity is exceeded`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 2)
        val identity = identity("profile-a")

        val d1 = HistoryDetail("1", "a", "G", null, "c1", "yaml", "m1", 1L, "P")
        val d2 = HistoryDetail("2", "b", "G", null, "c2", "yaml", "m2", 2L, "P")
        val d3 = HistoryDetail("3", "c", "G", null, "c3", "yaml", "m3", 3L, "P")

        cache.putHistoryDetail(identity, "public", "1", d1, observation = 7)
        cache.putHistoryDetail(identity, "public", "2", d2, observation = 8)
        cache.putHistoryDetail(identity, "public", "3", d3, observation = 9)

        assertNull(cache.getHistoryDetail(identity, "public", "1"))
        assertNotNull(cache.getHistoryDetail(identity, "public", "2"))
        assertNotNull(cache.getHistoryDetail(identity, "public", "3"))
    }

    @Test
    fun `clear removes all entries for one identity`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 10)
        val identityA = identity("profile-a")
        val identityB = identity("profile-b")

        cache.putHistoryPage(identityA, "public", "k", HistoryPage(1, 1, 1, emptyList()), observation = 10)
        cache.putHistoryPage(identityB, "public", "k", HistoryPage(2, 1, 1, emptyList()), observation = 11)

        cache.clearForIdentity(identityA)

        assertNull(cache.getHistoryPage(identityA, "public", "k"))
        assertNotNull(cache.getHistoryPage(identityB, "public", "k"))
    }

    @Test
    fun `access order updates LRU position`() {
        val cache = HistoryMemoryCache(maxPages = 2, maxDetails = 10)
        val identity = identity("profile-a")

        cache.putHistoryPage(identity, "public", "k1", HistoryPage(1, 1, 1, emptyList()), observation = 12)
        cache.putHistoryPage(identity, "public", "k2", HistoryPage(2, 1, 1, emptyList()), observation = 13)
        // Access k1 to make it more recently used than k2
        cache.getHistoryPage(identity, "public", "k1")
        // Now k2 should be evicted, not k1
        cache.putHistoryPage(identity, "public", "k3", HistoryPage(3, 1, 1, emptyList()), observation = 14)

        assertNotNull(cache.getHistoryPage(identity, "public", "k1"))
        assertNull(cache.getHistoryPage(identity, "public", "k2"))
        assertNotNull(cache.getHistoryPage(identity, "public", "k3"))
    }

    @Test
    fun `a stale history page cannot overwrite a newer one`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identity = identity("profile-a")
        val newer = HistoryPage(2, 1, 1, emptyList())

        assertTrue(cache.putHistoryPage(identity, "public", "k", newer, observation = 20))
        assertFalse(
            cache.putHistoryPage(identity, "public", "k", HistoryPage(1, 1, 1, emptyList()), observation = 15)
        )

        assertSame(newer, cache.getHistoryPage(identity, "public", "k"))
    }

    @Test
    fun `a stale history detail cannot overwrite a newer one`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identity = identity("profile-a")
        val newer = HistoryDetail("1", "app.yaml", "G", null, "v2", "yaml", "md5-2", 2000L, "PUBLISH")

        assertTrue(cache.putHistoryDetail(identity, "public", "1", newer, observation = 20))
        assertFalse(
            cache.putHistoryDetail(
                identity,
                "public",
                "1",
                HistoryDetail("1", "app.yaml", "G", null, "v1", "yaml", "md5-1", 1000L, "PUBLISH"),
                observation = 15
            )
        )

        assertSame(newer, cache.getHistoryDetail(identity, "public", "1"))
    }

    @Test
    fun `history ordering is per key, so an unrelated page is unaffected`() {
        val cache = HistoryMemoryCache(maxPages = 10, maxDetails = 20)
        val identity = identity("profile-a")

        cache.putHistoryPage(identity, "public", "k1", HistoryPage(1, 1, 1, emptyList()), observation = 20)

        assertTrue(
            cache.putHistoryPage(identity, "public", "k2", HistoryPage(2, 1, 1, emptyList()), observation = 5)
        )
    }

    private fun identity(profileId: String): AccessIdentity = AccessIdentity.ofProfile(
        profileId = profileId,
        accessRevision = 1,
        canonicalEndpoint = "https://nacos.example",
        resolvedGeneration = NacosApiGeneration.V1,
        authMode = AuthMode.ANONYMOUS,
        principal = "<anonymous>"
    )
}
