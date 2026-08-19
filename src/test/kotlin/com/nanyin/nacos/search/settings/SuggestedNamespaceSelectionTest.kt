package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestedNamespaceSelectionTest {

    private val team = DiscoveredNamespace("ns-uuid-1", "Team One")
    private val live = DiscoveredNamespace("ns-uuid-2", "uxinlive")
    private val options = listOf(team, live)

    @Test
    fun `blank editor persists as blank so the draft still treats empty as public later`() {
        assertEquals("", SuggestedNamespaceSelection.persistableId("  ", options))
    }

    @Test
    fun `typed namespace id is persisted as the identifier`() {
        assertEquals("ns-uuid-1", SuggestedNamespaceSelection.persistableId("ns-uuid-1", options))
    }

    @Test
    fun `unique display name resolves to the namespace id`() {
        assertEquals("ns-uuid-2", SuggestedNamespaceSelection.persistableId("uxinlive", options))
    }

    @Test
    fun `filter keystrokes keep the last committed identifier`() {
        assertEquals(
            "ns-uuid-2",
            SuggestedNamespaceSelection.persistableId("Team", options, lastCommitted = "ns-uuid-2")
        )
    }

    @Test
    fun `text that matches no option is a manual namespace id`() {
        assertEquals(
            "custom-ns",
            SuggestedNamespaceSelection.persistableId("custom-ns", options, lastCommitted = "ns-uuid-1")
        )
    }

    @Test
    fun `unknown text is kept as a manual namespace id`() {
        assertEquals("custom-ns", SuggestedNamespaceSelection.persistableId("custom-ns", emptyList()))
    }

    @Test
    fun `ambiguous display names keep the last committed identifier`() {
        val clash = listOf(
            DiscoveredNamespace("id-a", "shared"),
            DiscoveredNamespace("id-b", "shared")
        )
        assertEquals("id-a", SuggestedNamespaceSelection.persistableId("shared", clash, lastCommitted = "id-a"))
    }

    @Test
    fun `filter matches display name or namespace id`() {
        assertTrue(SuggestedNamespaceSelection.matchesFilter(live, "uxin"))
        assertTrue(SuggestedNamespaceSelection.matchesFilter(live, "UUID-2"))
        assertFalse(SuggestedNamespaceSelection.matchesFilter(live, "Team"))
        assertTrue(SuggestedNamespaceSelection.matchesFilter(live, ""))
    }

    @Test
    fun `label shows display name with id when they differ`() {
        assertEquals("uxinlive (ns-uuid-2)", SuggestedNamespaceSelection.label(live))
        assertEquals("public", SuggestedNamespaceSelection.label(DiscoveredNamespace("public", "public")))
    }
}
