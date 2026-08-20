package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.models.NacosServerConfig
import com.nanyin.nacos.search.services.operations.DiagnosticReport
import com.nanyin.nacos.search.services.operations.DiagnosticStageResult
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil

@TestApplication
class NacosConfigurableInteractionTest {

    @BeforeEach
    fun resetDefaults() {
        // The settings form seeds from the shared app-level NacosSettings, which
        // other test classes leave in an ANONYMOUS-published state. Reset to the
        // product defaults (NACOS_PASSWORD seed) so every UI test in this class
        // sees the same deterministic seed regardless of test order.
        ApplicationManager.getApplication().getService(NacosSettings::class.java).resetToDefaults()
    }

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
        val defaultGroupField = privateField<JBTextField>(configurable, "defaultGroupField")
        val testConnectionButton = privateField<JButton>(configurable, "testConnectionButton")
        val resetButton = findButtonByAutomationId(component, "nacos.settings.server.resetDefaults")
        val advancedButton = findButtonByAutomationId(component, "nacos.settings.advanced.toggle")

        assertNotNull(resetButton)
        assertNotNull(advancedButton)
        assertEquals(32, testConnectionButton.preferredSize.height)
        assertEquals(30, resetButton!!.preferredSize.height)

        runOnEdt {
            defaultGroupField.text = "CUSTOM_GROUP"
        }
        waitForUi()
        assertTrue(configurable.isModified())

        runOnEdt {
            resetButton!!.doClick()
        }
        waitForUi()
        assertEquals(NacosServerConfig.createDefault().defaultGroup, defaultGroupField.text)

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

    @Test
    fun crossNamespaceNavigationAdvancedCheckboxPersistsToActiveServer() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val crossNamespaceCheckBox = findCheckBoxByAutomationId(component, "nacos.settings.crossNamespaceNavigation")

        assertNotNull(crossNamespaceCheckBox)
        assertFalse(crossNamespaceCheckBox!!.isSelected)

        runOnEdt {
            crossNamespaceCheckBox.isSelected = true
            crossNamespaceCheckBox.doClick()
            crossNamespaceCheckBox.doClick()
        }
        waitForUi()

        assertTrue(crossNamespaceCheckBox.isSelected)
        assertTrue(configurable.isModified())

        configurable.apply()

