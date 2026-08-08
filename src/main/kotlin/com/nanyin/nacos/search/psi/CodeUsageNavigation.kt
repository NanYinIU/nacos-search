package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.nanyin.nacos.search.services.NavigationIndexRefreshService

/**
 * Navigate from a configuration-key Find Usages hit into code, requesting a
 * coalesced gutter pass so markers in the destination file re-evaluate under
 * the current session Namespace rather than whatever the last analysis painted
 * (issue #193). Does not refresh data or mutate the cache.
 */
internal fun navigateToCodeUsage(
    project: Project,
    element: PsiElement,
    requestGutterPass: (Project) -> Unit = { p ->
        ApplicationManager.getApplication()
            .getService(NavigationIndexRefreshService::class.java)
            .requestGutterPass(p)
    },
    navigate: (PsiElement) -> Unit = { el ->
        (el as? Navigatable)?.navigate(true)
    }
) {
    requestGutterPass(project)
    navigate(element)
}
