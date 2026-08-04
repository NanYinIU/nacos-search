package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.settings.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CacheCoordinateTest {

    private val identityA = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)
    private val identityB = testIdentity("http://nacos:8848", "user", AuthMode.TOKEN)

    @Test
    fun `detail coordinates with different identities produce different storage keys`() {
        val a = CacheCoordinate.Detail(identityA, "http://nacos:8848", "dev", "app.properties", "DEFAULT_GROUP")
        val b = CacheCoordinate.Detail(identityB, "http://nacos:8848", "dev", "app.properties", "DEFAULT_GROUP")
        assertFalse(a.storageKey() == b.storageKey())
    }

    @Test
    fun `same identity and params produce same key`() {
        val a = CacheCoordinate.NamespaceIndex(identityA, "http://nacos:8848", "dev")
        val b = CacheCoordinate.NamespaceIndex(identityA, "http://nacos:8848/", "dev")
        assertEquals(a.storageKey(), b.storageKey())
    }

    @Test
    fun `anonymous identity is distinct from named`() {
        val anon = testIdentity("http://nacos:8848", "", AuthMode.BASIC)
        val named = testIdentity("http://nacos:8848", "admin", AuthMode.BASIC)
        val keyAnon = CacheCoordinate.Detail(anon, "http://nacos:8848", "public", "d", "g").storageKey()
        val keyNamed = CacheCoordinate.Detail(named, "http://nacos:8848", "public", "d", "g").storageKey()
        assertFalse(keyAnon == keyNamed)
    }

    @Test
    fun `storage key never contains secrets`() {
        val coord = CacheCoordinate.Detail(identityA, "http://nacos:8848", "dev", "app.properties", "DEFAULT_GROUP")
        val key = coord.storageKey()
        assertFalse("Key should not contain 'password'", key.contains("password", ignoreCase = true))
        assertFalse("Key should not contain 'accessToken='", key.contains("accessToken=", ignoreCase = true))
        assertFalse("Key should not contain 'Authorization'", key.contains("Authorization", ignoreCase = true))
    }

    @Test
    fun `list page key includes request key`() {
        val a = CacheCoordinate.ListPage(identityA, "http://nacos:8848", "dev", "page=1&size=100")
        val b = CacheCoordinate.ListPage(identityA, "http://nacos:8848", "dev", "page=2&size=100")
        assertFalse(a.storageKey() == b.storageKey())
    }

    @Test
    fun `access revision participates in storage key`() {
        val rev1 = testIdentity(accessRevision = 1)
        val rev2 = testIdentity(accessRevision = 2)
        val a = CacheCoordinate.Detail(rev1, rev1.canonicalEndpoint, "dev", "app.properties", "DEFAULT_GROUP")
        val b = CacheCoordinate.Detail(rev2, rev2.canonicalEndpoint, "dev", "app.properties", "DEFAULT_GROUP")
        assertFalse(a.storageKey() == b.storageKey())
    }
}
