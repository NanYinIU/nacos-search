package com.nanyin.nacos.search.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression

/**
 * Lightweight context extracted near a Nacos placeholder usage. It is used only
 * as a ranking hint; unresolved or unsupported attributes fall back to the
 * active namespace/group behavior.
 */
data class NacosCodeContext(
    val dataId: String? = null,
    val group: String? = null,
    val namespaceId: String? = null
)

object NacosCodeContextExtractor {
    private val DATA_ID_ATTRIBUTES = listOf("dataId")
    private val GROUP_ATTRIBUTES = listOf("groupId", "group")
    private val NAMESPACE_ATTRIBUTES = listOf("namespaceId", "namespace", "tenant")

    fun fromLiteral(literal: PsiLiteralExpression): NacosCodeContext {
        var current: PsiElement? = literal.parent
        var depth = 0
        while (current != null && depth < 12) {
            when (current) {
                is PsiAnnotation -> {
                    val dataId = firstStringAttribute(current, DATA_ID_ATTRIBUTES)
                    val group = firstStringAttribute(current, GROUP_ATTRIBUTES)
                    val namespace = firstStringAttribute(current, NAMESPACE_ATTRIBUTES)
                    if (dataId != null || group != null || namespace != null) {
                        return NacosCodeContext(dataId, group, namespace)
                    }
                }
                is PsiClass -> {
                    current.annotations.forEach { annotation ->
                        val dataId = firstStringAttribute(annotation, DATA_ID_ATTRIBUTES)
                        val group = firstStringAttribute(annotation, GROUP_ATTRIBUTES)
                        val namespace = firstStringAttribute(annotation, NAMESPACE_ATTRIBUTES)
                        if (dataId != null || group != null || namespace != null) {
                            return NacosCodeContext(dataId, group, namespace)
                        }
                    }
                }
            }
            current = current.parent
            depth++
        }
        return NacosCodeContext()
    }

    private fun firstStringAttribute(annotation: PsiAnnotation, names: List<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            (annotation.parameterList.attributes.firstOrNull { it.name == name }?.value as? PsiLiteralExpression)
                ?.value
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
