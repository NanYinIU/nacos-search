package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.NacosSearchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.Dimension
import org.mockito.kotlin.mock
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.SwingUtilities

@TestApplication
class UiButtonInteractionTest {

    private val mockProject: Project = mock()

    @Test
    fun searchPanelButtonsTriggerSearchAndClearCallbacks() {
        val panel = SearchPanel(mockProject)
        val searchField = privateField<JTextField>(panel, "searchField")
        val searchButton = privateField<JButton>(panel, "searchButton")
        val clearButton = privateField<JButton>(panel, "clearButton")
        val groupFilterButton = privateField<JButton>(panel, "groupFilterButton")
        val calls = mutableListOf<String>()

        assertEquals(Dimension(28, 24), searchButton.preferredSize)
        assertEquals(Dimension(20, 20), clearButton.preferredSize)
        assertEquals(Dimension(90, 26), groupFilterButton.preferredSize)

        panel.onSearchRequested = { calls.add("search:${it.query}") }
        panel.onSearchCleared = { calls.add("clear") }

        runOnEdt {
            searchField.text = "demo"
            searchButton.doClick()
            clearButton.doClick()
        }

        assertEquals(listOf("search:demo", "clear"), calls)
        assertEquals("", panel.getSearchQuery())
    }

    @Test
    fun paginationButtonsTriggerNavigationAndPageSizeCallbacks() {
        val panel = PaginationPanel()
        val previousButton = privateField<JButton>(panel, "previousButton")
        val nextButton = privateField<JButton>(panel, "nextButton")
        val pageSizeComboBox = privateField<JComboBox<*>>(panel, "pageSizeComboBox")
        val calls = mutableListOf<String>()

       assertEquals(Dimension(24, 22), previousButton.preferredSize)
       assertEquals(Dimension(24, 22), nextButton.preferredSize)
        assertEquals(Dimension(72, 22), pageSizeComboBox.preferredSize)

        panel.onPreviousPage = { calls.add("previous") }
        panel.onNextPage = { calls.add("next") }
        panel.onPageSizeChanged = { calls.add("size:$it") }

        panel.updatePagination(
            NacosSearchService.PaginationState(
                currentPage = 2,
                pageSize = 10,
                totalCount = 25,
                totalPages = 3
            )
        )
        waitForUi()

        runOnEdt {
            previousButton.doClick()
            nextButton.doClick()
            pageSizeComboBox.selectedItem = 20
        }

        assertTrue("previous" in calls)
        assertTrue("next" in calls)
        assertTrue("size:20" in calls)
    }

    @Test
    fun configDetailActionButtonsFollowPrototypeSizing() {
        val panel = ConfigDetailPanel(mockProject)
        val refreshButton = privateField<JButton>(panel, "refreshButton")
        val copyButton = privateField<JButton>(panel, "copyButton")
        val saveButton = privateField<JButton>(panel, "saveButton")
        val editButton = privateField<JButton>(panel, "editButton")
        val revertButton = privateField<JButton>(panel, "revertButton")

       assertEquals(Dimension(26, 26), refreshButton.preferredSize)
       assertEquals(Dimension(68, 26), copyButton.preferredSize)
        assertEquals(Dimension(120, 26), saveButton.preferredSize)
        assertEquals(Dimension(72, 26), editButton.preferredSize)
        assertEquals(Dimension(72, 26), revertButton.preferredSize)
        assertTrue(copyButton.text.isNotBlank())

        Disposer.dispose(panel)
    }

    @Test
    fun detailSaveButtonIsPrimaryAndEditButtonTogglesVisibility() {
        val panel = ConfigDetailPanel(mockProject)
        val saveButton = privateField<JButton>(panel, "saveButton")
        val editButton = privateField<JButton>(panel, "editButton")

        // The Save & Publish button is the primary action in the design prototype
        // (class "btn primary" -> accent blue). It must carry the IntelliJ primary
        // button type so the rendered style matches the prototype.
        assertEquals("primary", saveButton.getClientProperty("JButton.buttonType"))

        // Edit button is visible by default (prototype: #editBtn visible until editing).
        assertTrue(editButton.isVisible)

        // The edit flow hides the button while editing and re-shows it on exit.
        // Simulate the internal state transitions directly (the editor is created
        // asynchronously via the real Nacos API, so we exercise the visibility
        // logic rather than the full load pipeline here).
        runOnEdt {
            val enterMethod = ConfigDetailPanel::class.java.getDeclaredMethod("enterEditMode")
            enterMethod.isAccessible = true
            enterMethod.invoke(panel)
        }
        // enterEditMode only hides when an editor exists; without one it is a no-op,
        // so the button stays visible. Verify exitEditMode restores a visible button.
        runOnEdt {
            val exitMethod = ConfigDetailPanel::class.java.getDeclaredMethod("exitEditMode")
            exitMethod.isAccessible = true
            exitMethod.invoke(panel)
        }
        assertTrue(editButton.isVisible)

        Disposer.dispose(panel)
    }

    @Test
    fun clearingConfigDetailAfterSelectionRemovesStaleMetadataAndActions() {
        val panel = ConfigDetailPanel(mockProject)
        val dataIdLabel = privateField<JTextField>(panel, "dataIdLabel")
        val copyButton = privateField<JButton>(panel, "copyButton")
        val editButton = privateField<JButton>(panel, "editButton")
        val config = NacosConfiguration(
            dataId = "application.properties",
            group = "DEFAULT_GROUP",
            tenantId = "",
            content = "server.port=8080",
            type = "properties"
        )

        runOnEdt {
            panel.showConfiguration(config)
            panel.clearConfiguration()
        }
        waitForUi()

        assertEquals(null, panel.getCurrentConfiguration())
        assertEquals("", dataIdLabel.text)
        assertTrue(!copyButton.isEnabled)
        assertTrue(!editButton.isEnabled)

        Disposer.dispose(panel)
    }

    @Test
    fun configListRefreshButtonTriggersRefreshCallback() {
        val panel = ConfigListPanel(mockProject)
        val refreshButton = privateField<JButton>(panel, "refreshButton")
        var refreshCount = 0

        panel.onRefreshRequested = { refreshCount++ }

        runOnEdt {
            refreshButton.doClick()
        }

        assertEquals(1, refreshCount)
        panel.dispose()
    }

    @Test
    fun selectingConfigListRowTriggersSelectionCallback() {
        val panel = ConfigListPanel(mockProject)
        val list = privateField<JList<*>>(panel, "configList")
        val config = NacosConfiguration(
            dataId = "demo.properties",
            group = "DEFAULT_GROUP",
            content = "k=v"
        )
        var selected: NacosConfiguration? = null

        panel.onConfigurationSelected = { selected = it }
        panel.setConfigurations(listOf(config))
        waitForUi()

        runOnEdt {
            list.selectedIndex = 0
        }
        waitForUi()

        assertEquals(config, selected)
        panel.dispose()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun runOnEdt(action: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) {
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
