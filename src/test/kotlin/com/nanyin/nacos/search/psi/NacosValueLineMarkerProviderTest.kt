package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NacosValueLineMarkerProviderTest {

    @get:Rule
    val applicationRule = ApplicationRule()

    @Before
    fun setUp() {
        runBlocking {
            ApplicationManager.getApplication().getService(CacheService::class.java).clearAll()
        }
    }

    @Test
    fun `test marker is provided for supported annotation literal`() {
        runBlocking {
            ApplicationManager.getApplication().getService(CacheService::class.java).putConfigDetail(
                serverUrl = "http://localhost:8848",
                namespaceId = null,
                configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "app.name=demo", "properties")
            )
        }
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
        runBlocking {
            ApplicationManager.getApplication().getService(CacheService::class.java).putConfigDetail(
                serverUrl = "http://localhost:8848",
                namespaceId = null,
                configuration = NacosConfiguration("app.properties", "DEFAULT_GROUP", null, "missing.name=demo", "properties")
            )
        }
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

    private fun markerFor(javaText: String): LineMarkerInfo<*>? = ApplicationManager.getApplication().runReadAction<LineMarkerInfo<*>?> {
        val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
            "Demo.java",
            com.intellij.lang.java.JavaLanguage.INSTANCE,
            javaText
        )

        val literal = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
            .firstOrNull { PlaceholderParser.containsPlaceholder(it.value as? String) }
        assertNotNull(literal)
        NacosValueLineMarkerProvider().getLineMarkerInfo(literal!!.firstChild)
    }
}
