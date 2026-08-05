package com.nanyin.nacos.search.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.operations.EditEnvironment
import com.nanyin.nacos.search.services.operations.EditSessionService
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.mockito.kotlin.mock
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import javax.swing.border.Border
import org.mockito.kotlin.whenever

@TestApplication
class UiButtonInteractionTest {

    /**
     * The detail panel renders the project's draft, so a stand-in project has
     * to supply one. This is a real [EditSessionService] over an environment
     * that never resolves a profile: no edit can start, which is all these
     * layout assertions need.
     */
    private val mockProject: Project = mock<Project>().also {
        whenever(it.getService(EditSessionService::class.java)).thenReturn(
            EditSessionService({ OperationGateway(emptyMap()) }, object : EditEnvironment {
                override fun selectedProfile(): EnvironmentProfile? = null
                override fun profile(profileId: String): EnvironmentProfile? = null
                override suspend fun captureTarget(profileId: String, namespaceId: String) =
                    Result.failure<OperationTarget>(IllegalStateException("no environment"))
            })
        )
    }

    @Test
    fun searchPanelButtonsTriggerSearchAndClearCallbacks() {
        val panel = SearchPanel(mockProject)
        val searchField = privateField<JTextField>(panel, "searchField")
        val clearButton = privateField<JButton>(panel, "clearButton")
        val groupFilterButton = privateField<JButton>(panel, "groupFilterButton")
        val calls = mutableListOf<String>()

        assertEquals(Dimension(26, 26), clearButton.preferredSize)
        assertTrue(groupFilterButton.preferredSize.width >= groupFilterButton.minimumSize.width)
        assertTrue(groupFilterButton.preferredSize.width > 90)

        panel.onSearchRequested = { calls.add("search:${it.query}") }
        panel.onSearchCleared = { calls.add("clear") }

        runOnEdt {
            searchField.text = "demo"
            // Enter in the field is the only way to submit: the search button was
            // never added to a layout, so no user could ever click it.
            pressEnter(searchField)
            clearButton.doClick()
        }

        assertEquals(listOf("search:demo", "clear"), calls)
        assertEquals("", panel.getSearchQuery())
        Disposer.dispose(panel)
    }

    @Test
    fun topToolbarSelectionControlsSizeFromDisplayedContentWithBounds() {
        val settings = NacosSettings().apply {
            servers = mutableListOf(
                NacosServerConfig(
                    id = "qa",
                    displayName = "QA Integration With A Longer Name",
                    serverUrl = "http://qa.example.com:8848"
                )
            )
            activeServerId = "qa"
        }
        val switcher = EnvironmentSwitcher(mockProject, settings)
        val envButton = privateField<JButton>(switcher, "envButton")

        assertEquals(null, envButton.icon)
        assertTrue(envButton.preferredSize.width >= envButton.minimumSize.width)
        assertTrue(envButton.preferredSize.width <= envButton.maximumSize.width)
        assertTrue(envButton.preferredSize.width > 180)
        assertTrue(envButton.maximumSize.width > 220)

        val panel = SearchPanel(mockProject)
        val searchField = privateField<JTextField>(panel, "searchField")
        val searchFieldPanel = privateField<JPanel>(panel, "searchFieldPanel")
        val groupFilterButton = privateField<JButton>(panel, "groupFilterButton")

        assertEquals(envButton.font.size2D, searchField.font.size2D)
        assertEquals(searchField.font.size2D, groupFilterButton.font.size2D)
        assertTrue(searchFieldPanel.border is EmptyBorder)
        assertTrue(groupFilterButton.border is EmptyBorder)
        assertTrue(groupFilterButton.preferredSize.width >= groupFilterButton.minimumSize.width)
        assertTrue(groupFilterButton.preferredSize.width <= groupFilterButton.maximumSize.width)
        assertTrue(groupFilterButton.preferredSize.width >= groupFilterButton.getFontMetrics(groupFilterButton.font).stringWidth(groupFilterButton.text) + 24)

        val namespaceService = mock<NamespaceService> {
            whenever(it.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(emptyList())))
            whenever(it.getCurrentNamespace()).thenReturn(null)
        }
        val namespacePanel = NamespacePanel(
            mockProject,
            namespaceService = namespaceService,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val namespaceButton = privateField<JButton>(namespacePanel, "namespaceButton")

        assertTrue(namespaceButton.border is EmptyBorder)
        assertTrue(namespaceButton.preferredSize.width >= namespaceButton.minimumSize.width)
        assertTrue(namespaceButton.preferredSize.width <= namespaceButton.maximumSize.width)
        assertTrue(namespaceButton.maximumSize.width > 220)
        Disposer.dispose(namespacePanel)
        Disposer.dispose(panel)
        Disposer.dispose(switcher)
    }

