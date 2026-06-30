package com.nanyin.nacos.search.psi

import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.ApplicationRule
import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NacosPopupChoiceItemTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `configuration popup item is navigatable without being a psi element`() {
        val element = NacosConfigKeyElement(
            project = ProjectManager.getInstance().defaultProject,
            config = NacosConfiguration(
                dataId = "roombiz.properties",
                group = "DEFAULT_GROUP",
                tenantId = "namespace2",
                content = "roombiz.im.check.switcher=false\n"
            ),
            key = "roombiz.im.check.switcher",
            value = "false",
            lineIndex = 0
        )

        val item = NacosConfigChoiceItem(element)

        val popupModelItem: Any = item
        assertFalse(popupModelItem is PsiElement)
        assertTrue(item.canNavigate())
    }

    @Test
    fun `configuration popup item uses readable namespace name when provided`() {
        val element = NacosConfigKeyElement(
            project = ProjectManager.getInstance().defaultProject,
            config = NacosConfiguration(
                dataId = "roombiz.properties",
                group = "DEFAULT_GROUP",
                tenantId = "fc765465-e05f-44b0-b953-8ceefc8a0d1c",
                content = "room.key=value\n"
            ),
            key = "room.key",
            value = "value",
            lineIndex = 0
        )

        val item = NacosConfigChoiceItem(element, namespaceDisplayName = "public")

        assertTrue(item.secondaryText.startsWith("public / DEFAULT_GROUP / roombiz.properties"))
        assertFalse(item.secondaryText.contains("fc765465-e05f-44b0-b953-8ceefc8a0d1c"))
    }
}
