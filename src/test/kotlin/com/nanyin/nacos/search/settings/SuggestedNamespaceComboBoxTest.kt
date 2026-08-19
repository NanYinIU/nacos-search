package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTextField

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
    fun `selecting a discovered option writes its namespace id`() {
        val combo = SuggestedNamespaceComboBox()
        combo.applyDiscovered(listOf(team, live))
        combo.selectedItem = live
        assertEquals("ns-uuid-2", combo.namespaceId())
    }
}