    @Test
    fun toolbarControlsRenderBorderlessAndAlignedInActualLayout() {
        val namespace = com.nanyin.nacos.search.models.NamespaceInfo(
            namespaceId = "427adcc2-dbf8-4086-8433-8be647a86b62",
            namespaceName = "uxinlive",
            configCount = 220
        )
        val namespaceService = mock<NamespaceService> {
            whenever(it.loadNamespacesAsync()).thenReturn(CompletableDeferred(Result.success(listOf(namespace))))
            whenever(it.getCurrentNamespace()).thenReturn(namespace)
        }

        val namespacePanel = NamespacePanel(
            mockProject,
            namespaceService = namespaceService,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val searchPanel = SearchPanel(mockProject)
        val toolbar = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = EmptyBorder(0, 8, 0, 8)
            add(namespacePanel)
            add(searchPanel)
            setSize(520, 60)
            doLayoutRecursively()
        }
        waitForUi()
        toolbar.doLayoutRecursively()

        val namespaceButton = privateField<JButton>(namespacePanel, "namespaceButton")
        val searchFieldPanel = privateField<JPanel>(searchPanel, "searchFieldPanel")
        val clearButton = privateField<JButton>(searchPanel, "clearButton")
        val groupFilterButton = privateField<JButton>(searchPanel, "groupFilterButton")

        val namespaceBounds = namespaceButton.convertedBounds(toolbar)
        val searchBounds = searchFieldPanel.convertedBounds(toolbar)
        val clearBounds = clearButton.convertedBounds(toolbar)
        val groupBounds = groupFilterButton.convertedBounds(toolbar)

        assertTrue(namespaceButton.border is EmptyBorder)
        assertTrue(searchFieldPanel.border is EmptyBorder)
        assertTrue(groupFilterButton.border is EmptyBorder)
        assertFalse(namespaceButton.isBorderPainted)
        assertFalse(clearButton.isBorderPainted)
        assertFalse(groupFilterButton.isBorderPainted)

        assertEquals(namespaceBounds.x, searchBounds.x)
        assertEquals(16, namespaceButton.insets.left)
        assertEquals(namespaceButton.insets.left, searchFieldPanel.border.leftInset())
        assertEquals(namespaceBounds.x + namespaceButton.insets.left, searchBounds.x + searchFieldPanel.border.leftInset())
        assertEquals(26, clearBounds.width)
        assertTrue(searchBounds.maxX() <= clearBounds.x)
        assertEquals(6, groupBounds.x - clearBounds.maxX())
        assertTrue(groupBounds.width >= groupFilterButton.getFontMetrics(groupFilterButton.font).stringWidth(groupFilterButton.text) + 20)

        val image = BufferedImage(toolbar.width, toolbar.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            toolbar.paint(g)
        } finally {
            g.dispose()
        }
        val out = File("build/ui-probe/nacos-toolbar.png")
        out.parentFile.mkdirs()
        ImageIO.write(image, "png", out)

        Disposer.dispose(namespacePanel)
        Disposer.dispose(searchPanel)
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
        assertEquals(Dimension(56, 22), pageSizeComboBox.preferredSize)
        assertEquals(Dimension(56, 22), pageSizeComboBox.maximumSize)

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
        Disposer.dispose(panel)
    }