        val draftServers = privateField<MutableList<NacosServerConfig>>(configurable, "draftServers")
        val activeId = privateField<String>(configurable, "draftActiveId")
        assertTrue(draftServers.first { it.id == activeId }.allowCrossNamespaceNavigation)
    }

    @Test
    fun advancedParameterLabelsUseShortScanFriendlyText() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()

        assertNotNull(findLabelByText(component, "Cross namespace"))
        assertNotNull(findLabelByText(component, "Default Group"))
    }

    @Test
    fun authStrategyOnPrimaryFormWithP0ChooserAndAdvancedWithoutAuth() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val advancedButton = findButtonByAutomationId(component, "nacos.settings.advanced.toggle")!!
        val authCombo = privateField<javax.swing.JComboBox<*>>(configurable, "authModeComboBox")
        val apiCombo = privateField<javax.swing.JComboBox<*>>(configurable, "apiPolicyComboBox")
        val writeIntent = findCheckBoxByAutomationId(component, "nacos.settings.writeIntent")!!
        val usernameRow = privateField<javax.swing.JComponent>(configurable, "usernameRow")
        val passwordRow = privateField<javax.swing.JComponent>(configurable, "passwordRow")

        assertEquals(4, authCombo.itemCount)
        assertEquals(
            listOf(
                AuthMode.ANONYMOUS,
                AuthMode.NACOS_PASSWORD,
                AuthMode.HTTP_BASIC,
                AuthMode.BEARER_TOKEN
            ),
            (0 until authCombo.itemCount).map { authCombo.getItemAt(it) }
        )
        // New / seed profiles default to Nacos password (not Anonymous).
        assertEquals(AuthMode.NACOS_PASSWORD, authCombo.selectedItem)
        // Password family shows credential rows (visibility is on wrappers, not fields).
        assertTrue(usernameRow.isVisible)
        assertTrue(passwordRow.isVisible)

        val advancedBody = findByAutomationId(component, "nacos.settings.advanced.body")
        assertNotNull(advancedBody)
        assertFalse(advancedBody!!.isVisible)
        // Auth strategy is on the primary form, not under Advanced.
        assertFalse(isAncestor(advancedBody, authCombo))
        assertTrue(isAncestor(advancedBody, apiCombo))
        assertTrue(isAncestor(advancedBody, writeIntent))

        runOnEdt { advancedButton.doClick() }
        waitForUi()
        assertTrue(advancedBody.isVisible)

        // Switching to Anonymous hides credentials entirely.
        runOnEdt {
            authCombo.selectedItem = AuthMode.ANONYMOUS
        }
        waitForUi()
        assertFalse(usernameRow.isVisible)
        assertFalse(passwordRow.isVisible)

        // Bearer shows only the token field.
        runOnEdt {
            authCombo.selectedItem = AuthMode.BEARER_TOKEN
        }
        waitForUi()
        assertFalse(usernameRow.isVisible)
        assertTrue(passwordRow.isVisible)
    }

    @Test
    fun testConnectionGatesLocallyWhenPasswordCredentialsIncomplete() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val testConnectionButton = privateField<JButton>(configurable, "testConnectionButton")
        val testStatusLabel = privateField<JLabel>(configurable, "testStatusLabel")
        val authCombo = privateField<javax.swing.JComboBox<*>>(configurable, "authModeComboBox")
        val serverUrlField = privateField<javax.swing.JTextField>(configurable, "serverUrlField")

        runOnEdt {
            serverUrlField.text = "http://localhost:8848"
            authCombo.selectedItem = AuthMode.NACOS_PASSWORD
            // Leave username/password empty (default seed).
            testConnectionButton.doClick()
        }
        waitForUi()

        assertEquals(
            com.nanyin.nacos.search.bundle.NacosSearchBundle.message("settings.test.credentials.incomplete"),
            testStatusLabel.text
        )
    }

    @Test
    fun testConnectionGatesLocallyWhenBearerTokenIncomplete() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val testConnectionButton = privateField<JButton>(configurable, "testConnectionButton")
        val testStatusLabel = privateField<JLabel>(configurable, "testStatusLabel")
        val authCombo = privateField<javax.swing.JComboBox<*>>(configurable, "authModeComboBox")
        val serverUrlField = privateField<javax.swing.JTextField>(configurable, "serverUrlField")
        val passwordField = privateField<javax.swing.JPasswordField>(configurable, "passwordField")

        runOnEdt {
            serverUrlField.text = "http://localhost:8848"
            authCombo.selectedItem = AuthMode.BEARER_TOKEN
            passwordField.text = ""
            testConnectionButton.doClick()
        }
        waitForUi()

        assertEquals(
            com.nanyin.nacos.search.bundle.NacosSearchBundle.message("settings.test.token.incomplete"),
            testStatusLabel.text
        )
    }

    @Test
    fun namespaceChooserIsEditableBeforeDiscovery() {
        val configurable = NacosConfigurable()
        val component = configurable.createComponent()
        val combo = privateField<SuggestedNamespaceComboBox>(configurable, "namespaceCombo")

        runOnEdt {
            combo.setNamespaceId("manual-ns")
            val formSize = component.preferredSize
            assertTrue(formSize.height > 0)
            assertTrue(combo.preferredSize.height > 0)
        }
        waitForUi()
        assertTrue(combo.isEditable)
        assertEquals("manual-ns", combo.namespaceId())
        assertEquals(0, combo.discoveredCount())
    }

    @Test
    fun changingServerUrlClearsDiscoveredNamespaceOptionsButKeepsIdentifier() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val combo = privateField<SuggestedNamespaceComboBox>(configurable, "namespaceCombo")
        val serverUrlField = privateField<javax.swing.JTextField>(configurable, "serverUrlField")
        val keep = DiscoveredNamespace("ns-keep", "Keep")

        runOnEdt {
            combo.setNamespaceId("ns-keep")
            configurable.acceptDiscoveredNamespaces(listOf(keep, DiscoveredNamespace("ns-other", "Other")))
        }
        waitForUi()
        assertEquals(2, combo.discoveredCount())

        runOnEdt {
            serverUrlField.text = "http://other.example:8848"
        }
        waitForUi()
        assertEquals(0, combo.discoveredCount())
        assertEquals(0, combo.itemCount)
        assertEquals("ns-keep", combo.namespaceId())
    }

    @Test
    fun changingNamespaceTextDoesNotClearDiscoveredOptions() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val combo = privateField<SuggestedNamespaceComboBox>(configurable, "namespaceCombo")
        val keep = DiscoveredNamespace("ns-keep", "Keep")

        runOnEdt {
            combo.setNamespaceId("ns-keep")
            configurable.acceptDiscoveredNamespaces(listOf(keep, DiscoveredNamespace("ns-other", "Other")))
            combo.setNamespaceId("ns-other")
        }
        waitForUi()
        assertEquals(2, combo.discoveredCount())
        assertEquals("ns-other", combo.namespaceId())
    }

    @Test
    fun staleDiscoveryIsNotAppliedAfterIdentityChange() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val combo = privateField<SuggestedNamespaceComboBox>(configurable, "namespaceCombo")
        val serverUrlField = privateField<javax.swing.JTextField>(configurable, "serverUrlField")
        val keep = DiscoveredNamespace("ns-keep", "Keep")

        runOnEdt {
            combo.setNamespaceId("ns-keep")
            configurable.acceptDiscoveredNamespaces(listOf(keep, DiscoveredNamespace("ns-other", "Other")))
        }
        waitForUi()
        val staleKey = privateField<DiscoveryOptionKey?>(configurable, "discoveredOptionKey")!!

        runOnEdt {
            serverUrlField.text = "http://other.example:8848"
        }
        waitForUi()
        assertEquals(0, combo.discoveredCount())

        val applied = configurable.applyDiscoveredNamespacesIfCurrent(
            listOf(keep, DiscoveredNamespace("ns-stale", "Stale")),
            staleKey
        )
        assertFalse(applied)
        assertEquals(0, combo.discoveredCount())
        assertEquals("ns-keep", combo.namespaceId())
    }

    @Test
    fun diagnosticHeadlineUsesPermissionCopyInsteadOfConnectionFailed() {
        val configurable = NacosConfigurable()
        val denied = DiagnosticStageResult(
            stage = "namespace_read",
            success = false,
            durationMillis = 1,
            sanitizedFailure = "Permission denied"
        )
        val discovery = DiagnosticStageResult(
            stage = "discovery",
            success = true,
            durationMillis = 1
        )
        val withOptions = DiagnosticReport(
            connected = false,
            stages = listOf(denied, discovery),
            manualNamespaceRequired = false,
            discoveredNamespaces = listOf(DiscoveredNamespace("ns-1", "Team")),
            configuredNamespaceId = "secret-ns"
        )
        val expected = com.nanyin.nacos.search.bundle.NacosSearchBundle.message(
            "settings.test.namespace.permission",
            "secret-ns"
        )
        assertEquals(expected, configurable.diagnosticHeadline(withOptions))

        val withoutOptions = withOptions.copy(discoveredNamespaces = emptyList())
        assertEquals(expected, configurable.diagnosticHeadline(withoutOptions))
        assertEquals(expected, configurable.diagnosticTooltip(withOptions, expected))
        assertEquals(expected, configurable.diagnosticTooltip(withoutOptions, expected))
    }

    @Test
    fun diagnosticTooltipKeepsStageDumpWhenHeadlineIsNotPermissionDenied() {
        val configurable = NacosConfigurable()
        val report = DiagnosticReport(
            connected = false,
            stages = listOf(
                DiagnosticStageResult(
                    stage = "namespace_read",
                    success = false,
                    durationMillis = 4,
                    sanitizedFailure = "Connection failed"
                )
            ),
            manualNamespaceRequired = false,
            configuredNamespaceId = "public"
        )
        val headline = configurable.diagnosticHeadline(report)
        assertEquals(
            com.nanyin.nacos.search.bundle.NacosSearchBundle.message("settings.connection.failed"),
            headline
        )
        assertEquals("namespace_read: Connection failed (4ms)", configurable.diagnosticTooltip(report, headline))
    }

    @Test
    fun diagnosticHeadlineDoesNotTreatDiscoveryAuthFailureAsTheTitle() {
        val configurable = NacosConfigurable()
        val report = DiagnosticReport(
            connected = false,
            stages = listOf(
                DiagnosticStageResult(
                    stage = "namespace_read",
                    success = false,
                    durationMillis = 1,
                    sanitizedFailure = "Connection failed"
                ),
                DiagnosticStageResult(
                    stage = "discovery",
                    success = false,
                    durationMillis = 1,
                    sanitizedFailure = "Authentication failed"
                )
            ),
            manualNamespaceRequired = false,
            configuredNamespaceId = "public"
        )
        assertEquals(
            com.nanyin.nacos.search.bundle.NacosSearchBundle.message("settings.connection.failed"),
            configurable.diagnosticHeadline(report)
        )
    }

    @Test
    fun strategySwitchPreservesPasswordFamilyAndClearsOnAnonymousOrBearer() {
        val configurable = NacosConfigurable()
        configurable.createComponent()
        val authCombo = privateField<javax.swing.JComboBox<*>>(configurable, "authModeComboBox")
        val usernameField = privateField<javax.swing.JTextField>(configurable, "usernameField")
        val passwordField = privateField<javax.swing.JPasswordField>(configurable, "passwordField")

        runOnEdt {
            authCombo.selectedItem = AuthMode.NACOS_PASSWORD
            usernameField.text = "alice"
            passwordField.text = "s3cret"
        }
        waitForUi()

        // NACOS_PASSWORD → HTTP_BASIC preserves username + password in fields and draft.
        runOnEdt {
            authCombo.selectedItem = AuthMode.HTTP_BASIC
        }
        waitForUi()
        assertEquals("alice", usernameField.text)
        assertEquals("s3cret", String(passwordField.password))
        val draftAfterBasic = selectedDraft(configurable)
        assertEquals(AuthMode.HTTP_BASIC, draftAfterBasic.authMode)
        assertEquals("alice", draftAfterBasic.username)
        assertEquals("s3cret", draftAfterBasic.password)

        // Any transition involving Bearer clears secret and username.
        runOnEdt {
            authCombo.selectedItem = AuthMode.BEARER_TOKEN
        }
        waitForUi()
        assertEquals("", usernameField.text)
        assertEquals("", String(passwordField.password))
        val draftAfterBearer = selectedDraft(configurable)
        assertEquals(AuthMode.BEARER_TOKEN, draftAfterBearer.authMode)
        assertEquals("", draftAfterBearer.username)
        assertEquals("", draftAfterBearer.password)

        // Fill a token, then switch to ANONYMOUS → clear both.
        runOnEdt {
            passwordField.text = "tok-value"
            authCombo.selectedItem = AuthMode.ANONYMOUS
        }
        waitForUi()
        assertEquals("", usernameField.text)
        assertEquals("", String(passwordField.password))
        val draftAfterAnon = selectedDraft(configurable)
        assertEquals(AuthMode.ANONYMOUS, draftAfterAnon.authMode)
        assertEquals("", draftAfterAnon.username)
        assertEquals("", draftAfterAnon.password)
    }

    private fun selectedDraft(configurable: NacosConfigurable): NacosServerConfig {
        val draftServers = privateField<MutableList<NacosServerConfig>>(configurable, "draftServers")
        val selectedId = privateField<String?>(configurable, "selectedServerId")
        return draftServers.first { it.id == selectedId }
    }

    private fun findByAutomationId(root: Component, automationId: String): JComponent? {
        if (root is JComponent && root.getClientProperty("nacos.automation.id") == automationId) return root
        if (root is Container) {
            root.components.forEach { child ->
                findByAutomationId(child, automationId)?.let { return it }
            }
        }
        return null
    }

    private fun isAncestor(ancestor: Component, node: Component): Boolean {
        var current: Component? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
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

    private fun findCheckBoxByAutomationId(root: Component, automationId: String): JCheckBox? {
        if (root is JCheckBox && root.getClientProperty("nacos.automation.id") == automationId) return root
        if (root is Container) {
            root.components.forEach { child ->
                findCheckBoxByAutomationId(child, automationId)?.let { return it }
            }
        }
        return null
    }

    private fun findLabelByText(root: Component, text: String): JLabel? {
        if (root is JLabel && root.text == text) return root
        if (root is Container) {
            root.components.forEach { child ->
                findLabelByText(child, text)?.let { return it }
            }
        }
        return null
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
        UIUtil.invokeAndWaitIfNeeded(Runnable { action() })
    }

    private fun waitForUi() {
        runOnEdt {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
