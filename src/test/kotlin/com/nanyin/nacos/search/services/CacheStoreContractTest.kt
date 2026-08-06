package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.ConfigListResponse
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.visibility.AccessRefusalReason
import com.nanyin.nacos.search.services.visibility.AccessVisibilityRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The behaviour every [CacheStore] adapter owes the cache module. Both the
 * file-backed production adapter and the in-memory test adapter run this suite,
 * so substituting one for the other in a cache test cannot change what the
 * cache observes.
 *
 * The store owns the key list as well as the payloads, so a stored entry is
 * always enumerable and a removed entry always disappears from both.
 * Visibility records that gate those payloads share the same contract
 * (issue #123).
 */
internal abstract class CacheStoreContractTest {

    protected abstract fun newStore(): CacheStore

    private fun detail(dataId: String, content: String, createdAt: Long = 1_000L) =
        CacheService.CacheEntry(
            type = CacheService.CacheEntryType.CONFIG_DETAIL,
            data = NacosConfiguration(dataId, "DEFAULT_GROUP", "dev", content, "properties"),
            createdAt = createdAt,
            ttlMs = 60_000L,
            source = CacheService.CacheSource.REMOTE
        )

    private fun listPage(totalCount: Int) = CacheService.CacheEntry(
        type = CacheService.CacheEntryType.LIST_PAGE,
        data = ConfigListResponse(totalCount, 1, 1, emptyList()),
        createdAt = 1_000L,
        ttlMs = 60_000L,
        source = CacheService.CacheSource.REMOTE
    )

    private fun visibilityRecord(profileId: String = "p") = AccessVisibilityRecord(
        profileId = profileId,
        accessRevision = 1,
        canonicalEndpoint = "http://nacos",
        resolvedGeneration = "V1",
        authMode = "BASIC",
        principal = "alice",
        capability = AccessVisibilityRecord.IDENTITY_AUTH,
        namespaceId = null,
        refusalReason = AccessRefusalReason.AUTHENTICATION.name,
        detail = "Authentication failed",
        recordedAtMillis = 1_000L
    )

    @Test
    fun `a stored detail is readable by key and enumerated with its key`() = runBlocking {
        val store = newStore()

        store.putDetail("v2|p|1|http://nacos|V1|BASIC|alice|dev|app.yaml|DEFAULT_GROUP", detail("app.yaml", "k=v"))

        val key = "v2|p|1|http://nacos|V1|BASIC|alice|dev|app.yaml|DEFAULT_GROUP"
        assertEquals("k=v", store.loadDetail(key)?.data?.content)
        assertEquals(mapOf(key to "k=v"), store.loadDetails().mapValues { it.value.data.content })
    }

    @Test
    fun `removing a detail drops its payload and its key entry together`() = runBlocking {
        val store = newStore()
        store.putDetail("kept", detail("kept.yaml", "keep=1"))
        store.putDetail("dropped", detail("dropped.yaml", "drop=1"))

        store.removeDetail("dropped")

        assertNull(store.loadDetail("dropped"))
        assertEquals(setOf("kept"), store.loadDetails().keys)
    }

    @Test
    fun `list pages are enumerated with their keys and removed the same way`() = runBlocking {
        val store = newStore()
        store.putListPage("v2|p|1|http://nacos|V1|BASIC|alice|dev|page=1", listPage(totalCount = 7))
        store.putListPage("v2|p|1|http://nacos|V1|BASIC|alice|dev|page=2", listPage(totalCount = 9))

        assertEquals(
            mapOf(
                "v2|p|1|http://nacos|V1|BASIC|alice|dev|page=1" to 7,
                "v2|p|1|http://nacos|V1|BASIC|alice|dev|page=2" to 9
            ),
            store.loadListPages().mapValues { it.value.data.totalCount }
        )

        store.removeListPage("v2|p|1|http://nacos|V1|BASIC|alice|dev|page=2")

        assertEquals(
            setOf("v2|p|1|http://nacos|V1|BASIC|alice|dev|page=1"),
            store.loadListPages().keys
        )
    }

    @Test
    fun `clearing leaves no payload behind`() = runBlocking {
        val store = newStore()
        store.putDetail("d|1", detail("app.yaml", "k=v"))
        store.putListPage("l|1", listPage(totalCount = 1))

        store.clear()

        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadListPages().isEmpty())
        assertNull(store.loadDetail("d|1"))
    }

    @Test
    fun `a stored visibility record is enumerable and removable`() = runBlocking {
        val store = newStore()
        val key = "vis|auth|v2|p|1|http://nacos|V1|BASIC|alice"
        store.putVisibilityRecord(key, visibilityRecord())

        assertEquals(
            AccessRefusalReason.AUTHENTICATION.name,
            store.loadVisibilityRecords()[key]?.refusalReason
        )

        store.removeVisibilityRecord(key)

        assertTrue(store.loadVisibilityRecords().isEmpty())
    }

    @Test
    fun `clearing drops visibility records together with payloads`() = runBlocking {
        val store = newStore()
        store.putDetail("d|1", detail("app.yaml", "k=v"))
        store.putVisibilityRecord(
            "vis|auth|v2|p|1|http://nacos|V1|BASIC|alice",
            visibilityRecord()
        )

        store.clear()

        assertTrue(store.loadDetails().isEmpty())
        assertTrue(store.loadVisibilityRecords().isEmpty())
    }
}
