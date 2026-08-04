package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.nanyin.nacos.search.services.writeDetail
import com.nanyin.nacos.search.services.replaceNamespaceIndex
import com.nanyin.nacos.search.services.markNamespaceIndexNonAuthoritative
import com.nanyin.nacos.search.services.clearAll

class NacosKeyResolverTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    private lateinit var cache: CacheService
    private val identity = testIdentity("http://localhost:8848")

    @Before
    fun setUp() {
        runBlocking {
            cache = CacheService(InMemoryCacheStore())
            cache.clearAll()
        }
    }

    private fun cfg(dataId: String, group: String, tenant: String?, content: String, type: String) =
        NacosConfiguration(dataId, group, tenant, content, type)

    private suspend fun seedConfigurations(
        configurations: List<NacosConfiguration>,
        forIdentity: com.nanyin.nacos.search.models.AccessIdentity = identity
    ) {
        configurations.forEach { config ->
            cache.writeDetail(
                identity = forIdentity,
                namespaceId = config.tenantId,
                configuration = config,
                ttl = 60_000L
            )
        }
        NacosKeyResolver.refreshIndex(cache, forIdentity)
    }

    @Test
    fun `empty cache returns no hits without identity`() {
        assertTrue(NacosKeyResolver.resolve("anything", cache).isEmpty())
    }

    @Test
    fun `empty cache returns no hits with identity`() = runBlocking {
        NacosKeyResolver.refreshIndex(cache, identity)
        assertTrue(NacosKeyResolver.resolve("anything", cache, activeIdentity = identity).isEmpty())
    }

    @Test
    fun `missing snapshot is unavailable rather than unresolved`() {
        val result = NacosKeyResolver.resolveStatus("db.url", null)
        assertEquals(ConfigReferenceStatus.UNAVAILABLE, result.status)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `resolved key remains navigable as stale and deep-stale`() = runBlocking {
        var now = 3_000_000L
        val timedCache = CacheService({ now }, InMemoryCacheStore())
        timedCache.clearAll()
        timedCache.writeDetail(
            identity = identity,
            namespaceId = "dev",
            configuration = cfg(
                "app.properties",
                "DEFAULT_GROUP",
                "dev",
                "feature.enabled=true\n",
                "properties"
            ),
            ttl = 100L
        )

        var resolution = NacosKeyResolver.resolveStatus(
            "feature.enabled",
            NacosKeyResolver.refreshIndex(timedCache, identity)
        )
        assertEquals(ConfigReferenceStatus.RESOLVED, resolution.status)
        assertEquals(CacheService.DetailFreshness.FRESH, resolution.hits.single().freshness)

        now += 101L
        resolution = NacosKeyResolver.resolveStatus(
            "feature.enabled",
            NacosKeyResolver.refreshIndex(timedCache, identity)
        )
        assertEquals(ConfigReferenceStatus.STALE, resolution.status)
        assertEquals(CacheService.DetailFreshness.STALE, resolution.hits.single().freshness)

        now = 3_000_000L + 8L * 24 * 60 * 60 * 1000
        resolution = NacosKeyResolver.resolveStatus(
            "feature.enabled",
            NacosKeyResolver.refreshIndex(timedCache, identity)
        )
        assertEquals(ConfigReferenceStatus.STALE, resolution.status)
        assertEquals(CacheService.DetailFreshness.DEEP_STALE, resolution.hits.single().freshness)
    }

    @Test
    fun `current resolution changes when TTL elapses without a cache write`() = runBlocking {
        var now = 4_000_000L
        val timedCache = CacheService({ now }, InMemoryCacheStore())
        timedCache.clearAll()
        timedCache.writeDetail(
            identity,
            "dev",
            cfg("app.properties", "DEFAULT_GROUP", "dev", "timeout=30\n", "properties"),
            ttl = 100L
        )
        NacosKeyResolver.refreshIndex(timedCache, identity)

        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            NacosKeyResolver.resolveCurrentState(
                "timeout",
                timedCache,
                activeIdentity = identity
            ).status
        )

        now += 101L

        val stale = NacosKeyResolver.resolveCurrentState(
            "timeout",
            timedCache,
            activeIdentity = identity
        )
        assertEquals(ConfigReferenceStatus.STALE, stale.status)
        assertEquals(CacheService.DetailFreshness.STALE, stale.hits.single().freshness)
    }

    @Test
    fun `only a fresh complete namespace can prove a dataId absent`() = runBlocking {
        var now = 5_000_000L
        val timedCache = CacheService({ now }, InMemoryCacheStore())
        timedCache.clearAll()
        timedCache.writeDetail(
            identity,
            "dev",
            cfg("other.properties", "DEFAULT_GROUP", "dev", "other=true\n", "properties"),
            ttl = 100L
        )
        NacosKeyResolver.refreshIndex(timedCache, identity)

        assertTrue(
            NacosKeyResolver.isDataIdKnown(
                "target.properties",
                timedCache,
                activeNamespaceId = "dev",
                activeIdentity = identity
            )
        )

        timedCache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("other.properties", "DEFAULT_GROUP", "dev", "other=true\n", "properties")),
            ttl = 100L
        )
        NacosKeyResolver.refreshIndex(timedCache, identity)
        assertFalse(
            NacosKeyResolver.isDataIdKnown(
                "target.properties",
                timedCache,
                activeNamespaceId = "dev",
                activeIdentity = identity
            )
        )

        timedCache.markNamespaceIndexNonAuthoritative(identity, "dev")
        assertTrue(
            NacosKeyResolver.isDataIdKnown(
                "target.properties",
                timedCache,
                activeNamespaceId = "dev",
                activeIdentity = identity
            )
        )

        now += 101L
        assertTrue(
            NacosKeyResolver.isDataIdKnown(
                "target.properties",
                timedCache,
                activeNamespaceId = "dev",
                activeIdentity = identity
            )
        )
    }

    @Test
    fun `single hit in properties`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        val hits = NacosKeyResolver.resolve("timeout", cache, activeIdentity = identity)
        assertEquals(1, hits.size)
        assertEquals("3000", hits[0].location.value)
    }

    @Test
    fun `multiple hits across configs`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("application.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"),
                cfg("common.yaml", "SHARED", "dev", "timeout: 5000\n", "yaml")
            )
        )
        val hits = NacosKeyResolver.resolve("timeout", cache, activeIdentity = identity)
        assertEquals(2, hits.size)
    }

    @Test
    fun `active access identity scopes resolution`() = runBlocking {
        val dev = testIdentity("http://dev-nacos:8848")
        val prod = testIdentity("http://prod-nacos:8848")
        cache.writeDetail(
            identity = dev,
            namespaceId = null,
            configuration = cfg("app.properties", "DEFAULT_GROUP", null, "app.name=dev\n", "properties"),
            ttl = 60_000L
        )
        cache.writeDetail(
            identity = prod,
            namespaceId = null,
            configuration = cfg("app.properties", "DEFAULT_GROUP", null, "app.name=prod\n", "properties"),
            ttl = 60_000L
        )
        NacosKeyResolver.refreshIndex(cache, prod)

        val hits = NacosKeyResolver.resolve(
            "app.name",
            cache,
            activeIdentity = prod
        )

        assertEquals(1, hits.size)
        assertEquals("prod", hits.single().location.value)
    }

    @Test
    fun `without active identity resolve is a genuine miss`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        // Index exists for identity, but a call with no identity must not fall
        // back to a legacy server-URL key space.
        assertTrue(NacosKeyResolver.resolve("timeout", cache, activeIdentity = null).isEmpty())
    }

    @Test
    fun `active namespace sorts first`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("p.properties", "DEFAULT_GROUP", null, "k=pub\n", "properties"),
                cfg("d.properties", "DEFAULT_GROUP", "dev", "k=dev\n", "properties"),
                cfg("o.properties", "DEFAULT_GROUP", "sit", "k=sit\n", "properties")
            )
        )
        val hits = NacosKeyResolver.resolve(
            "k",
            cache,
            activeNamespaceId = "dev",
            activeIdentity = identity
        )
        assertEquals(3, hits.size)
        assertEquals("dev", hits[0].config.tenantId)
        assertEquals("dev", hits[0].location.value)
        assertEquals(null, hits[1].config.tenantId)
        assertEquals("sit", hits[2].config.tenantId)
    }

    @Test
    fun `cross namespace disabled only returns active namespace hits`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                cfg("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "room.key",
            cacheService = cache,
            activeNamespaceId = "namespace1",
            allowCrossNamespace = false,
            activeIdentity = identity
        )

        assertEquals(1, hits.size)
        assertEquals("namespace1", hits.single().config.tenantId)
        assertEquals("one", hits.single().location.value)
    }

    @Test
    fun `cross namespace disabled treats blank and public as same namespace`() = runBlocking {
        seedConfigurations(
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
            allowCrossNamespace = false,
            activeIdentity = identity
        )

        assertEquals(2, hits.size)
        assertTrue(hits.all { it.config.tenantId == null || it.config.tenantId == "public" })
    }

    @Test
    fun `cross namespace enabled returns all namespaces with active namespace first`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("room-one.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                cfg("room-two.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "room.key",
            cacheService = cache,
            activeNamespaceId = "namespace1",
            allowCrossNamespace = true,
            activeIdentity = identity
        )

        assertEquals(2, hits.size)
        assertEquals("namespace1", hits.first().config.tenantId)
        assertEquals("namespace2", hits.last().config.tenantId)
    }

    @Test
    fun `preferred group sorts before other groups for same key`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("shared.properties", "SHARED_GROUP", null, "timeout=1000\n", "properties"),
                cfg("app.properties", "APP_GROUP", null, "timeout=3000\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            "timeout",
            cache,
            preferredGroup = "APP_GROUP",
            activeIdentity = identity
        )

        assertEquals(2, hits.size)
        assertEquals("APP_GROUP", hits[0].config.group)
        assertEquals("3000", hits[0].location.value)
    }

    @Test
    fun `preferred dataId hard-filters when that file defines the key`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("common.properties", "DEFAULT_GROUP", "uxinlive", "common.new.anchor.flow.min.fans.count=50\n", "properties"),
                cfg("klive.common.properties", "DEFAULT_GROUP", "uxinlive", "common.new.anchor.flow.min.fans.count=50\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "common.new.anchor.flow.min.fans.count",
            cacheService = cache,
            preferredDataId = "common.properties",
            activeNamespaceId = "uxinlive",
            allowCrossNamespace = false,
            activeIdentity = identity
        )

        assertEquals(1, hits.size)
        assertEquals("common.properties", hits.single().config.dataId)
    }

    @Test
    fun `preferred dataId soft-falls back when that file lacks the key`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("common.properties", "DEFAULT_GROUP", null, "other.key=1\n", "properties"),
                cfg("klive.common.properties", "DEFAULT_GROUP", null, "timeout=50\n", "properties"),
                cfg("shared.properties", "DEFAULT_GROUP", null, "timeout=100\n", "properties")
            )
        )

        val hits = NacosKeyResolver.resolve(
            key = "timeout",
            cacheService = cache,
            preferredDataId = "common.properties",
            activeIdentity = identity
        )

        assertEquals(2, hits.size)
        assertEquals("klive.common.properties", hits[0].config.dataId)
        assertEquals("shared.properties", hits[1].config.dataId)
    }

    @Test
    fun `public sorts before non active namespaces`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("a.properties", "g", "sit", "k=sit\n", "properties"),
                cfg("b.properties", "g", null, "k=pub\n", "properties")
            )
        )
        val hits = NacosKeyResolver.resolve(
            "k",
            cache,
            activeNamespaceId = "uat",
            activeIdentity = identity
        )
        assertEquals(null, hits[0].config.tenantId)
        assertEquals("sit", hits[1].config.tenantId)
    }

    @Test
    fun `missing key returns empty`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "a=1\n", "properties"))
        )
        assertTrue(NacosKeyResolver.resolve("nope", cache, activeIdentity = identity).isEmpty())
    }

    @Test
    fun `blank key returns empty`() {
        assertTrue(NacosKeyResolver.resolve("   ", cache, activeIdentity = identity).isEmpty())
    }

    @Test
    fun `key resolved across yaml and properties`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("app.yaml", "g", null, "server:\n  port: 8080\n", "yaml")
            )
        )
        val hits = NacosKeyResolver.resolve("server.port", cache, activeIdentity = identity)
        assertEquals(1, hits.size)
        assertEquals("8080", hits[0].location.value)
    }

    @Test
    fun `refreshIndex produces an index that hasKey reads immediately`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "timeout=3000\n", "properties"))
        )
        val index = NacosKeyResolver.refreshIndex(cache, identity)
        assertEquals(1, index.hitsByKey.size)

        assertTrue(NacosKeyResolver.hasKey("timeout", cache, activeIdentity = identity))
        assertFalse(NacosKeyResolver.hasKey("missing", cache, activeIdentity = identity))
        assertEquals(
            "3000",
            NacosKeyResolver.resolve("timeout", cache, activeIdentity = identity).single().location.value
        )
    }

    @Test
    fun `refreshIndex again after cache change reflects new keys`() = runBlocking {
        NacosKeyResolver.refreshIndex(cache, identity)
        assertFalse(NacosKeyResolver.hasKey("new.key", cache, activeIdentity = identity))

        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "new.key=v\n", "properties"))
        )
        NacosKeyResolver.refreshIndex(cache, identity)
        assertTrue(NacosKeyResolver.hasKey("new.key", cache, activeIdentity = identity))
    }

    @Test
    fun `lazy load flow caches config then rebuild makes key resolvable`() = runBlocking {
        assertFalse(NacosKeyResolver.hasKey("db.url", cache, activeIdentity = identity))

        cache.writeDetail(
            identity = identity,
            namespaceId = null,
            configuration = cfg("datasource.properties", "DEFAULT_GROUP", null, "db.url=jdbc:test\n", "properties"),
            ttl = 60_000L
        )

        NacosKeyResolver.refreshIndex(cache, identity)
        assertTrue(NacosKeyResolver.hasKey("db.url", cache, activeIdentity = identity))
        assertEquals(
            "jdbc:test",
            NacosKeyResolver.resolve("db.url", cache, activeIdentity = identity).single().location.value
        )
    }
}
