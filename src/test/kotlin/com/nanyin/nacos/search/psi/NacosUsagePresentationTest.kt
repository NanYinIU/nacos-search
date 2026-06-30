package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ApplicationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.awt.Container
import javax.swing.JList
import javax.swing.JPanel

class NacosUsagePresentationTest {
    @get:Rule
    val applicationRule = ApplicationRule()

    @Test
    fun `usage presentation includes class member annotation file and line`() {
        val literal = ApplicationManager.getApplication().runReadAction<PsiLiteralExpression> {
            val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
                "AgoraRestService.java",
                com.intellij.lang.java.JavaLanguage.INSTANCE,
                """
                class AgoraRestService {
                    @NacosValue(value = "${'$'}{roombiz.im.check.switcher:false}", autoRefreshed = true)
                    private boolean imCheckSwitcher;
                }
                """.trimIndent()
            )
            PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
                .first { PlaceholderParser.containsPlaceholder(it.value as? String) }
        }

        val presentation = NacosUsagePresentation.fromElement(literal)

        assertEquals("AgoraRestService.imCheckSwitcher", presentation.primaryText)
        assertTrue(presentation.secondaryText.contains("@NacosValue"))
        assertTrue(presentation.secondaryText.contains("roombiz.im.check.switcher"))
        assertTrue(presentation.locationText.contains("AgoraRestService.java"))
        assertTrue(presentation.locationText.contains(":2"))
    }

    @Test
    fun `usage primary text includes normalized module path when module is available`() {
        val primary = NacosUsagePresentation.formatPrimaryText(
            className = "RoomBizConfig",
            ownerName = "flushRoomUseImSwitcher",
            moduleName = "ypzb.room-impl.main"
        )

        assertEquals("ypzb/room-impl/RoomBizConfig.flushRoomUseImSwitcher", primary)
    }

    @Test
    fun `usage popup renderer aligns location with primary row`() {
        val item = NacosUsageChoiceItem(
            literalFor(
                """
                class RoomConfig {
                    @NacosValue(value = "${'$'}{room.room.communicate.icon:}", autoRefreshed = true)
                    private String communicateIcon;
                }
                """.trimIndent()
            )
        )
        val rendererClass = Class.forName(
            "com.nanyin.nacos.search.ui.ConfigDetailPanel\$KeyFindUsagesAction\$CodeUsageRenderer"
        )
        val constructor = rendererClass.getDeclaredConstructor()
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val renderer = constructor.newInstance() as javax.swing.ListCellRenderer<NacosUsageChoiceItem>

        val component = renderer.getListCellRendererComponent(JList<NacosUsageChoiceItem>(), item, 0, true, false) as JPanel
        val headerRow = findPanelWithDirectLabels(component, item.presentation.primaryText, item.presentation.locationText)

        assertNotNull("location must be in the same header row as the primary usage text", headerRow)
    }

    private fun literalFor(javaText: String): PsiLiteralExpression =
        ApplicationManager.getApplication().runReadAction<PsiLiteralExpression> {
            val file = PsiFileFactory.getInstance(ProjectManager.getInstance().defaultProject).createFileFromText(
                "RoomConfig.java",
                com.intellij.lang.java.JavaLanguage.INSTANCE,
                javaText
            )
            PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)
                .first { PlaceholderParser.containsPlaceholder(it.value as? String) }
        }

    private fun findPanelWithDirectLabels(root: Container, firstText: String, secondText: String): JPanel? {
        if (root is JPanel) {
            val labels = root.components.filterIsInstance<javax.swing.JLabel>().map { it.text }
            if (firstText in labels && secondText in labels) return root
        }
        root.components.filterIsInstance<Container>().forEach { child ->
            findPanelWithDirectLabels(child, firstText, secondText)?.let { return it }
        }
        return null
    }
}
