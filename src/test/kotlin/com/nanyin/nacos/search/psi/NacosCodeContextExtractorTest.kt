package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class NacosCodeContextExtractorTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `extracts class level groupId for nacos value literal`() {
        val context = contextFor(
            """
            @NacosPropertySource(dataId = "common.properties", groupId = "APP_GROUP", namespaceId = "dev")
            class Demo {
                @org.springframework.beans.factory.annotation.Value("${'$'}{timeout}")
                private String timeout;
            }
            """.trimIndent()
        )

        assertEquals("common.properties", context.dataId)
        assertEquals("APP_GROUP", context.group)
        assertEquals("dev", context.namespaceId)
        assertNull(context.declaredType)
    }

    @Test
    fun `extracts the declared type as a reference expression`() {
        // `type = ConfigType.JSON` is an enum constant, not a string literal, so
        // the string-literal helper the other three attributes share reads it as
        // absent. What lands in the context is the constant's own name.
        val context = contextFor(
            """
            @NacosPropertySource(dataId = "service-config", type = ConfigType.JSON)
            class Demo {
                @com.alibaba.nacos.api.config.annotation.NacosValue("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertEquals("service-config", context.dataId)
        assertEquals("JSON", context.declaredType)
    }

    @Test
    fun `extracts the declared type written out in full`() {
        val context = contextFor(
            """
            @NacosPropertySource(
                dataId = "service-config",
                type = com.alibaba.nacos.api.config.ConfigType.YAML
            )
            class Demo {
                @com.alibaba.nacos.api.config.annotation.NacosValue("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertEquals("YAML", context.declaredType)
    }

    @Test
    fun `the declared type comes from the annotation that names the data id`() {
        // `nacos-spring-context` reads both off one `@NacosPropertySource`, so a
        // type must never be lifted from a different annotation onto a data id
        // it says nothing about.
        val context = contextFor(
            """
            @NacosPropertySource(dataId = "app.yaml")
            class Demo {
                @com.alibaba.nacos.api.config.annotation.NacosValue("${'$'}{app.name}")
                private String name;
            }
            """.trimIndent()
        )

        assertEquals("app.yaml", context.dataId)
        assertNull(context.declaredType)
    }

    private fun contextFor(javaText: String) = ApplicationManager.getApplication().runReadAction<NacosCodeContext> {
        val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
            "Demo.java",
            com.intellij.lang.java.JavaLanguage.INSTANCE,
            javaText
        )
        val literal = PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
        assertNotNull(literal)
        NacosCodeContextExtractor.fromLiteral(literal!!)
    }
}
