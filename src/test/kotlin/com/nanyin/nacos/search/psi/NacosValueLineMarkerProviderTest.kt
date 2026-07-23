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
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.captureAccessIdentity
import com.nanyin.nacos.search.settings.NacosProjectSession
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
        }
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            cache.clearAll()
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
        }
        ApplicationManager.getApplication().getService(NamespaceService::class.java).setCurrentNamespace(null)
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
        cache.putConfigDetail(settings.captureAccessIdentity(), null, configuration)
        NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
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
        cache.putConfigDetail(
            settings.captureAccessIdentity(),
            null,
            NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties"),
            ttl = -1L
        )
        NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())

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
    fun `stale gutter observation requests background refresh without blocking PSI`() = runBlocking {
        val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        cache.putConfigDetail(
            settings.captureAccessIdentity(),
            null,
            NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties"),
            ttl = -1L
        )
        NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())

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
        assertTrue(observed)
    }

    @Test
    fun `marker resolves against project-selected namespace not app-wide NamespaceService`() {
        val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.getActiveServer().allowCrossNamespaceNavigation = false
        // App-wide service points at a different namespace than the project session.
        ApplicationManager.getApplication().getService(NamespaceService::class.java)
            .setCurrentNamespace(com.nanyin.nacos.search.models.NamespaceInfo("other-ns", "Other"))
        selectProjectNamespace("qa-ns")
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            cache.putConfigDetail(
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
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
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
        settings.servers = mutableListOf(local, qa)
        settings.profileMigrationCompleted = false
        settings.migrationDefaults()

        val project = ProjectManager.getInstance().defaultProject
        project.getService(NacosProjectSession::class.java).select("qa", "public")

        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val qaIdentity = settings.captureAccessIdentity("qa")
            cache.putConfigDetail(
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
            NacosKeyResolver.refreshIndex(cache, qaIdentity)
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
           cache.putNamespaceIndex(
               settings.captureAccessIdentity(),
               null,
               listOf(NacosConfiguration("other.properties", "DEFAULT_GROUP", null, "other.key=val\n", "properties"))
           )
           NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
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
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = null,
                configuration = NacosConfiguration("datasource.properties", "DEFAULT_GROUP", null, "db.url=jdbc:test\n", "properties")
            )
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
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
        ApplicationManager.getApplication().getService(NacosSettings::class.java)
            .getActiveServer().allowCrossNamespaceNavigation = false
        selectProjectNamespace("namespace1")
        ApplicationManager.getApplication().getService(NamespaceService::class.java)
            .setCurrentNamespace(com.nanyin.nacos.search.models.NamespaceInfo("namespace1", "Namespace 1"))
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace1",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                ttl = 60_000L
            )
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
        }

        val results = resolveReferenceForKey("room.key")

        assertEquals(1, results.size)
        val element = results.single().element as NacosConfigKeyElement
        assertEquals("namespace1", element.config.tenantId)
    }

    @Test
    fun `value reference resolves other namespaces when cross namespace navigation is enabled`() {
        ApplicationManager.getApplication().getService(NacosSettings::class.java)
            .getActiveServer().allowCrossNamespaceNavigation = true
        selectProjectNamespace("namespace1")
        ApplicationManager.getApplication().getService(NamespaceService::class.java)
            .setCurrentNamespace(com.nanyin.nacos.search.models.NamespaceInfo("namespace1", "Namespace 1"))
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace1",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace1", "room.key=one\n", "properties"),
                ttl = 60_000L
            )
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
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

    private fun cacheKeyInOtherNamespaceForActive(activeNamespaceId: String, allowCrossNamespace: Boolean) {
        ApplicationManager.getApplication().getService(NacosSettings::class.java)
            .getActiveServer().allowCrossNamespaceNavigation = allowCrossNamespace
        selectProjectNamespace(activeNamespaceId)
        ApplicationManager.getApplication().getService(NamespaceService::class.java)
            .setCurrentNamespace(com.nanyin.nacos.search.models.NamespaceInfo(activeNamespaceId, "Namespace 1"))
        runBlocking {
            val cache = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            cache.putConfigDetail(
                identity = settings.captureAccessIdentity(),
                namespaceId = "namespace2",
                configuration = NacosConfiguration("room.properties", "DEFAULT_GROUP", "namespace2", "room.key=two\n", "properties"),
                ttl = 60_000L
            )
            NacosKeyResolver.refreshIndex(cache, settings.serverUrl, settings.captureAccessIdentity())
        }
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
