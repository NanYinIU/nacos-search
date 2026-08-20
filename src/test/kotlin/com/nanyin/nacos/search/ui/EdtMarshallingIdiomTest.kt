package com.nanyin.nacos.search.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Issue #82: one marshalling idiom ([com.nanyin.nacos.search.Edt.invokeOnEdt] in
 * production, [com.intellij.util.ui.UIUtil.invokeAndWaitIfNeeded] when a test
 * must block) replaces the four prior idioms and both hand-rolled
 * dispatch-thread checks.
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
            "SwingUtilities.isEventDispatchThread",
            // The no-modality Application.invokeLater overload and the
            // modality form must not appear outside the one helper.
            "ApplicationManager.getApplication().invokeLater",
            "ApplicationManager.getApplication().invokeAndWait",
            "ModalityUiUtil",
            "UIUtil.invokeLaterIfNeeded",
        )
        // Hand-rolled "if on EDT run else schedule". Bare asserts of thread
        // identity (e.g. off-EDT test preconditions) are not this pattern.
        val handRolledMarshall = Regex(
            """if\s*\([\s\S]*?isDispatchThread[\s\S]*?\)\s*\{[\s\S]*?\}\s*else\s*\{[\s\S]*?(?:invokeLater|invokeAndWait|invokeOnEdt)""",
        )

        val allowlisted = setOf(
            "EdtMarshallingIdiomTest.kt",
            "invokeOnEdt.kt", // the one production idiom lives here
        )

        val violations = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name !in allowlisted }
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
