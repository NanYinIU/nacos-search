package com.nanyin.nacos.search.settings

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.UIUtil
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.JTextField

@TestApplication
class SuggestedNamespaceComboBoxTest {

    private val team = DiscoveredNamespace("ns-uuid-1", "Team One")
    private val live = DiscoveredNamespace("ns-uuid-2", "uxinlive")

    @Test
    fun `empty combo keeps a typed namespace id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("manual-ns")
        assertEquals("manual-ns", combo.namespaceId())
        assertEquals(0, combo.discoveredCount())
        assertEquals(0, combo.itemCount)
    }

    @Test
    fun `discovered options fill the popup and keep the current identifier`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("ns-uuid-2")
        combo.applyDiscovered(listOf(team, live))
        assertEquals(2, combo.discoveredCount())
        assertEquals(2, combo.itemCount)
        assertEquals("ns-uuid-2", combo.namespaceId())
    }

    @Test
    fun `clearing options keeps the typed identifier`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("ns-uuid-1")
        combo.applyDiscovered(listOf(team, live))
        combo.clearDiscoveredOptions()
        assertEquals(0, combo.discoveredCount())
        assertEquals(0, combo.itemCount)
        assertEquals("ns-uuid-1", combo.namespaceId())
    }

    @Test
    fun `typing a unique display name persists the namespace id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.applyDiscovered(listOf(team, live))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = "uxinlive"
        assertEquals("ns-uuid-2", combo.namespaceId())
        assertTrue(combo.itemCount <= 2)
        assertTrue(combo.itemCount >= 1)
    }

    @Test
    fun `filter keystrokes do not replace the committed namespace id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("ns-uuid-2")
        combo.applyDiscovered(listOf(team, live))
        val editor = combo.editor.editorComponent as JTextField
        editor.text = "Team"
        assertEquals("ns-uuid-2", combo.namespaceId())
    }

    @Test
    fun `selecting a discovered option writes its namespace id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.applyDiscovered(listOf(team, live))
        combo.selectedItem = live
        assertEquals("ns-uuid-2", combo.namespaceId())
    }

    @Test
    fun `picking a non-first option is not replaced by the first option after the list is restored`() {
        val publicNs = DiscoveredNamespace("public", "public")
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("public")
        combo.applyDiscovered(listOf(publicNs, team, live))
        UIUtil.invokeAndWaitIfNeeded(Runnable {
            combo.selectedItem = live
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        })
        assertEquals("ns-uuid-2", combo.namespaceId())
        assertEquals("ns-uuid-2", (combo.editor.editorComponent as JTextField).text)
    }

    @Test
    fun `a typed id that is not in the list survives restoring the unfiltered options`() {
        val publicNs = DiscoveredNamespace("public", "public")
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("custom-ns")
        combo.applyDiscovered(listOf(publicNs, live))
        flushChooserEvents()
        assertEquals("custom-ns", combo.namespaceId())
        assertEquals("custom-ns", (combo.editor.editorComponent as JTextField).text)

        combo.applyDiscovered(listOf(publicNs, live, team))
        flushChooserEvents()
        assertEquals("custom-ns", combo.namespaceId())
        assertEquals("custom-ns", (combo.editor.editorComponent as JTextField).text)
    }

    @Test
    fun `loading placeholder is not persistable and keeps the typed id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("custom-ns")
        combo.showLoadingPlaceholder()
        assertTrue(combo.isTransientRowVisible())
        assertEquals(0, combo.discoveredCount())
        assertEquals("custom-ns", combo.namespaceId())
        combo.selectedItem = combo.getItemAt(0)
        flushChooserEvents()
        assertEquals("custom-ns", combo.namespaceId())
        assertEquals("custom-ns", (combo.editor.editorComponent as JTextField).text)
    }

    @Test
    fun `failure placeholder is not persistable and keeps the typed id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.setNamespaceId("custom-ns")
        combo.showFailurePlaceholder()
        assertTrue(combo.isTransientRowVisible())
        assertEquals("custom-ns", combo.namespaceId())
        combo.selectedItem = combo.getItemAt(0)
        flushChooserEvents()
        assertEquals("custom-ns", combo.namespaceId())
    }

    @Test
    fun `preferred size does not npe when an empty editable combo is laid out`() {
        val combo = SuggestedNamespaceComboBox()
        val wrapper = JPanel(java.awt.BorderLayout(8, 0))
        wrapper.add(combo, java.awt.BorderLayout.CENTER)
        val size = wrapper.preferredSize
        assertTrue(size.height > 0)
        assertTrue(size.width >= 0)
    }

    private fun flushChooserEvents() {
        UIUtil.invokeAndWaitIfNeeded(Runnable {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        })
    }
}
