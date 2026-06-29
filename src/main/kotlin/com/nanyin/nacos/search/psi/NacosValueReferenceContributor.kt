package com.nanyin.nacos.search.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext

/**
 * Contributes [NacosValueReference]s for string literals that appear as the
 * `value` attribute of `@NacosValue` (com.alibaba.nacos.api.config.annotation)
 * or Spring's `@Value` annotation, when that literal contains a `${...}`
 * placeholder.
 *
 * Binding to [PsiLiteralExpression] (rather than the annotation element
 * directly) lets us read the actual string value and extract the placeholder,
 * while the surrounding annotation context decides eligibility.
 */
class NacosValueReferenceContributor : PsiReferenceContributor() {


    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            com.intellij.patterns.PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            object : com.intellij.psi.PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
                    if (!isInSupportedAnnotation(literal)) return PsiReference.EMPTY_ARRAY
                    val text = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
                    val placeholder = PlaceholderParser.parse(text) ?: return PsiReference.EMPTY_ARRAY
                    return arrayOf(
                        NacosValueReference(
                            literal,
                            placeholder.key,
                            NacosCodeContextExtractor.fromLiteral(literal)
                        )
                    )
                }
            }
        )
    }

   companion object {
        private val SUPPORTED_ANNOTATIONS = setOf(
            "com.alibaba.nacos.api.config.annotation.NacosValue",
            "org.springframework.beans.factory.annotation.Value",
            "NacosValue",
            "Value"
        )

       /**
        * Walks up the PSI tree from [literal] to find an enclosing [PsiAnnotation]
         * whose qualified name is one of [SUPPORTED_ANNOTATIONS]. Shared with
         * the reverse Find Usages searcher.
         */
        fun isInSupportedAnnotation(literal: PsiLiteralExpression): Boolean {
            var current: PsiElement? = literal.parent
            var depth = 0
            while (current != null && depth < 5) {
                if (current is PsiAnnotation) {
                    val referenceName = current.nameReferenceElement?.text
                    if (referenceName in SUPPORTED_ANNOTATIONS) return true

                    val name = current.qualifiedName ?: return false
                    return name in SUPPORTED_ANNOTATIONS
                }
                current = current.parent
                depth++
            }
            return false
        }
    }
}
