package com.nanyin.nacos.search.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Issue #82: one marshalling idiom ([com.intellij.util.ModalityUiUtil.invokeLaterIfNeeded]
 * in production, [com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded] when a test must block)
 * replaces the four prior idioms and both hand-rolled dispatch-thread checks.
 */
class EdtMarshallingIdiomTest {

    @Test
    fun `source uses only the unified EDT marshalling idiom`() {
        val roots = listOf(
            File("src/main/kotlin"),
            File("src/test/kotlin"),
        )
        val forbiddenStrings = listOf(
            "SwingUtilities.invokeLater",
            "SwingUtilities.invokeAndWait",
            "ApplicationManager.getApplication().invokeLater",
            "ApplicationManager.getApplication().invokeAndWait",
            "SwingUtilities.isEventDispatchThread",
        )
        // Hand-rolled "if on EDT run else schedule". Bare asserts of thread
        // identity are not this pattern.
        val handRolledMarshall = Regex(
            """if\s*\([^)]*isDispatchThread[^)]*\)\s*\{[^}]*\}\s*else\s*\{[^}]*(?:invokeLater|invokeAndWait)""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val violations = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name != "EdtMarshallingIdiomTest.kt" }
                .forEach { file ->
                    val text = file.readText()
                    forbiddenStrings.forEach { pattern ->
                        if (text.contains(pattern)) {
                            violations += "${file.path}: contains '$pattern'"
                        }
                    }
                    if (handRolledMarshall.containsMatchIn(text)) {
                        violations += "${file.path}: hand-rolled dispatch-thread marshall"
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Removed EDT marshalling idioms must be gone:\n${violations.joinToString("\n")}"
        )
    }
}
