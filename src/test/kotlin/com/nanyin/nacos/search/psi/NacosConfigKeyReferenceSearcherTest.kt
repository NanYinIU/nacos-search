package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NacosConfigKeyReferenceSearcherTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `index extracts dotted key from fully qualified value annotation`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@org.springframework.beans.factory.annotation.Value("${'$'}{spring.datasource.url}") String u;"""
        )
        assertTrue(keys.contains("spring.datasource.url"))
    }

    @Test
    fun `index extracts hyphenated nacos key`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@NacosValue("${'$'}{rpc-timeout-ms}") int t;"""
        )
        assertTrue(keys.contains("rpc-timeout-ms"))
    }

    @Test
    fun `index returns empty for text without placeholders`() {
        assertTrue(Indexer.extractPlaceholderKeys("// no annotations here").isEmpty())
    }

    @Test
    fun `short nacos value annotation is supported for reverse search`() {
        ApplicationManager.getApplication().runReadAction {
            val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
                "Demo.java",
                com.intellij.lang.java.JavaLanguage.INSTANCE,
                """
                class Demo {
                    @NacosValue(value = "${'$'}{common.location.citycode.secretKey:default}")
                    private String key;
                }
                """.trimIndent()
            )
            val literal = PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            assertTrue(NacosValueReferenceContributor.isInSupportedAnnotation(literal!!))
        }
    }
}
