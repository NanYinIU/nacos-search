package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertFalse
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

    @Test
    fun `reverse search accepts undeclared usages for any source dataId`() {
        // No @NacosPropertySource on the usage site → no hard constraint; the
        // key may resolve against any loaded configuration.
        assertTrue(
            NacosConfigKeyReferenceSearcher.acceptsUsageForSourceConfig(
                "test.yaml",
                NacosCodeContext()
            )
        )
    }

    @Test
    fun `reverse search accepts usages that declare the same dataId`() {
        assertTrue(
            NacosConfigKeyReferenceSearcher.acceptsUsageForSourceConfig(
                "es.properties",
                NacosCodeContext(dataId = "es.properties")
            )
        )
    }

    @Test
    fun `reverse search rejects usages hard-bound to a different dataId`() {
        // test.yaml's app.name must not reverse-navigate to a @NacosValue under
        // @NacosPropertySource(dataId = "es.properties") — forward hard filter
        // would not resolve that placeholder against test.yaml either.
        assertFalse(
            NacosConfigKeyReferenceSearcher.acceptsUsageForSourceConfig(
                "test.yaml",
                NacosCodeContext(dataId = "es.properties")
            )
        )
    }

    @Test
    fun `unscoped reverse search accepts every usage`() {
        assertTrue(
            NacosConfigKeyReferenceSearcher.acceptsUsageForSourceConfig(
                null,
                NacosCodeContext(dataId = "es.properties")
            )
        )
        assertTrue(
            NacosConfigKeyReferenceSearcher.acceptsUsageForSourceConfig(
                "",
                NacosCodeContext(dataId = "es.properties")
            )
        )
    }
}
