package com.nanyin.nacos.search.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Editor-reuse rules for detail Body re-renders. Quiet background confirms of
 * the same content must keep the live editor so @NacosValue navigation does
 * not lose the caret to line 0.
 */
class ConfigDetailEditorReuseTest {

    @Test
    fun `view-mode quiet confirm with same content reuses the editor`() {
        assertTrue(
            ConfigDetailPanel.shouldReuseDetailEditor(
                hasLiveEditor = true,
                sameCoordinate = true,
                editing = false,
                sameContent = true
            )
        )
    }

    @Test
    fun `view-mode content change recreates the editor`() {
        assertFalse(
            ConfigDetailPanel.shouldReuseDetailEditor(
                hasLiveEditor = true,
                sameCoordinate = true,
                editing = false,
                sameContent = false
            )
        )
    }

    @Test
    fun `edit-mode keeps the editor even when content differs`() {
        assertTrue(
            ConfigDetailPanel.shouldReuseDetailEditor(
                hasLiveEditor = true,
                sameCoordinate = true,
                editing = true,
                sameContent = false
            )
        )
    }

    @Test
    fun `different configuration recreates the editor`() {
        assertFalse(
            ConfigDetailPanel.shouldReuseDetailEditor(
                hasLiveEditor = true,
                sameCoordinate = false,
                editing = false,
                sameContent = true
            )
        )
    }

    @Test
    fun `missing editor never reuses`() {
        assertFalse(
            ConfigDetailPanel.shouldReuseDetailEditor(
                hasLiveEditor = false,
                sameCoordinate = false,
                editing = false,
                sameContent = false
            )
        )
    }
}
