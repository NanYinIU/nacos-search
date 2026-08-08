package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.captureAccessIdentity
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.nanyin.nacos.search.services.writeDetail
import com.nanyin.nacos.search.services.replaceNamespaceIndex
import com.nanyin.nacos.search.services.clearAll
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.reportVisibility

class NacosValueLineMarkerProviderTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    @Before
    fun setUp() {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        val project = ProjectManager.getInstance().defaultProject
        project.getService(NacosProjectSession::class.java).sessionState.apply {
            selectedProfileId = ""
            namespaceId = "public"
            selectionWasExplicit = false
            upgradeSummaryShown = false
            upgradeSummaryShownForSchemaVersion = 0
        }
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            cache.clearAll()
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }
    }

    /**
     * The production path: the application-level index service rebuilds from a
     * snapshot the cache hands out. Nothing here reaches into cache internals.
     */
    private fun refreshKeyIndex(cache: CacheService, identity: AccessIdentity) {
        ApplicationManager.getApplication()
            .getService(NacosKeyIndexService::class.java)
            .refreshIndex(cache.snapshot(identity))
    }

    private fun selectProjectNamespace(namespaceId: String) {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        ProjectManager.getInstance().defaultProject
            .getService(NacosProjectSession::class.java)
            .select(settings.activeServerId, namespaceId)
    }

    private fun cacheAndRefresh(configuration: NacosConfiguration) = runBlocking {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        cache.writeDetail(settings.captureAccessIdentity(), null, configuration)
        refreshKeyIndex(cache, settings.captureAccessIdentity())
    }

    @Test
    fun `test marker is provided for supported annotation literal`() {
        cacheAndRefresh(NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties"))
        val marker = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `test stale resolved key uses stale gutter icon`() = runBlocking {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        cache.writeDetail(
            settings.captureAccessIdentity(),
            null,
            NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties"),
            ttl = -1L
        )
        refreshKeyIndex(cache, settings.captureAccessIdentity())

        val marker = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfigStale, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `stale gutter observation does not request background refresh`() = runBlocking {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        cache.writeDetail(
            settings.captureAccessIdentity(),
            null,
            NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties"),
            ttl = -1L
        )
        refreshKeyIndex(cache, settings.captureAccessIdentity())

        var observed = false
        val provider = NacosValueLineMarkerProvider { _, _ -> observed = true }
        val marker = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent(),
            provider
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfigStale, marker?.createGutterRenderer()?.icon)
        assertFalse("STALE markers must render without network (issue #145)", observed)
    }

    @Test
    fun `marker resolves against project-selected namespace`() {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        setAllowCrossNamespaceNavigation(false)
        selectProjectNamespace("qa-ns")
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "qa-ns",
                configuration = NacosConfiguration(
                    "room.properties",
                    "DEFAULT_GROUP",
                    "qa-ns",
                    "room.room.fluency.type=5\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }

        val marker = markerFor(
            """
            class RoomConfig {
                @NacosValue(value = "${'$'}{room.room.fluency.type:3}")
                private Integer fluencyType;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `marker resolves against project-selected profile cache identity`() {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val qa = NacosServerConfig(
            id = "qa",
            displayName = "QA",
            serverUrl = "http://qa.example:8848"
        )
        val local = settings.getActiveServer()
        settings.applyServers(listOf(local, qa), local.id)

        val project = ProjectManager.getInstance().defaultProject
        project.getService(NacosProjectSession::class.java).select("qa", "public")

        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val qaIdentity = settings.captureAccessIdentity("qa")
            cache.writeDetail(
                identity = qaIdentity,
                namespaceId = null,
                configuration = NacosConfiguration(
                    "room.properties",
                    "DEFAULT_GROUP",
                    null,
                    "room.room.fluency.type=5\n",
                    "properties"
                ),
                ttl = 60_000L
            )
            // App-active profile cache stays empty — gutter must not look there.
            refreshKeyIndex(cache, qaIdentity)
        }

        val marker = markerFor(
            """
            class RoomConfig {
                @NacosValue(value = "${'$'}{room.room.fluency.type:3}")
                private Integer fluencyType;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `test no marker is shown when key is not cached and no dataId context exists`() {
        val marker = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{missing.name}")
                private String name;
            }
            """.trimIndent()
        )

       // Key not in cache + no @NacosPropertySource dataId → no marker
       org.junit.Assert.assertNull(marker)
   }

   @Test
   fun `test no marker when dataId context exists but dataId is absent from loaded namespace`() {
       // Only a fresh COMPLETE Namespace snapshot can prove absence.
       runBlocking {
           val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
           val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
           cache.replaceNamespaceIndex(
               settings.captureAccessIdentity(),
               null,
               listOf(NacosConfiguration("other.properties", "DEFAULT_GROUP", null, "other.key=val\n", "properties"))
           )
           refreshKeyIndex(cache, settings.captureAccessIdentity())
       }

       val marker = markerFor(
           """
           @NacosPropertySource(dataId = "klive.room.background.config.properties")
           class Demo {
               @NacosValue(value = "${'$'}{some.key}")
               private String name;
           }
           """.trimIndent()
       )

       assertNull(marker)
   }

   @Test
   fun `test marker is shown unresolved when dataId context exists but key is not cached`() {
        val marker = markerFor(
            """
            @NacosPropertySource(dataId = "common.properties")
            class Demo {
                @NacosValue(value = "${'$'}{missing.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfigUnresolved, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `test marker is provided for short nacos annotation name when cached definition exists`() {
        cacheAndRefresh(NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "missing.name=demo", "properties"))
        val marker = markerFor(
            """
            class Demo {
                @NacosValue(value = "${'$'}{missing.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
    }

    @Test
    fun `test marker transitions from unresolved to resolved after lazy load`() {
        val javaText = """
            @NacosPropertySource(dataId = "datasource.properties")
            class Demo {
                @NacosValue(value = "${'$'}{db.url}")
                private String url;
            }
        """.trimIndent()

        // Before the remote fetch: dataId context exists so a hollow marker shows,
        // but the key is not cached yet → unresolved icon.
        val unresolved = markerFor(javaText)
        assertNotNull(unresolved)
        assertEquals(NacosIcons.GutterConfigUnresolved, unresolved?.createGutterRenderer()?.icon)

        // Simulate what lazyLoadAndNavigate does: fetch + cache the config, then
        // rebuild the index synchronously.
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = null,
                configuration = NacosConfiguration("datasource.properties", "DEFAULT_GROUP", null, "db.url=jdbc:test\n", "properties")
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }

        // After the rebuild the key is resolvable → solid icon.
        val resolved = markerFor(javaText)
        assertNotNull(resolved)
        assertEquals(NacosIcons.GutterConfig, resolved?.createGutterRenderer()?.icon)
    }

    @Test
    fun `resolved element returns containing file instead of throwing PsiInvalidElementAccessException`() {
        cacheAndRefresh(NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo\n", "properties"))

        ApplicationManager.getApplication().runReadAction {
            val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
                "Demo.java",
                com.intellij.lang.java.JavaLanguage.INSTANCE,
                """
                class Demo {
                    @NacosValue(value = "${'$'}{app.name}")
                    private String name;
                }
                """.trimIndent()
            )
            val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
                .firstOrNull { PlaceholderParser.containsPlaceholder(it.value as? String) }!!
            val ref = NacosValueReference(literal, "app.name")

            val results = ref.multiResolve(false)
            assertEquals(1, results.size)

            // This is the call that used to throw PsiInvalidElementAccessException
            // because the FakePsiElement had a null parent and no containing file.
            val element = results.first().element as NacosConfigKeyElement
            val containingFile = element.containingFile
            assertNotNull("resolved element must report a containing file", containingFile)
            assertEquals(file, containingFile)
        }
    }

    @Test
    fun `NacosConfigKeyElement without context returns null containing file instead of throwing`() {
        val element = NacosConfigKeyElement(
            project = ProjectManager.getInstance().defaultProject,
            config = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "k=v\n", "properties"),
            key = "k",
            value = "v",
            lineIndex = 0,
            contextElement = null
        )
        // No context → null, but never throws (used by ConfigDetailPanel Find Usages anchor).
        assertNull(element.containingFile)
    }

    @Test
    fun `Nacos config key presentation identifies namespace dataId group and key`() {
        val element = NacosConfigKeyElement(
            project = ProjectManager.getInstance().defaultProject,
            config = NacosConfiguration(
                dataId = "roombiz.properties",
                group = "DEFAULT_GROUP",
                tenantId = "namespace2",
                content = "roombiz.im.check.switcher=false\n",
                type = "properties"
            ),
            key = "roombiz.im.check.switcher",
            value = "false",
            lineIndex = 0,
            contextElement = null
        )

        val presentation = element.presentation

        assertEquals("roombiz.im.check.switcher = false", presentation.presentableText)
        val location = presentation.locationString.orEmpty()
        assertTrue(location.contains("namespace2"))
        assertTrue(location.contains("roombiz.properties"))
        assertTrue(location.contains("DEFAULT_GROUP"))
        assertTrue(element.toString().contains("namespace2"))
    }

    @Test
    fun `value reference only resolves current namespace when cross namespace navigation is disabled`() {
        setAllowCrossNamespaceNavigation(false)
        selectProjectNamespace("namespace1")
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace1",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                ttl = 60_000L
            )
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }

        val results = resolveReferenceForKey("room.key")

        assertEquals(1, results.size)
        val element = results.single().element as NacosConfigKeyElement
        assertEquals("namespace1", element.config.tenantId)
    }

    @Test
    fun `value reference resolves other namespaces when cross namespace navigation is enabled`() {
        setAllowCrossNamespaceNavigation(true)
        selectProjectNamespace("namespace1")
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace1",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                ttl = 60_000L
            )
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }

        val results = resolveReferenceForKey("room.key")

        assertEquals(2, results.size)
        assertEquals(listOf("namespace1", "namespace2"), results.map { (it.element as NacosConfigKeyElement).config.tenantId })
    }

    @Test
    fun `marker is hidden when cross namespace target has no actionable dataId`() {
        cacheKeyInOtherNamespaceForActive("namespace1", allowCrossNamespace = false)

        val marker = markerFor(
            """
            class Demo {
                @NacosValue(value = "${'$'}{room.key}")
                private String value;
            }
            """.trimIndent()
        )

        assertNull(marker)
    }

    @Test
    fun `marker is resolved for cross namespace key when cross namespace navigation is enabled`() {
        cacheKeyInOtherNamespaceForActive("namespace1", allowCrossNamespace = true)

        val marker = markerFor(
            """
            class Demo {
                @NacosValue(value = "${'$'}{room.key}")
                private String value;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
    }

    // ── Visibility-aware gutters (issue #126) ──

    @Test
    fun `blocked identity hides resolved markers and renders blocked references undecidable`() = runBlocking {
        cacheAndRefresh(NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo\n", "properties"))
        val baseline = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )
        assertNotNull(baseline)
        assertEquals(NacosIcons.GutterConfig, baseline?.createGutterRenderer()?.icon)

        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        reportVisibility(
            cache,
            settings.captureAccessIdentity(),
            observation = 10_000,
            error = RemoteOperationError.Authentication(401)
        )
        refreshKeyIndex(cache, settings.captureAccessIdentity())

        // Without a dataId context the marker is hidden entirely.
        assertNull(
            markerFor(
                """
                class Demo {
                    @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                    private String name;
                }
                """.trimIndent()
            )
        )

        // With a declared property source the marker shows hollow and
        // undecidable, with an access-refused tooltip.
        val undecidable = markerFor(
            """
            @NacosPropertySource(dataId = "app.properties")
            class Demo {
                @NacosValue(value = "${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )
        assertNotNull(undecidable)
        assertEquals(NacosIcons.GutterConfigUnresolved, undecidable?.createGutterRenderer()?.icon)
        val tooltip = ApplicationManager.getApplication().runReadAction<String?> { undecidable?.lineMarkerTooltip }
        assertTrue("tooltip was: $tooltip", tooltip?.contains("access refused") == true)

        // Reference resolution returns no cached key hits.
        assertTrue(resolveReferenceForKey("app.name").isEmpty())

        // A newer matching success restores the resolved marker over the
        // retained payload.
        reportVisibility(cache, settings.captureAccessIdentity(), observation = 10_001)
        refreshKeyIndex(cache, settings.captureAccessIdentity())
        val restored = markerFor(
            """
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )
        assertNotNull(restored)
        assertEquals(NacosIcons.GutterConfig, restored?.createGutterRenderer()?.icon)
    }

    @Test
    fun `namespace block hides only that namespace's gutter markers`() = runBlocking {
        setAllowCrossNamespaceNavigation(false)
        selectProjectNamespace("team-a")
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        cache.writeDetail(
            identity = settings.captureAccessIdentity(),
            namespaceId = "team-a",
            configuration = NacosConfiguration(
                "room.properties", "DEFAULT_GROUP", "team-a", "room.key=one\n", "properties"
            ),
            ttl = 60_000L
        )
        cache.writeDetail(
            identity = settings.captureAccessIdentity(),
            namespaceId = "team-b",
            configuration = NacosConfiguration(
                "room.properties", "DEFAULT_GROUP", "team-b", "room.key=two\n", "properties"
            ),
            ttl = 60_000L
        )
        refreshKeyIndex(cache, settings.captureAccessIdentity())
        val javaText = """
            @NacosPropertySource(dataId = "room.properties")
            class Demo {
                @NacosValue(value = "${'$'}{room.key}")
                private String value;
            }
        """.trimIndent()
        assertEquals(NacosIcons.GutterConfig, markerFor(javaText)?.createGutterRenderer()?.icon)

        reportVisibility(
            cache,
            settings.captureAccessIdentity(),
            observation = 10_000,
            namespaceId = "team-a",
            error = RemoteOperationError.Authorization(403)
        )
        refreshKeyIndex(cache, settings.captureAccessIdentity())

        // Active namespace blocked: hollow undecidable marker, access-refused tooltip.
        val blockedMarker = markerFor(javaText)
        assertNotNull(blockedMarker)
        assertEquals(NacosIcons.GutterConfigUnresolved, blockedMarker?.createGutterRenderer()?.icon)
        val blockedTooltip = ApplicationManager.getApplication().runReadAction<String?> { blockedMarker?.lineMarkerTooltip }
        assertTrue("tooltip was: $blockedTooltip", blockedTooltip?.contains("access refused") == true)

        // The visible namespace keeps its resolved marker.
        selectProjectNamespace("team-b")
        val visibleMarker = markerFor(javaText)
        assertNotNull(visibleMarker)
        assertEquals(NacosIcons.GutterConfig, visibleMarker?.createGutterRenderer()?.icon)
    }

    // ── 格式不参与解析 (issue #172) ──

    @Test
    fun `a cached configuration no parse produces keys from renders the terminal marker`() {
        cacheAndRefresh(
            NacosConfiguration("beans.xml", "DEFAULT_GROUP", null, "<beans><a>1</a></beans>", "xml")
        )
        var swept = false
        val provider = NacosValueLineMarkerProvider { _, _ -> swept = true }

        val marker = markerFor(
            """
            @NacosPropertySource(dataId = "beans.xml")
            class Demo {
                @NacosValue(value = "${'$'}{a}")
                private String value;
            }
            """.trimIndent(),
            provider
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfigFormatNotParsed, marker?.createGutterRenderer()?.icon)
        val tooltip = ApplicationManager.getApplication().runReadAction<String?> { marker?.lineMarkerTooltip }
        // "not read from this format", never "no runtime parses it": both
        // runtimes parse xml and this plugin does not yet, so the string has to
        // be about what the plugin reads (ADR-0055).
        assertTrue("tooltip was: $tooltip", tooltip?.contains("not read from this format") == true)
        // The behavioural point of the state: a format the plugin will never
        // parse must stop asking the server about itself (issue #172).
        assertFalse("格式不参与解析 is terminal and must never sweep", swept)
        // The absent jump arrow is a promise the marker has to keep: every
        // other state is clickable, this one has nowhere to go and must not
        // fetch a body on click either.
        assertNull("nowhere to jump", marker?.navigationHandler)
    }

    @Test
    fun `a data id naming no format explains itself differently`() {
        cacheAndRefresh(NacosConfiguration("service-config", "DEFAULT_GROUP", null, "a=1\n", "properties"))
        var swept = false
        val provider = NacosValueLineMarkerProvider { _, _ -> swept = true }

        val marker = markerFor(
            """
            @NacosPropertySource(dataId = "service-config")
            class Demo {
                @NacosValue(value = "${'$'}{a}")
                private String value;
            }
            """.trimIndent(),
            provider
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfigFormatNotParsed, marker?.createGutterRenderer()?.icon)
        // One icon, but the only reason the user can act on says so.
        val tooltip = ApplicationManager.getApplication().runReadAction<String?> { marker?.lineMarkerTooltip }
        assertTrue("tooltip was: $tooltip", tooltip?.contains("names no format") == true)
        assertFalse("格式不参与解析 is terminal and must never sweep", swept)
    }

    @Test
    fun `a placeholder with no data id context keeps its current behaviour`() {
        cacheAndRefresh(
            NacosConfiguration("beans.xml", "DEFAULT_GROUP", null, "<beans><a>1</a></beans>", "xml")
        )

        // No @NacosPropertySource: the miss is attributable to nothing, so the
        // marker is hidden exactly as it was before this state existed.
        assertNull(
            markerFor(
                """
                class Demo {
                    @NacosValue(value = "${'$'}{a}")
                    private String value;
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a data id that does not exist stays unmarked whatever its format says`() {
        // The terminal conclusion is reached before presence is asked about,
        // which is right — presence changes nothing about whether a placeholder
        // could resolve. But the user is never shown a format explanation for a
        // configuration that is not there: a fresh complete index proves the
        // data id absent, and `shouldShowMarker` hides the marker outright.
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.replaceNamespaceIndex(
                settings.captureAccessIdentity(),
                null,
                listOf(NacosConfiguration("other.properties", "DEFAULT_GROUP", null, "other.key=v\n", "properties"))
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }

        assertNull(
            markerFor(
                """
                @NacosPropertySource(dataId = "service-config")
                class Demo {
                    @NacosValue(value = "${'$'}{a}")
                    private String value;
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a configuration that still extracts keeps its resolved marker`() {
        cacheAndRefresh(
            NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo\n", "properties")
        )

        val marker = markerFor(
            """
            @NacosPropertySource(dataId = "app.properties")
            class Demo {
                @NacosValue(value = "${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertNotNull(marker)
        assertEquals(NacosIcons.GutterConfig, marker?.createGutterRenderer()?.icon)
        assertNotNull("a resolvable placeholder stays clickable", marker?.navigationHandler)
    }

    private fun cacheKeyInOtherNamespaceForActive(activeNamespaceId: String, allowCrossNamespace: Boolean) {
        setAllowCrossNamespaceNavigation(allowCrossNamespace)
        selectProjectNamespace(activeNamespaceId)
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.writeDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            refreshKeyIndex(cache, settings.captureAccessIdentity())
        }
    }

    /**
     * Publishes the cross-namespace preference through the preference record
     * (issue #101). Mutating the legacy server entry alone is no longer enough.
     */
    private fun setAllowCrossNamespaceNavigation(enabled: Boolean) {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        val servers = settings.cloneServers().map {
            if (it.id == settings.activeServerId) {
                it.copy(allowCrossNamespaceNavigation = enabled)
            } else {
                it
            }
        }
        settings.applyServers(servers, settings.activeServerId)
    }

    private fun markerFor(
        javaText: String,
        provider: NacosValueLineMarkerProvider = NacosValueLineMarkerProvider { _, _ -> }
    ): LineMarkerInfo<*>? = ApplicationManager.getApplication().runReadAction<LineMarkerInfo<*>?> {
        val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
            "Demo.java",
            com.intellij.lang.java.JavaLanguage.INSTANCE,
            javaText
        )

        val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .firstOrNull { PlaceholderParser.containsPlaceholder(it.value as? String) }
        assertNotNull(literal)
        provider.getLineMarkerInfo(literal!!.firstChild)
    }

    private fun resolveReferenceForKey(key: String) = ApplicationManager.getApplication().runReadAction<Array<com.intellij.psi.ResolveResult>> {
        val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
            "Demo.java",
            com.intellij.lang.java.JavaLanguage.INSTANCE,
            """
            class Demo {
                @NacosValue(value = "${'$'}{$key}")
                private String value;
            }
            """.trimIndent()
        )
        val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .first { PlaceholderParser.containsPlaceholder(it.value as? String) }
        NacosValueReference(literal, key).multiResolve(false)
    }
}
