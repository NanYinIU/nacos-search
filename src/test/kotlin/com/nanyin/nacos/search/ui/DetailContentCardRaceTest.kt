package com.nanyin.nacos.search.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.CardLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Documents the first-load CardLayout race that left "Loading configuration..."
 * painted over the editor: [CardLayout.show] for a card that is not yet in the
 * container is a no-op, and a later [Container.add] does not hide the previous
 * card. The panel must show "content" only after the editor card is added.
 */
class DetailContentCardRaceTest {

    @Test
    fun `show content before add leaves loading visible`() {
        val panel = JPanel(CardLayout())
        val loading = JLabel("Loading configuration...")
        panel.add(loading, "loading")
        (panel.layout as CardLayout).show(panel, "loading")
        assertTrue(loading.isVisible)

        // First-load bug: reveal "content" before the editor exists.
        (panel.layout as CardLayout).show(panel, "content")
        assertTrue(loading.isVisible, "show(content) must be a no-op when the card is absent")

        val content = JLabel("body")
        panel.add(content, "content")
        assertTrue(loading.isVisible, "adding content must not hide the current card")
        assertFalse(content.isVisible, "CardLayout hides newly added non-current cards")
    }

    @Test
    fun `show content after add hides loading`() {
        val panel = JPanel(CardLayout())
        val loading = JLabel("Loading configuration...")
        panel.add(loading, "loading")
        (panel.layout as CardLayout).show(panel, "loading")

        val content = JLabel("body")
        panel.add(content, "content")
        loading.isVisible = false
        (panel.layout as CardLayout).show(panel, "content")

        assertFalse(loading.isVisible)
        assertTrue(content.isVisible)
    }
}
