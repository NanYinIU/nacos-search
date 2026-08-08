package com.nanyin.nacos.search.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Find Usages → code navigation must request a coalesced gutter pass before
 * opening the usage (issue #193). Both the single-hit and multi-chooser
 * branches call [navigateToCodeUsage]; this tests the shared seam so neither
 * sibling can open a file without re-analyzing markers.
 */
class CodeUsageNavigationTest {

    @Test
    fun `requests a gutter pass before navigating`() {
        val project = mock<Project>()
        val element = mock<PsiElement>()
        val events = mutableListOf<String>()

        navigateToCodeUsage(
            project = project,
            element = element,
            requestGutterPass = { p ->
                assertEquals(project, p)
                events += "pass"
            },
            navigate = { el ->
                assertEquals(element, el)
                events += "navigate"
            }
        )

        assertEquals(listOf("pass", "navigate"), events)
    }

    @Test
    fun `still navigates when the pass request is a no-op`() {
        val project = mock<Project>()
        val element = mock<PsiElement>()
        var navigated = false

        navigateToCodeUsage(
            project = project,
            element = element,
            requestGutterPass = { },
            navigate = { navigated = true }
        )

        assertTrue(navigated)
    }
}
