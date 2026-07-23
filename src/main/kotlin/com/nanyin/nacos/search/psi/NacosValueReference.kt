package com.nanyin.nacos.search.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import com.nanyin.nacos.search.settings.allowCrossNamespaceNavigation
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import com.nanyin.nacos.search.settings.selectedNacosNamespaceId

/**
 * A reference from a `${...}` placeholder inside `@NacosValue` / `@Value` to the
 * Nacos configuration that defines the key.
 *
 * Resolves against the local configuration cache via [NacosKeyResolver]; each
 * hit becomes a [NacosConfigKeyElement]. When the cache is empty or the key is
 * absent the reference resolves to nothing (navigation is a no-op).
 */
class NacosValueReference(
    element: PsiLiteralExpression,
    private val key: String,
    private val codeContext: NacosCodeContext = NacosCodeContext()
) : PsiPolyVariantReferenceBase<PsiLiteralExpression>(element) {

    override fun getVariants(): Array<Any> = emptyArray()

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project: Project = element.project ?: return emptyArray()
        val allowCrossNamespace = project.allowCrossNamespaceNavigation()
        val hits = NacosKeyResolver.resolve(
            key = key,
            preferredGroup = codeContext.group,
            preferredNamespaceId = codeContext.namespaceId,
            allowCrossNamespace = allowCrossNamespace,
            activeNamespaceId = project.selectedNacosNamespaceId(),
            activeIdentity = project.captureSelectedAccessIdentity()
        )
        return hits.map { hit ->
            object : ResolveResult {
                override fun getElement(): PsiElement =
                    NacosConfigKeyElement(
                        project,
                        hit.config,
                        key,
                        hit.location.value,
                        hit.location.lineIndex,
                        contextElement = this@NacosValueReference.element
                    )
                override fun isValidResult(): Boolean = true
            }
        }.toTypedArray()
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        return element is NacosConfigKeyElement && (element as NacosConfigKeyElement).key == key
    }
}