   @Test
   fun configDetailActionButtonsFollowPrototypeSizing() {
       val panel = ConfigDetailPanel(mockProject)
       try {
            // Read utilities (Refresh / Copy / History) are now native AnActions rendered via an
            // ActionToolbar (not icon JButtons). Verify the three actions carry their icons and the
            // toolbar component is present; the legacy refreshButton/copyButton/historyButton fields
            // no longer exist.
            val refreshAction = privateField<com.intellij.openapi.actionSystem.AnAction>(panel, "refreshAction")
            val copyAction = privateField<com.intellij.openapi.actionSystem.AnAction>(panel, "copyAction")
            val historyAction = privateField<com.intellij.openapi.actionSystem.AnAction>(panel, "historyAction")
            assertNotNull(refreshAction.templatePresentation.icon)
            assertNotNull(copyAction.templatePresentation.icon)
            assertNotNull(historyAction.templatePresentation.icon)

            // Edit lifecycle buttons keep text labels (clear commit commands)
            val saveButton = privateField<JButton>(panel, "saveButton")
            val editButton = privateField<JButton>(panel, "editButton")
            val revertButton = privateField<JButton>(panel, "revertButton")
            assertEquals(Dimension(72, 26), saveButton.preferredSize)
            assertEquals(Dimension(72, 26), editButton.preferredSize)
            assertEquals(Dimension(72, 26), revertButton.preferredSize)
            assertTrue(editButton.text.isNotBlank())
            assertTrue(saveButton.text.isNotBlank())
           assertTrue(revertButton.text.isNotBlank())

           // View mode: Edit visible; Save/Revert hidden until editing
           assertTrue(editButton.isVisible)
           assertFalse(saveButton.isVisible)
           assertFalse(revertButton.isVisible)
        } finally {
            Disposer.dispose(panel)
        }
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

        // enterEditMode now requires writeIntent and resolves an OperationTarget
        // asynchronously. Without those, it shows a dialog and returns. Exercise
        // exitEditMode to verify the button remains/restores visible.
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
        // Copy/History are now AnActions whose enabled state derives from currentConfiguration
        // in update(); clearing the config (currentConfiguration == null) disables them, verified
        // indirectly via the currentConfiguration assertion below. editButton is still a JButton.
        val dataIdLabel = privateField<JTextField>(panel, "dataIdLabel")
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
        assertTrue(!editButton.isEnabled)

        Disposer.dispose(panel)
    }

    @Test
    fun configDetailHeaderRowsUseSameVisualLeftEdge() {
        val panel = ConfigDetailPanel(mockProject)
        try {
            val dataIdLabel = privateField<JTextField>(panel, "dataIdLabel")
            val inlineMetaLabel = privateField<JComponent>(panel, "inlineMetaLabel")
            val formatTagLabel = privateField<JComponent>(panel, "formatTagLabel")
            val config = NacosConfiguration(
                dataId = "sys.properties",
                group = "DEFAULT_GROUP",
                tenantId = "427adcc2-dbf8-4086-8433-8be647a86b62",
                content = "room.enabled=true",
                type = "properties"
            )

            runOnEdt {
                panel.showConfiguration(config)
                panel.setSize(900, 260)
                panel.doLayoutRecursively()
            }
            waitForUi()
            runOnEdt {
                panel.setSize(900, 260)
                panel.doLayoutRecursively()
            }

            val titleX = dataIdLabel.convertedBounds(panel).x
            val metadataX = inlineMetaLabel.convertedBounds(panel).x
            val formatTagX = formatTagLabel.convertedBounds(panel).x

            assertEquals(titleX, metadataX)
            assertEquals(titleX, formatTagX)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun configListRefreshButtonTriggersRefreshCallback() {
        val panel = ConfigListPanel(mockProject)
        val refreshButton = privateField<JButton>(panel, "refreshButton")
        var refreshCount = 0

        panel.onRefreshRequested = { refreshCount++ }

        assertEquals(Dimension(28, 24), refreshButton.preferredSize)
        assertEquals("toolbar", refreshButton.getClientProperty("JButton.buttonType"))
        assertFalse(refreshButton.isBorderPainted)
        assertFalse(refreshButton.isContentAreaFilled)

        runOnEdt {
            refreshButton.doClick()
        }

        assertEquals(1, refreshCount)
        Disposer.dispose(panel)
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
        Disposer.dispose(panel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun pressEnter(field: JTextField) {
        field.keyListeners.forEach {
            it.keyPressed(
                java.awt.event.KeyEvent(
                    field,
                    java.awt.event.KeyEvent.KEY_PRESSED,
                    0L,
                    0,
                    java.awt.event.KeyEvent.VK_ENTER,
                    '\n'
                )
            )
        }
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

    private fun JPanel.doLayoutRecursively() {
        doLayout()
        components.forEach {
            if (it is JPanel) {
                it.doLayoutRecursively()
            } else {
                it.doLayout()
            }
        }
    }

    private fun JButton.convertedBounds(root: JPanel): Rectangle =
        SwingUtilities.convertRectangle(parent, bounds, root)

    private fun JPanel.convertedBounds(root: JPanel): Rectangle =
        SwingUtilities.convertRectangle(parent, bounds, root)

    private fun JComponent.convertedBounds(root: JPanel): Rectangle =
        SwingUtilities.convertRectangle(parent, bounds, root)

    private fun Rectangle.maxX(): Int = x + width

    private fun Border.leftInset(): Int = getBorderInsets(null).left
}
