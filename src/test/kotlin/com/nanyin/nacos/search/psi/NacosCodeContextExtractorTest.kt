package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
