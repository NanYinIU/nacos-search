package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NacosKeyResolverTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private lateinit var cache: CacheService

    @Before
    fun setUp() = runBlocking {
        cache = CacheService()
        cache.clearAll()
    }

    private fun cfg(dataId: String, group: String, tenant: String?, content: String, type: String) =
        NacosConfiguration(dataId, group, tenant, content, type)

    @Test
    fun `empty cache returns no hits`() {
        assertTrue(NacosKeyResolver.resolve("anything", cache).isEmpty())
    }

    @Test
    fun `single hit in properties`() = runBlocking {
        cache.cacheConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        val hits = NacosKeyResolver.resolve("timeout", cache)
        assertEquals(1, hits.size)
        assertEquals("3000", hits[0].location.value)
    }

    @Test
    fun `multiple hits across configs`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("application.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"),
                cfg("common.yaml", "SHARED", "dev", "timeout: 5000\n", "yaml")
            )
        )
        val hits = NacosKeyResolver.resolve("timeout", cache)
        assertEquals(2, hits.size)
    }

    @Test
    fun `active server url scopes resolution`() = runBlocking {
        cache.putConfigDetail(
            serverUrl = "http://dev-nacos:8848",
            namespaceId = null,
            configuration = cfg("app.properties", "DEFAULT_GROUP", null, "app.name=dev\n", "properties"),
            ttl = 60_000L
        )
        cache.putConfigDetail(
            serverUrl = "http://prod-nacos:8848",
            namespaceId = null,
            configuration = cfg("app.properties", "DEFAULT_GROUP", null, "app.name=prod\n", "properties"),
            ttl = 60_000L
        )

        val hits = NacosKeyResolver.resolve(
            "app.name",
            cache,
            activeServerUrl = "http://prod-nacos:8848/"
        )

        assertEquals(1, hits.size)
        assertEquals("prod", hits.single().location.value)
    }

    @Test
    fun `active namespace sorts first`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                // public
                cfg("p.properties", "DEFAULT_GROUP", null, "k=pub\n", "properties"),
                // dev namespace (active)
                cfg("d.properties", "DEFAULT_GROUP", "dev", "k=dev\n", "properties"),
                // other namespace
                cfg("o.properties", "DEFAULT_GROUP", "sit", "k=sit\n", "properties")
            )
        )
        val hits = NacosKeyResolver.resolve("k", cache, activeNamespaceId = "dev")
        assertEquals(3, hits.size)
        // active namespace (dev) first
        assertEquals("dev", hits[0].config.tenantId)
        assertEquals("dev", hits[0].location.value)
        // then public
        assertEquals(null, hits[1].config.tenantId)
        // then other
        assertEquals("sit", hits[2].config.tenantId)
    }

    @Test
    fun `cross namespace disabled only returns active namespace hits`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                cfg("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "room.key",
            cacheService = cache,
            activeNamespaceId = "namespace1",
            allowCrossNamespace = false
        )

        assertEquals(1, hits.size)
        assertEquals("namespace1", hits.single().config.tenantId)
        assertEquals("one", hits.single().location.value)
    }

    @Test
    fun `cross namespace disabled treats blank and public as same namespace`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("public-empty.properties", "DEFAULT_GROUP", null, "room.key=empty\n", "properties"),
                cfg("public-literal.properties", "DEFAULT_GROUP", "public", "room.key=literal\n", "properties"),
                cfg("other.properties", "DEFAULT_GROUP", "namespace2", "room.key=other\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "room.key",
            cacheService = cache,
            activeNamespaceId = "public",
            allowCrossNamespace = false
        )

        assertEquals(2, hits.size)
        assertTrue(hits.all { it.config.tenantId == null || it.config.tenantId == "public" })
    }

    @Test
    fun `cross namespace enabled returns all namespaces with active namespace first`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("room-one.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                cfg("room-two.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "room.key",
            cacheService = cache,
            activeNamespaceId = "namespace1",
            allowCrossNamespace = true
        )

        assertEquals(2, hits.size)
        assertEquals("namespace1", hits.first().config.tenantId)
        assertEquals("namespace2", hits.last().config.tenantId)
    }

    @Test
    fun `preferred group sorts before other groups for same key`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("shared.properties", "SHARED_GROUP", null, "timeout=1000\n", "properties"),
                cfg("app.properties", "APP_GROUP", null, "timeout=3000\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve("timeout", cache, preferredGroup = "APP_GROUP")

        assertEquals(2, hits.size)
        assertEquals("APP_GROUP", hits[0].config.group)
        assertEquals("3000", hits[0].location.value)
    }

    @Test
    fun `public sorts before non active namespaces`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("a.properties", "g", "sit", "k=sit\n", "properties"),
                cfg("b.properties", "g", null, "k=pub\n", "properties")
            )
        )
        val hits = NacosKeyResolver.resolve("k", cache, activeNamespaceId = "uat")
        // uat not present -> public before sit
        assertEquals(null, hits[0].config.tenantId)
        assertEquals("sit", hits[1].config.tenantId)
    }

    @Test
    fun `missing key returns empty`() = runBlocking {
        cache.cacheConfigurations(
            listOf(cfg("app.properties", "g", null, "a=1\n", "properties"))
        )
        assertTrue(NacosKeyResolver.resolve("nope", cache).isEmpty())
    }

    @Test
    fun `blank key returns empty`() {
        assertTrue(NacosKeyResolver.resolve("   ", cache).isEmpty())
    }

    @Test
    fun `key resolved across yaml and properties`() = runBlocking {
        cache.cacheConfigurations(
            listOf(
                cfg("app.yaml", "g", null, "server:\n  port: 8080\n", "yaml")
            )
        )
       val hits = NacosKeyResolver.resolve("server.port", cache)
       assertEquals(1, hits.size)
       assertEquals("8080", hits[0].location.value)
    }

    @Test
    fun `rebuildBlocking produces an index that hasKey reads immediately`() = runBlocking {
        cache.cacheConfigurations(
            listOf(cfg("app.properties", "g", null, "timeout=3000\n", "properties"))
        )
        val index = NacosKeyResolver.rebuildBlocking(cache, activeServerUrl = null)
        assertEquals(1, index.hitsByKey.size)

        assertTrue(NacosKeyResolver.hasKey("timeout", cache))
        assertFalse(NacosKeyResolver.hasKey("missing", cache))
        assertEquals("3000", NacosKeyResolver.resolve("timeout", cache).single().location.value)
    }

    @Test
    fun `rebuildBlocking again after cache change reflects new keys`() = runBlocking {
        NacosKeyResolver.rebuildBlocking(cache, activeServerUrl = null)
        assertFalse(NacosKeyResolver.hasKey("new.key", cache))

        cache.cacheConfigurations(
            listOf(cfg("app.properties", "g", null, "new.key=v\n", "properties"))
        )
        NacosKeyResolver.rebuildBlocking(cache, activeServerUrl = null)
        assertTrue(NacosKeyResolver.hasKey("new.key", cache))
    }

    @Test
    fun `lazy load flow caches config then rebuild makes key resolvable`() = runBlocking {
        // Simulates what NacosValueLineMarkerProvider.lazyLoadAndNavigate does:
        // before the remote fetch the key is unknown (gray marker),
        assertFalse(NacosKeyResolver.hasKey("db.url", cache))

        // then putConfigDetail persists the freshly fetched configuration,
        cache.putConfigDetail(
            serverUrl = "http://localhost:8848",
            namespaceId = null,
            configuration = cfg("datasource.properties", "DEFAULT_GROUP", null, "db.url=jdbc:test\n", "properties"),
            ttl = 60_000L
        )

        // and a synchronous index rebuild makes the key immediately resolvable
        // so the gutter icon turns solid on the next highlighter pass.
        NacosKeyResolver.rebuildBlocking(cache, activeServerUrl = "http://localhost:8848")
        assertTrue(NacosKeyResolver.hasKey("db.url", cache))
        assertEquals("jdbc:test", NacosKeyResolver.resolve("db.url", cache).single().location.value)
    }
}
