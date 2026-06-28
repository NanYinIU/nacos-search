package com.nanyin.nacos.search.settings

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.SwingUtilities

@TestApplication
class NacosConfigurableInteractionTest {

    @Test
    fun serverToolbarButtonsUpdateDraftSelectionAndModifiedState() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val serverList = privateField<JList<*>>(configurable, "serverList")
        val addButton = findButtonByAutomationId(component, "nacos.settings.server.add")
        val duplicateButton = findButtonByAutomationId(component, "nacos.settings.server.duplicate")
        val deleteButton = findButtonByAutomationId(component, "nacos.settings.server.delete")
        val setActiveButton = findButtonByAutomationId(component, "nacos.settings.server.setActive")
        val originalCount = serverList.model.size

        assertEquals(Dimension(26, 26), addButton!!.preferredSize)
        assertEquals(Dimension(26, 26), duplicateButton!!.preferredSize)
        assertEquals(Dimension(26, 26), deleteButton!!.preferredSize)
        assertEquals(Dimension(26, 26), setActiveButton!!.preferredSize)

        clickByAutomationId(component, "nacos.settings.server.add")
        waitForUi()
        assertEquals(originalCount + 1, serverList.model.size)
        assertTrue(configurable.isModified())

        val addedIndex = serverList.selectedIndex
        clickByAutomationId(component, "nacos.settings.server.duplicate")
        waitForUi()
        assertEquals(originalCount + 2, serverList.model.size)
        assertTrue(serverList.selectedIndex >= addedIndex)

        clickByAutomationId(component, "nacos.settings.server.setActive")
        waitForUi()
        assertTrue(configurable.isModified())
        assertActiveServerMatchesSelection(configurable, serverList)
    }

    @Test
    fun toolbarDeleteAndSetActiveButtonsAreDisabledPerDesignPrototype() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val serverList = privateField<JList<*>>(configurable, "serverList")
        val deleteButton = findButtonByAutomationId(component, "nacos.settings.server.delete")!!
        val setActiveButton = findButtonByAutomationId(component, "nacos.settings.server.setActive")!!

        // Single server: delete disabled (prototype #delSrv disabled when
        // servers.length <= 1); selected server is already active so Set Active
        // disabled too (prototype #setActive disabled when s.id === activeId).
        assertEquals(1, serverList.model.size)
        assertFalse(deleteButton.isEnabled)
        assertFalse(setActiveButton.isEnabled)

        // Add a second server: delete becomes enabled, and the new selection is
        // not yet active, so Set Active becomes enabled.
        clickByAutomationId(component, "nacos.settings.server.add")
        waitForUi()
        assertEquals(2, serverList.model.size)
        assertTrue(deleteButton.isEnabled)
        assertTrue(setActiveButton.isEnabled)

        // Mark the selected server active: Set Active disables again, delete stays enabled.
        clickByAutomationId(component, "nacos.settings.server.setActive")
        waitForUi()
        assertFalse(setActiveButton.isEnabled)
        assertTrue(deleteButton.isEnabled)
    }

    @Test
    fun resetDefaultsAndAdvancedToggleBehaveLikeSettingsPrototypeControls() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val timeoutSpinner = privateField<JSpinner>(configurable, "connectionTimeoutSpinner")
        val testConnectionButton = privateField<JButton>(configurable, "testConnectionButton")
        val resetButton = findButtonByAutomationId(component, "nacos.settings.server.resetDefaults")
        val advancedButton = findButtonByAutomationId(component, "nacos.settings.advanced.toggle")

        assertNotNull(resetButton)
        assertNotNull(advancedButton)
        assertEquals(32, testConnectionButton.preferredSize.height)
        assertEquals(30, resetButton!!.preferredSize.height)

        runOnEdt {
            timeoutSpinner.value = 120000
        }
        waitForUi()
        assertTrue(configurable.isModified())

        runOnEdt {
            resetButton!!.doClick()
        }
        waitForUi()
        assertEquals(NacosServerConfig.createDefault().connectionTimeoutMs, timeoutSpinner.value)

        val advancedBody = advancedButton!!.parent.components
            .filterIsInstance<JComponent>()
            .firstOrNull { it.layout is java.awt.GridBagLayout && !it.isVisible }
        assertNotNull(advancedBody)
        assertFalse(advancedBody!!.isVisible)

        runOnEdt {
            advancedButton.doClick()
        }
        waitForUi()
        assertTrue(advancedBody.isVisible)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun assertActiveServerMatchesSelection(configurable: NacosConfigurable, serverList: JList<*>) {
        val draftServers = privateField<MutableList<NacosServerConfig>>(configurable, "draftServers")
        val activeId = privateField<String>(configurable, "draftActiveId")
        val selected = draftServers[serverList.selectedIndex]
        assertEquals(selected.id, activeId)
    }

    private fun clickByAutomationId(root: Component, automationId: String) {
        val button = findButtonByAutomationId(root, automationId)
        assertNotNull(button, "Missing button with automation id $automationId")
        runOnEdt {
            button!!.doClick()
        }
    }

    private fun findButtonByAutomationId(root: Component, automationId: String): JButton? {
        return findButton(root) { it.getClientProperty("nacos.automation.id") == automationId }
    }

    private fun findButton(root: Component, predicate: (JButton) -> Boolean): JButton? {
        if (root is JButton && predicate(root)) return root
        if (root is Container) {
            root.components.forEach { child ->
                findButton(child, predicate)?.let { return it }
            }
        }
        return null
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeAndWait(action)
        }
    }

    private fun waitForUi() {
        runOnEdt {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
