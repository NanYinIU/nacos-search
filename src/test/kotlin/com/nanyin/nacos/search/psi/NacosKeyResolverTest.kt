package com.nanyin.nacos.search.psi

import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.testIdentity
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.InMemoryCacheStore
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.visibility.CompletedObservation
import com.nanyin.nacos.search.services.visibility.ObservationOutcome
import com.nanyin.nacos.search.services.operations.ProtocolCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Seeds a detail under a 配置坐标 whose Namespace may differ from the
     * payload tenant (issue #190). When [coordinateNamespaceId] is omitted the
     * coordinate follows the payload tenant, matching the historical helper.
     */
    private data class SeedDetail(
        val configuration: NacosConfiguration,
        val coordinateNamespaceId: String? = configuration.tenantId
    )

    private fun seed(
        dataId: String,
        group: String,
        payloadTenant: String?,
        content: String,
        type: String = "properties",
        coordinateNamespaceId: String? = payloadTenant
    ) = SeedDetail(
        configuration = cfg(dataId, group, payloadTenant, content, type),
        coordinateNamespaceId = coordinateNamespaceId
    )

    private suspend fun seedConfigurations(
        configurations: List<NacosConfiguration>,
        forIdentity: com.nanyin.nacos.search.models.AccessIdentity = identity
    ) {
        seedDetails(configurations.map { SeedDetail(it) }, forIdentity)
    }

    private suspend fun seedDetails(
        details: List<SeedDetail>,
        forIdentity: com.nanyin.nacos.search.models.AccessIdentity = identity
    ) {
        details.forEach { detail ->
            cache.writeDetail(
                identity = forIdentity,
                namespaceId = detail.coordinateNamespaceId,
                configuration = detail.configuration,
                ttl = 60_000L
            )
        }
        indexService.refreshIndex(cache.snapshot(forIdentity))
    }

    @Test
    fun `key index resolves keys from list-seeded detail bodies without a separate fetch`() = runBlocking {
        // Issue #146: COMPLETE V1 index load seeds ordinary details; the gutter
        // key index reads those details from the snapshot — no per-item fetch.
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("app.properties", "DEFAULT_GROUP", "dev", "", "properties")),
            ttl = 60_000L
        )
        cache.writeDetail(
            identity = identity,
            namespaceId = "dev",
            configuration = cfg(
                "app.properties",
                "DEFAULT_GROUP",
                "dev",
                "feature.enabled=true\n",
                "properties"
            ),
            ttl = 60_000L
        )
        indexService.refreshIndex(cache.snapshot(identity))

        val hits = indexService.resolve(cache.snapshot(identity), "feature.enabled")
        assertEquals(1, hits.size)
        assertEquals("app.properties", hits.single().config.dataId)
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
    fun `preferred dataId is a hard filter when its cached body lacks the key`() = runBlocking {
        // Body of the declared source is in the 配置详情缓存 and was read: the key
        // is genuinely absent from it. Other cached configurations still define
        // the same key, but the declaration is a hard constraint — do not soft
        // to a substitute (user: @NacosPropertySource dataId = es.properties).
        seedConfigurations(
            listOf(
                cfg("common.properties", "DEFAULT_GROUP", null, "other.key=1\n", "properties"),
                cfg("klive.common.properties", "DEFAULT_GROUP", null, "timeout=50\n", "properties"),
                cfg("shared.properties", "DEFAULT_GROUP", null, "timeout=100\n", "properties")
            )
        )

        val resolution = indexService.resolveCurrentState(
            snapshot = cache.snapshot(identity),
            key = "timeout",
            preferredDataId = "common.properties"
        )

        assertEquals(ConfigReferenceStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.hits.isEmpty())
    }

    @Test
    fun `preferred dataId present in namespace index without detail is undecidable even when others define the key`() =
        runBlocking {
            // Reproduction for issue #192: the declared Data ID is listed by a
            // fresh authoritative Namespace 索引 but its body was never fetched,
            // while an unrelated cached configuration defines the same key.
            // Soft-falling to the unrelated file painted 已解析配置引用 and skipped
            // the lazy-load that would have fetched the declared body.
            cache.replaceNamespaceIndex(
                identity,
                "dev",
                listOf(
                    cfg("declared.properties", "DEFAULT_GROUP", "dev", "", "properties"),
                    cfg("other.properties", "DEFAULT_GROUP", "dev", "", "properties")
                ),
                ttl = 60_000L
            )
            cache.writeDetail(
                identity = identity,
                namespaceId = "dev",
                configuration = cfg(
                    "other.properties",
                    "DEFAULT_GROUP",
                    "dev",
                    "feature.enabled=true\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            indexService.refreshIndex(cache.snapshot(identity))

            val resolution = indexService.resolveCurrentState(
                snapshot = cache.snapshot(identity),
                key = "feature.enabled",
                preferredDataId = "declared.properties",
                activeNamespaceId = "dev"
            )

            assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
            assertTrue(resolution.hits.isEmpty())
            assertFalse(resolution.visibilityBlocked)
        }

    @Test
    fun `detail cached in another namespace does not soft-fall for a listed unfetched declared source`() =
        runBlocking {
            // Cross-Namespace twist of #192: the same dataId name has a detail
            // in prod (key absent there) while dev's Namespace 索引 lists it
            // without a body. Soft-fallback must not treat prod's body as proof
            // that dev's declared source was read.
            cache.writeDetail(
                identity = identity,
                namespaceId = "prod",
                configuration = cfg(
                    "declared.properties",
                    "DEFAULT_GROUP",
                    "prod",
                    "other.key=1\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            cache.replaceNamespaceIndex(
                identity,
                "dev",
                listOf(
                    cfg("declared.properties", "DEFAULT_GROUP", "dev", "", "properties"),
                    cfg("other.properties", "DEFAULT_GROUP", "dev", "", "properties")
                ),
                ttl = 60_000L
            )
            cache.writeDetail(
                identity = identity,
                namespaceId = "dev",
                configuration = cfg(
                    "other.properties",
                    "DEFAULT_GROUP",
                    "dev",
                    "feature.enabled=true\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            indexService.refreshIndex(cache.snapshot(identity))

            val resolution = indexService.resolveCurrentState(
                snapshot = cache.snapshot(identity),
                key = "feature.enabled",
                preferredDataId = "declared.properties",
                activeNamespaceId = "dev"
            )

            assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
            assertTrue(resolution.hits.isEmpty())
        }

    @Test
    fun `preferred dataId proven absent does not soft-fall to another dataId`() = runBlocking {
        // A fresh complete Namespace 索引 that does not list the declared Data ID
        // proves the source itself is gone. Still a hard miss — do not paint
        // another configuration as 已解析 for a site that named a different dataId.
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("other.properties", "DEFAULT_GROUP", "dev", "", "properties")),
            ttl = 60_000L
        )
        cache.writeDetail(
            identity = identity,
            namespaceId = "dev",
            configuration = cfg(
                "other.properties",
                "DEFAULT_GROUP",
                "dev",
                "timeout=50\n",
                "properties"
            ),
            ttl = 60_000L
        )
        indexService.refreshIndex(cache.snapshot(identity))

        val resolution = indexService.resolveCurrentState(
            snapshot = cache.snapshot(identity),
            key = "timeout",
            preferredDataId = "missing.properties",
            activeNamespaceId = "dev"
        )

        assertEquals(ConfigReferenceStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.hits.isEmpty())
    }

    @Test
    fun `preferred dataId whose absence is unproven does not substitute another dataId`() = runBlocking {
        // No Namespace 索引 and the declared body is not in the 配置详情缓存:
        // zero key-index hits must not soft-fall to every other definition
        // (issue #192).
        seedConfigurations(
            listOf(
                cfg("other.properties", "DEFAULT_GROUP", "dev", "feature.enabled=true\n", "properties")
            )
        )

        val resolution = indexService.resolveCurrentState(
            snapshot = cache.snapshot(identity),
            key = "feature.enabled",
            preferredDataId = "declared.properties",
            activeNamespaceId = "dev"
        )

        assertTrue(resolution.hits.isEmpty())
        assertEquals(ConfigReferenceStatus.UNAVAILABLE, resolution.status)
    }

    @Test
    fun `stale namespace index does not unlock soft discovery for an unfetched declared dataId`() = runBlocking {
        var now = 6_000_000L
        val timedCache = CacheService({ now }, InMemoryCacheStore())
        timedCache.clearAll()
        timedCache.replaceNamespaceIndex(
            identity,
            "dev",
            // Index that does not list the declared id — would be ABSENT if fresh.
            listOf(cfg("other.properties", "DEFAULT_GROUP", "dev", "", "properties")),
            ttl = 100L
        )
        timedCache.writeDetail(
            identity = identity,
            namespaceId = "dev",
            configuration = cfg(
                "other.properties",
                "DEFAULT_GROUP",
                "dev",
                "feature.enabled=true\n",
                "properties"
            ),
            ttl = 60_000L
        )
        indexService.refreshIndex(timedCache.snapshot(identity))
        now += 101L

        val resolution = indexService.resolveCurrentState(
            snapshot = timedCache.snapshot(identity),
            key = "feature.enabled",
            preferredDataId = "declared.properties",
            activeNamespaceId = "dev"
        )

        assertTrue(resolution.hits.isEmpty())
        assertEquals(ConfigReferenceStatus.UNAVAILABLE, resolution.status)
    }

    @Test
    fun `non-authoritative namespace index does not unlock soft discovery for an unfetched declared dataId`() =
        runBlocking {
            cache.replaceNamespaceIndex(
                identity,
                "dev",
                listOf(cfg("other.properties", "DEFAULT_GROUP", "dev", "", "properties")),
                ttl = 60_000L
            )
            cache.writeDetail(
                identity = identity,
                namespaceId = "dev",
                configuration = cfg(
                    "other.properties",
                    "DEFAULT_GROUP",
                    "dev",
                    "feature.enabled=true\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            cache.markNamespaceIndexNonAuthoritative(identity, "dev")
            indexService.refreshIndex(cache.snapshot(identity))

            val resolution = indexService.resolveCurrentState(
                snapshot = cache.snapshot(identity),
                key = "feature.enabled",
                preferredDataId = "declared.properties",
                activeNamespaceId = "dev"
            )

            assertTrue(resolution.hits.isEmpty())
            assertEquals(ConfigReferenceStatus.UNAVAILABLE, resolution.status)
        }

    @Test
    fun `body-cached declared dataId with no key hit is unresolved not undecidable`() = runBlocking {
        // Declared body is in this Namespace's 配置详情缓存 and lacks the key.
        // Re-asking Namespace presence would claim "body not loaded"
        // (UNDECIDABLE) even though the body was read.
        seedConfigurations(
            listOf(
                cfg("declared.properties", "DEFAULT_GROUP", "dev", "other.key=1\n", "properties")
            )
        )
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("declared.properties", "DEFAULT_GROUP", "dev", "", "properties")),
            ttl = 60_000L
        )
        indexService.refreshIndex(cache.snapshot(identity))

        val resolution = indexService.resolveCurrentState(
            snapshot = cache.snapshot(identity),
            key = "feature.enabled",
            preferredDataId = "declared.properties",
            activeNamespaceId = "dev"
        )

        assertEquals(ConfigReferenceStatus.UNRESOLVED, resolution.status)
        assertTrue(resolution.hits.isEmpty())
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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
                operationClass = ProtocolCapability.CONFIGURATION_READ,
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

    // ---- a reference site's own 运行时格式 (#173) ----

    /** A body two formats read as two different key spaces. */
    private val nestedBody = "server:\n  port: 8080\n"

    @Test
    fun `a declaration resolves the configuration it names under the declared format`() = runBlocking {
        seedConfigurations(listOf(cfg("app.properties", "DEFAULT_GROUP", null, nestedBody, "properties")))
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")

        // Under the suffix the index holds `server` and `port`; under this
        // site's declaration the same body is one nested key.
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(
                snapshot,
                "server.port",
                preferredDataId = site.dataId,
                formatOverride = site.runtimeFormatOverride()
            ).status
        )
        assertEquals(
            "8080",
            indexService.resolve(
                snapshot,
                "server.port",
                preferredDataId = site.dataId,
                formatOverride = site.runtimeFormatOverride()
            ).single().location.value
        )
    }

    @Test
    fun `a declaration replaces what the suffix read, it does not add to it`() = runBlocking {
        seedConfigurations(listOf(cfg("app.properties", "DEFAULT_GROUP", null, nestedBody, "properties")))
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")

        // `port` is what reading this body as properties yields. The runtime
        // this site declares will never define it, so the gutter must not.
        assertTrue(
            indexService.resolve(
                snapshot,
                "port",
                preferredDataId = site.dataId,
                formatOverride = site.runtimeFormatOverride()
            ).isEmpty()
        )
    }

    @Test
    fun `two sites declaring different types for one data id resolve by their own declaration`() = runBlocking {
        seedConfigurations(listOf(cfg("app.properties", "DEFAULT_GROUP", null, nestedBody, "properties")))
        val snapshot = cache.snapshot(identity)

        val declaresYaml = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")
        val declaresNothing = NacosCodeContext(dataId = "app.properties")

        assertEquals(
            1,
            indexService.resolve(
                snapshot,
                "server.port",
                preferredDataId = declaresYaml.dataId,
                formatOverride = declaresYaml.runtimeFormatOverride()
            ).size
        )
        assertTrue(
            indexService.resolve(
                snapshot,
                "server.port",
                preferredDataId = declaresNothing.dataId,
                formatOverride = declaresNothing.runtimeFormatOverride()
            ).isEmpty()
        )
        assertEquals(
            1,
            indexService.resolve(
                snapshot,
                "port",
                preferredDataId = declaresNothing.dataId,
                formatOverride = declaresNothing.runtimeFormatOverride()
            ).size
        )
    }

    @Test
    fun `a declaration makes an otherwise undetermined data id resolvable`() = runBlocking {
        seedConfigurations(listOf(cfg("service-config", "DEFAULT_GROUP", null, """{"k":"v"}""", "json")))
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(dataId = "service-config", declaredType = "JSON")

        // Without the declaration nothing about the data id names a format, so
        // the index holds no definition at all for it.
        assertTrue(indexService.resolve(snapshot, "k", preferredDataId = "service-config").isEmpty())
        assertEquals(
            ConfigReferenceStatus.RESOLVED,
            indexService.resolveCurrentState(
                snapshot,
                "k",
                preferredDataId = site.dataId,
                formatOverride = site.runtimeFormatOverride()
            ).status
        )
    }

    @Test
    fun `a reference-site declaration leaves the derived key index unchanged`() = runBlocking {
        seedConfigurations(listOf(cfg("app.properties", "DEFAULT_GROUP", null, nestedBody, "properties")))
        val snapshot = cache.snapshot(identity)
        val before = indexService.currentIndex(snapshot)!!

        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")
        indexService.resolve(
            snapshot,
            "server.port",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )

        // The index is built once per 缓存快照 over every cached configuration
        // and has no code context; the override is resolved per reference and
        // never enters it.
        val after = indexService.currentIndex(snapshot)!!
        assertEquals(before.version, after.version)
        assertEquals(setOf("server", "port"), after.definitionsByKey.keys)
    }

    @Test
    fun `a declaration hard-filters out other data ids even when they hold the key`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("app.properties", "DEFAULT_GROUP", null, "unrelated=1\n", "properties"),
                cfg("other.properties", "DEFAULT_GROUP", null, nestedBody, "properties")
            )
        )
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")

        // The declared data id defines no `port` (under the site's YAML reading
        // either). Hard filter must not soft-fall to other.properties.
        val hits = indexService.resolve(
            snapshot,
            "port",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a declaration leaves another Namespace's copy of the same data id alone`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("app.properties", "DEFAULT_GROUP", "dev", nestedBody, "properties"),
                cfg("app.properties", "DEFAULT_GROUP", "prod", nestedBody, "properties")
            )
        )
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(
            dataId = "app.properties",
            namespaceId = "dev",
            declaredType = "YAML"
        )

        // Only dev's copy is the one this site named, so only dev's reading
        // changes. Prod's stays what the data id decided — the site said
        // nothing about it, and dropping its keys would be this declaration
        // reaching a configuration it never claimed.
        val nested = indexService.resolve(
            snapshot,
            "server.port",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )
        assertEquals(1, nested.size)
        assertEquals("dev", nested.single().namespaceId)

        val flat = indexService.resolve(
            snapshot,
            "port",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )
        assertEquals(1, flat.size)
        assertEquals("prod", flat.single().namespaceId)
    }

    @Test
    fun `a declaration keeps an undetermined data id out of the terminal state`() = runBlocking {
        // Where #172 and #173 meet. Without a declaration this data id names no
        // format, so it reaches 格式不参与解析 — terminal, and correctly so. The
        // declaration is the developer's documented way out of that state, so
        // it has to be consulted before the conclusion is drawn.
        seedConfigurations(listOf(cfg("service-config", "DEFAULT_GROUP", null, """{"k":"v"}""", "json")))
        val snapshot = cache.snapshot(identity)

        val undeclared = indexService.resolveCurrentState(snapshot, "k", preferredDataId = "service-config")
        assertEquals(ConfigReferenceStatus.FORMAT_NOT_PARSED, undeclared.status)
        assertEquals(KeyExtraction.Reason.FORMAT_UNDETERMINED, undeclared.formatRefusal)

        val site = NacosCodeContext(dataId = "service-config", declaredType = "JSON")
        val declared = indexService.resolveCurrentState(
            snapshot,
            "k",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )
        assertEquals(ConfigReferenceStatus.RESOLVED, declared.status)
        assertNull(declared.formatRefusal)
    }

    @Test
    fun `a declared format the plugin cannot parse still reaches the terminal state`() = runBlocking {
        // The declaration decides the 运行时格式; it does not promise the plugin
        // reads keys from it. A site declaring XML for a data id that would
        // otherwise be undetermined is still terminal — and for XML's own
        // reason, so the tooltip explains the right thing.
        seedConfigurations(listOf(cfg("service-config", "DEFAULT_GROUP", null, "<beans/>", "xml")))
        val site = NacosCodeContext(dataId = "service-config", declaredType = "XML")

        val resolution = indexService.resolveCurrentState(
            cache.snapshot(identity),
            "k",
            preferredDataId = site.dataId,
            formatOverride = site.runtimeFormatOverride()
        )
        assertEquals(ConfigReferenceStatus.FORMAT_NOT_PARSED, resolution.status)
        assertEquals(KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED, resolution.formatRefusal)
        assertFalse(
            "the sweep leak #172 closed must stay closed whatever named the format",
            resolution.status.mayRequestBackgroundRefresh()
        )
    }

    @Test
    fun `a declaration naming an uncached configuration resolves nothing of its own`() = runBlocking {
        seedConfigurations(listOf(cfg("other.properties", "DEFAULT_GROUP", null, nestedBody, "properties")))
        val snapshot = cache.snapshot(identity)
        val site = NacosCodeContext(dataId = "service-config", declaredType = "JSON")

        assertTrue(
            indexService.resolve(
                snapshot,
                "k",
                preferredDataId = site.dataId,
                formatOverride = site.runtimeFormatOverride()
            ).isEmpty()
        )
    }

    // ── 格式不参与解析 (issue #172) ──

    /**
     * The body is cached and complete. "Content not loaded yet" was a lie about
     * every one of these, and the lie is what kept a sweep pending forever.
     */
    @Test
    fun `a cached configuration no parse produces keys from reaches a terminal state`() = runBlocking {
        val configurations = listOf(
            cfg("beans.xml", "DEFAULT_GROUP", "dev", "<beans><a>1</a></beans>", "xml"),
            cfg("notes.txt", "DEFAULT_GROUP", "dev", "a=1\n", "text"),
            cfg("page.html", "DEFAULT_GROUP", "dev", "<p>a=1</p>", "html"),
            cfg("app.toml", "DEFAULT_GROUP", "dev", "a = 1\n", "toml")
        )
        // A complete Namespace index listing every one of them, plus their
        // bodies: the exact state that used to answer "正文尚未加载" for a body
        // that was right there.
        cache.replaceNamespaceIndex(identity, "dev", configurations, ttl = 60_000L)
        seedConfigurations(configurations)

        configurations.map { it.dataId }.forEach { dataId ->
            val resolution = indexService.resolveCurrentState(
                cache.snapshot(identity),
                "a",
                preferredDataId = dataId,
                activeNamespaceId = "dev"
            )
            assertEquals(dataId, ConfigReferenceStatus.FORMAT_NOT_PARSED, resolution.status)
            assertTrue(dataId, resolution.hits.isEmpty())
            assertFalse(dataId, resolution.status.mayRequestBackgroundRefresh())
        }
    }

    @Test
    fun `a known non-parsing suffix and an undetermined one keep their reasons apart`() = runBlocking {
        seedConfigurations(
            listOf(
                cfg("notes.txt", "DEFAULT_GROUP", "dev", "a=1\n", "text"),
                cfg("service-config", "DEFAULT_GROUP", "dev", "a=1\n", "properties")
            )
        )
        val snapshot = cache.snapshot(identity)

        assertEquals(
            KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT,
            indexService.resolveCurrentState(snapshot, "a", preferredDataId = "notes.txt", activeNamespaceId = "dev")
                .formatRefusal
        )
        // Declared properties in the console, but the data id names no format,
        // so no runtime rule applies — a different situation with a different
        // next action, and the only one of the two the user can act on.
        val undetermined = indexService.resolveCurrentState(
            snapshot,
            "a",
            preferredDataId = "service-config",
            activeNamespaceId = "dev"
        )
        assertEquals(ConfigReferenceStatus.FORMAT_NOT_PARSED, undetermined.status)
        assertEquals(KeyExtraction.Reason.FORMAT_UNDETERMINED, undetermined.formatRefusal)
    }

    @Test
    fun `the terminal format state never permits a background namespace index refresh`() {
        // The behavioural point of the state, and the only one with no visual
        // signal of its own.
        assertFalse(ConfigReferenceStatus.FORMAT_NOT_PARSED.mayRequestBackgroundRefresh())
    }

    @Test
    fun `a placeholder with no data id context keeps its current behaviour`() = runBlocking {
        seedConfigurations(
            listOf(cfg("beans.xml", "DEFAULT_GROUP", "dev", "<beans><a>1</a></beans>", "xml"))
        )
        // Attribution needs a data id: without one the miss cannot be blamed on
        // this configuration, or on any other.
        assertEquals(
            ConfigReferenceStatus.UNRESOLVED,
            indexService.resolveCurrentState(cache.snapshot(identity), "a", activeNamespaceId = "dev").status
        )
    }

    @Test
    fun `a cold start reaches the terminal state instead of spending a sweep on it`() {
        // No index has been built for this identity yet. The conclusion needs
        // none — and the state it used to fall through to (UNAVAILABLE) asks
        // for the sweep that cannot help.
        val resolution = NacosKeyResolver.resolveCurrentState(
            key = "a",
            index = null,
            snapshot = cache.snapshot(identity),
            preferredDataId = "beans.xml"
        )
        assertEquals(ConfigReferenceStatus.FORMAT_NOT_PARSED, resolution.status)
        assertEquals(KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED, resolution.formatRefusal)
        assertFalse(resolution.status.mayRequestBackgroundRefresh())
    }

    @Test
    fun `a cold start for a parseable data id still asks for the sweep that would load it`() {
        val resolution = NacosKeyResolver.resolveCurrentState(
            key = "a",
            index = null,
            snapshot = cache.snapshot(identity),
            preferredDataId = "app.properties"
        )
        assertEquals(ConfigReferenceStatus.UNAVAILABLE, resolution.status)
        assertTrue(resolution.status.mayRequestBackgroundRefresh())
    }

    @Test
    fun `declared format-not-parsed dataId does not soft-fall to another configuration`() = runBlocking {
        // The site declared beans.xml (格式不参与解析). Another cached file holds
        // the key, but the Data ID is a hard constraint — terminal format
        // conclusion for the declared source, no substitute navigation.
        seedConfigurations(
            listOf(
                cfg("beans.xml", "DEFAULT_GROUP", "dev", "<beans><x>1</x></beans>", "xml"),
                cfg("app.properties", "DEFAULT_GROUP", "dev", "a=1\n", "properties")
            )
        )
        val resolution = indexService.resolveCurrentState(
            cache.snapshot(identity),
            "a",
            preferredDataId = "beans.xml",
            activeNamespaceId = "dev"
        )
        assertEquals(ConfigReferenceStatus.FORMAT_NOT_PARSED, resolution.status)
        assertTrue(resolution.hits.isEmpty())
    }

    @Test
    fun `a visibility block outranks the terminal format conclusion`() = runBlocking {
        seedConfigurations(
            listOf(cfg("beans.xml", "DEFAULT_GROUP", null, "<beans><a>1</a></beans>", "xml"))
        )
        cache.visibilityReporter().reportCompleted(
            CompletedObservation(
                observation = 10_000,
                identity = identity,
                operationClass = ProtocolCapability.CONFIGURATION_READ,
                outcome = ObservationOutcome.RemoteFailure(RemoteOperationError.Authentication(401))
            )
        )
        val blocked = cache.snapshot(identity)
        assertTrue(blocked.isAccessBlocked)

        // The plugin never converts "cannot see" into a conclusion, not even a
        // conclusion it could have reached without looking (issue #126).
        val resolution = indexService.resolveCurrentState(blocked, "a", preferredDataId = "beans.xml")
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.visibilityBlocked)
    }

    @Test
    fun `a data id whose format still extracts keeps reaching the presence question`() = runBlocking {
        // The format question must not swallow the states behind it: a listed
        // yaml data id with no detail is still "body not loaded yet", and still
        // asks for the sweep that will load it.
        cache.replaceNamespaceIndex(
            identity,
            "dev",
            listOf(cfg("app.yaml", "DEFAULT_GROUP", "dev", "", "yaml")),
            ttl = 60_000L
        )
        indexService.refreshIndex(cache.snapshot(identity))

        val resolution = indexService.resolveCurrentState(
            cache.snapshot(identity),
            "server.port",
            preferredDataId = "app.yaml",
            activeNamespaceId = "dev"
        )
        assertEquals(ConfigReferenceStatus.UNDECIDABLE, resolution.status)
        assertTrue(resolution.status.mayRequestBackgroundRefresh())
    }

    // ── Issue #190: coordinate Namespace is authority, not payload tenant ──

    @Test
    fun `cross namespace off filters a non-public coordinate even when payload tenant is absent`() = runBlocking {
        // Written under coordinate "team-a" but the response body carries no tenant.
        // When cross-Namespace navigation is off and the session is on public, the
        // definition must not resolve — payload tenant is display-only (issue #190).
        seedDetails(
            listOf(
                seed(
                    dataId = "app.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "feature.enabled=true\n",
                    coordinateNamespaceId = "team-a"
                )
            )
        )

        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "feature.enabled",
            activeNamespaceId = "public",
            allowCrossNamespace = false
        )
        assertTrue(hits.isEmpty())

        // The same definition is still a hit when the active Namespace matches
        // the coordinate, even though the payload tenant is null.
        val activeHits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "feature.enabled",
            activeNamespaceId = "team-a",
            allowCrossNamespace = false
        )
        assertEquals(1, activeHits.size)
        assertEquals("team-a", activeHits.single().namespaceId)
        assertNull(activeHits.single().config.tenantId)
    }

    @Test
    fun `two coordinates with the same Data ID and tenant-less payloads both enter the key index`() = runBlocking {
        // Two Namespaces hold the same Data ID / Group with bodies that omit tenant.
        // Snapshot de-duplication and the derived index must keep both (issue #190).
        seedDetails(
            listOf(
                seed(
                    dataId = "app.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "shared=from-a\n",
                    coordinateNamespaceId = "ns-a"
                ),
                seed(
                    dataId = "app.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "shared=from-b\n",
                    coordinateNamespaceId = "ns-b"
                )
            )
        )

        val snapshot = cache.snapshot(identity)
        assertEquals(2, snapshot.configurations.size)
        assertEquals(
            setOf("ns-a", "ns-b"),
            snapshot.configurations.map { it.namespaceId }.toSet()
        )

        val hits = indexService.resolve(
            snapshot = snapshot,
            key = "shared",
            allowCrossNamespace = true
        )
        assertEquals(2, hits.size)
        assertEquals(setOf("ns-a", "ns-b"), hits.map { it.namespaceId }.toSet())
        assertEquals(
            setOf("from-a", "from-b"),
            hits.map { it.location.value }.toSet()
        )

        // Each coordinate remains independently addressable when cross-Namespace
        // filtering is off.
        val onlyA = indexService.resolve(
            snapshot = snapshot,
            key = "shared",
            activeNamespaceId = "ns-a",
            allowCrossNamespace = false
        )
        assertEquals(1, onlyA.size)
        assertEquals("from-a", onlyA.single().location.value)
        assertEquals("ns-a", onlyA.single().namespaceId)
    }

    @Test
    fun `dataIdsByNamespace keys off the coordinate Namespace not the payload tenant`() = runBlocking {
        seedDetails(
            listOf(
                seed(
                    dataId = "only-on-coord.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "k=v\n",
                    coordinateNamespaceId = "team-x"
                )
            )
        )
        val index = indexService.currentIndex(cache.snapshot(identity))!!
        // normalizeNamespaceId leaves non-public ids as-is; public collapses to "".
        assertTrue("only-on-coord.properties" in index.dataIdsByNamespace["team-x"].orEmpty())
        assertFalse("only-on-coord.properties" in index.dataIdsByNamespace[""].orEmpty())
    }

    @Test
    fun `PSI element and chooser labels use coordinate Namespace when payload tenant is absent`() = runBlocking {
        // End-to-end navigability seam: hit.namespaceId → NacosConfigKeyElement
        // → chooser secondary text, without falling back to public (issue #190).
        seedDetails(
            listOf(
                seed(
                    dataId = "app.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "shared=from-a\n",
                    coordinateNamespaceId = "ns-a"
                ),
                seed(
                    dataId = "app.properties",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "shared=from-b\n",
                    coordinateNamespaceId = "ns-b"
                )
            )
        )
        val hits = indexService.resolve(
            snapshot = cache.snapshot(identity),
            key = "shared",
            allowCrossNamespace = true
        )
        assertEquals(2, hits.size)

        val project = com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
        val elements = hits.map { hit ->
            NacosConfigKeyElement(
                project = project,
                config = hit.config,
                key = "shared",
                value = hit.location.value,
                lineIndex = hit.location.lineIndex,
                coordinateNamespaceId = hit.namespaceId
            )
        }
        assertEquals(setOf("ns-a", "ns-b"), elements.map { it.namespaceId }.toSet())
        // Payload tenant is still null — display/routing must not invent public.
        assertTrue(elements.all { it.config.tenantId == null })

        val chooserLabels = elements.map { el ->
            NacosConfigChoiceItem(el, namespaceDisplayName = el.namespaceId).secondaryText
        }
        assertTrue(chooserLabels.any { it.startsWith("ns-a /") })
        assertTrue(chooserLabels.any { it.startsWith("ns-b /") })
        assertFalse(chooserLabels.any { it.startsWith("public /") })
    }

    @Test
    fun `format override re-derives a tenant-less body under its coordinate Namespace`() = runBlocking {
        // Site declares YAML for a data id with no suffix; body is under coordinate
        // "dev" with a null payload tenant. Matching must use the coordinate or the
        // override silently misses and falls back to the undetermined suffix.
        seedDetails(
            listOf(
                seed(
                    dataId = "service-config",
                    group = "DEFAULT_GROUP",
                    payloadTenant = null,
                    content = "k: v\n",
                    type = "yaml",
                    coordinateNamespaceId = "dev"
                )
            )
        )
        val override = ReferenceFormatOverride(
            dataId = "service-config",
            group = "DEFAULT_GROUP",
            namespaceId = "dev",
            format = RuntimeConfigFormat.YAML
        )
        val hits = NacosKeyResolver.resolve(
            key = "k",
            index = indexService.currentIndex(cache.snapshot(identity)),
            snapshot = cache.snapshot(identity),
            preferredDataId = "service-config",
            preferredNamespaceId = "dev",
            formatOverride = override
        )
        assertEquals(1, hits.size)
        assertEquals("v", hits.single().location.value)
        assertEquals("dev", hits.single().namespaceId)
    }
}
