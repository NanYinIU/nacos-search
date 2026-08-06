package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.visibility.CompletedObservation
import com.nanyin.nacos.search.services.visibility.ObservationOutcome
import com.nanyin.nacos.search.services.visibility.VisibilityOperationClass
import kotlinx.coroutines.Dispatchers
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
    private lateinit var indexService: NacosKeyIndexService
    private val identity = testIdentity("http://localhost:8848")

    @Before
    fun setUp() {
        runBlocking {
            cache = CacheService(InMemoryCacheStore())
            cache.clearAll()
            indexService = NacosKeyIndexService(Dispatchers.Unconfined)
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
        indexService.refreshIndex(cache.snapshot(forIdentity))
    }

    @Test
    fun `empty cache returns no hits`() = runBlocking {
        indexService.refreshIndex(cache.snapshot(identity))
        assertTrue(indexService.resolve(cache.snapshot(identity), "anything").isEmpty())
    }

    @Test
    fun `missing index is unavailable rather than unresolved`() {
        val result = NacosKeyResolver.resolveCurrentState("db.url", null, cache.snapshot(identity))
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
        indexService.refreshIndex(timedCache.snapshot(identity))

        var resolution = indexService.resolveCurrentState(timedCache.snapshot(identity), "feature.enabled")
        assertEquals(ConfigReferenceStatus.RESOLVED, resolution.status)
        assertEquals(CacheService.DetailFreshness.FRESH, resolution.hits.single().freshness)

        now += 101L
        resolution = indexService.resolveCurrentState(timedCache.snapshot(identity), "feature.enabled")
        assertEquals(ConfigReferenceStatus.STALE, resolution.status)
        assertEquals(CacheService.DetailFreshness.STALE, resolution.hits.single().freshness)

        now = 3_000_000L + 8L * 24 * 60 * 60 * 1000
        resolution = indexService.resolveCurrentState(timedCache.snapshot(identity), "feature.enabled")
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
        indexService.refreshIndex(timedCache.snapshot(identity))

        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(timedCache.snapshot(identity), "timeout").status
        )

        now += 101L

        val stale = indexService.resolveCurrentState(timedCache.snapshot(identity), "timeout")
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
        indexService.refreshIndex(timedCache.snapshot(identity))

        assertTrue(
            indexService.isDataIdKnown(
                timedCache.snapshot(identity),
                "target.properties",
                activeNamespaceId = "dev"
            )
        )

        timedCache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("other.properties", "DEFAULT_GROUP", "dev", "other=true\n", "properties")),
            ttl = 100L
        )
        indexService.refreshIndex(timedCache.snapshot(identity))
        assertFalse(
            indexService.isDataIdKnown(
                timedCache.snapshot(identity),
                "target.properties",
                activeNamespaceId = "dev"
            )
        )

        timedCache.markNamespaceIndexNonAuthoritative(identity, "dev")
        assertTrue(
            indexService.isDataIdKnown(
                timedCache.snapshot(identity),
                "target.properties",
                activeNamespaceId = "dev"
            )
        )

        now += 101L
        assertTrue(
            indexService.isDataIdKnown(
                timedCache.snapshot(identity),
                "target.properties",
                activeNamespaceId = "dev"
            )
        )
    }

    @Test
    fun `single hit in properties`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        val hits = indexService.resolve(cache.snapshot(identity), "timeout")
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
        val hits = indexService.resolve(cache.snapshot(identity), "timeout")
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
        indexService.refreshIndex(cache.snapshot(prod))

        val hits = indexService.resolve(cache.snapshot(prod), "app.name")

        assertEquals(1, hits.size)
        assertEquals("prod", hits.single().location.value)
    }

    @Test
    fun `a snapshot for another identity is a genuine miss`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        // Index exists for identity; a snapshot for a different one must not
        // fall back to it.
        val other = testIdentity("http://other-nacos:8848")
        assertTrue(indexService.resolve(cache.snapshot(other), "timeout").isEmpty())
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
        val hits = indexService.resolve(
            cache.snapshot(identity),
            "k",
            activeNamespaceId = "dev"
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

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "room.key",
            activeNamespaceId = "namespace1",
            allowCrossNamespace = false
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

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "room.key",
            activeNamespaceId = "public",
            allowCrossNamespace = false
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

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "room.key",
            activeNamespaceId = "namespace1",
            allowCrossNamespace = true
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

        val hits = indexService.resolve(
            cache.snapshot(identity),
            "timeout",
            preferredGroup = "APP_GROUP"
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

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "common.new.anchor.flow.min.fans.count",
            preferredDataId = "common.properties",
            activeNamespaceId = "uxinlive",
            allowCrossNamespace = false
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

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "timeout",
            preferredDataId = "common.properties"
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
        val hits = indexService.resolve(
            cache.snapshot(identity),
            "k",
            activeNamespaceId = "uat"
        )
        assertEquals(null, hits[0].config.tenantId)
        assertEquals("sit", hits[1].config.tenantId)
    }

    @Test
    fun `missing key returns empty`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "a=1\n", "properties"))
        )
        assertTrue(indexService.resolve(cache.snapshot(identity), "nope").isEmpty())
    }

    @Test
    fun `blank key returns empty`() {
        assertTrue(indexService.resolve(cache.snapshot(identity), "   ").isEmpty())
    }

    @Test
    fun `key resolved across yaml and properties`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("app.yaml", "g", null, "server:\n  port: 8080\n", "yaml")
            )
        )
        val hits = indexService.resolve(cache.snapshot(identity), "server.port")
        assertEquals(1, hits.size)
        assertEquals("8080", hits[0].location.value)
    }

    @Test
    fun `refreshIndex produces an index that resolve reads immediately`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "timeout=3000\n", "properties"))
        )
        val index = indexService.refreshIndex(cache.snapshot(identity))
        assertEquals(1, index.definitionsByKey.size)

        assertTrue(indexService.resolve(cache.snapshot(identity), "missing").isEmpty())
        assertEquals(
            "3000",
            indexService.resolve(cache.snapshot(identity), "timeout").single().location.value
        )
    }

    @Test
    fun `refreshIndex again after cache change reflects new keys`() = runBlocking {
        indexService.refreshIndex(cache.snapshot(identity))
        assertTrue(indexService.resolve(cache.snapshot(identity), "new.key").isEmpty())

        seedConfigurations(
            listOf(cfg("app.properties", "g", null, "new.key=v\n", "properties"))
        )
        indexService.refreshIndex(cache.snapshot(identity))
        assertTrue(indexService.resolve(cache.snapshot(identity), "new.key").isNotEmpty())
    }

    @Test
    fun `lazy load flow caches config then rebuild makes key resolvable`() = runBlocking {
        indexService.refreshIndex(cache.snapshot(identity))
        assertTrue(indexService.resolve(cache.snapshot(identity), "db.url").isEmpty())

        cache.writeDetail(
            identity = identity,
            namespaceId = null,
            configuration = cfg("datasource.properties", "DEFAULT_GROUP", null, "db.url=jdbc:test\n", "properties"),
            ttl = 60_000L
        )

        indexService.refreshIndex(cache.snapshot(identity))
        assertEquals(
            "jdbc:test",
            indexService.resolve(cache.snapshot(identity), "db.url").single().location.value
        )
    }

    // ── Visibility-aware resolution (issue #126) ──

    @Test
    fun `blocked identity yields no hits and reports references undecidable`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        assertTrue(indexService.resolve(cache.snapshot(identity), "timeout").isNotEmpty())

        cache.visibilityReporter().reportCompleted(
            CompletedObservation(
                observation = 10_000,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
            )
        )
        val blocked = cache.snapshot(identity)
        assertTrue(blocked.isAccessBlocked)

        assertTrue(indexService.resolve(blocked, "timeout").isEmpty())
        val resolution = indexService.resolveCurrentState(blocked, "timeout")
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.hits.isEmpty())
        assertTrue(resolution.visibilityBlocked)
        // Absence cannot be proven either: the data id stays "possibly present".
        assertTrue(indexService.isDataIdKnown(blocked, "app.properties"))
    }

    @Test
    fun `namespace block hides only that namespace from resolution`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("a.properties", "DEFAULT_GROUP", "team-a", "a.only=one\n", "properties"),
                cfg("b.properties", "DEFAULT_GROUP", "team-b", "b.only=two\n", "properties"),
                cfg("shared-a.properties", "DEFAULT_GROUP", "team-a", "shared=blocked\n", "properties"),
                cfg("shared-b.properties", "DEFAULT_GROUP", "team-b", "shared=visible\n", "properties")
            )
        )
        cache.visibilityReporter().reportCompleted(
            CompletedObservation(
                observation = 10_000,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                namespaceId = "team-a",
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authorization(403))
            )
        )
        val blocked = cache.snapshot(identity)
        assertFalse(blocked.isAccessBlocked)
        assertEquals(setOf("team-a"), blocked.blockedNamespaces)

        // Only team-a's definitions are hidden.
        assertTrue(indexService.resolve(blocked, "a.only").isEmpty())
        assertEquals(1, indexService.resolve(blocked, "b.only").size)

        // In the blocked namespace the reference is undecidable, never a confident miss.
        val inBlocked = indexService.resolveCurrentState(blocked, "a.only", activeNamespaceId = "team-a")
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, inBlocked.status)
        assertTrue(inBlocked.hits.isEmpty())
        assertTrue(inBlocked.visibilityBlocked)

        // The visible namespace keeps its normal resolution.
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(blocked, "b.only", activeNamespaceId = "team-b").status
        )

        // Cross-namespace resolution serves the visible hit, never the hidden one.
        val shared = indexService.resolve(blocked, "shared", activeNamespaceId = "team-b")
        assertEquals(1, shared.size)
        assertEquals("team-b", shared.single().namespaceId)
    }

    @Test
    fun `an index derived under a different visibility state serves nothing`() = runBlocking {
        seedConfigurations(
            listOf(cfg("app.properties", "DEFAULT_GROUP", null, "timeout=3000\n", "properties"))
        )
        val visibleSnapshot = cache.snapshot(identity)
        val staleIndex = indexService.currentIndex(visibleSnapshot)!!

        cache.visibilityReporter().reportCompleted(
            CompletedObservation(
                observation = 10_000,
                identity = identity,
                operationClass = VisibilityOperationClass.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
            )
        )
        val blockedSnapshot = cache.snapshot(identity)
        assertTrue(blockedSnapshot.isAccessBlocked)

        // The resolver itself refuses the mismatched pair: no hit from the
        // stale index, and the decision is undecidable, not unresolved.
        assertTrue(NacosKeyResolver.resolve("timeout", staleIndex, blockedSnapshot).isEmpty())
        val resolution = NacosKeyResolver.resolveCurrentState("timeout", staleIndex, blockedSnapshot)
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.hits.isEmpty())
    }
}
