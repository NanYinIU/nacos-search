package com.nanyin.nacos.search.psi

import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement

class NacosConfigChoiceItem(
    val element: NacosConfigKeyElement,
    private val namespaceDisplayName: String? = null
) : Navigatable {
    val primaryText: String get() = element.presentation.presentableText.orEmpty()
    val secondaryText: String
        get() = listOf(
            namespaceDisplayName?.takeIf { it.isNotBlank() } ?: element.namespaceDisplayName,
            element.config.group,
            element.config.dataId
        ).joinToString(" / ")

    override fun navigate(requestFocus: Boolean) {
        element.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = false

    override fun toString(): String = "$primaryText  $secondaryText"
}

class NacosUsageChoiceItem(
    val element: PsiElement,
    val presentation: NacosUsagePresentation = NacosUsagePresentation.fromElement(element)
) : Navigatable {
    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean =
        (element as? Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? Navigatable)?.canNavigateToSource() ?: false

    override fun toString(): String = presentation.toString()
}
