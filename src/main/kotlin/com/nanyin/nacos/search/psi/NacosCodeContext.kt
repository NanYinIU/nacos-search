package com.nanyin.nacos.search.psi

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiReferenceExpression

/**
 * Lightweight context extracted near a Nacos placeholder usage
 * (typically `@NacosPropertySource`).
 *
 * [group] / [namespaceId] remain soft ranking hints. [dataId] is the
 * 已声明配置来源 **hard filter**: key hits are constrained to that Data ID alone.
 * Zero matches never soft-fall to another configuration; [NacosKeyResolver]
 * classifies the miss (body cached **within its TTL** without the key → 未解析
 * and no gutter icon; body cached past its TTL, or listed but unfetched → 不可判定
 * so a click — or one coordinate-width refresh — can load the declared body;
 * absence unproven → 不可用).
 */
data class NacosCodeContext(
    val dataId: String? = null,
    val group: String? = null,
    val namespaceId: String? = null,
    /**
     * The type declared on the configuration-source annotation
     * (`@NacosPropertySource(type = ConfigType.JSON)`), as the enum constant is
     * spelled there — `nacos-spring-context` reads it before the data id
     * suffix, so it takes the first row of the 运行时格式 decision table
     * ([RuntimeFormatDecision.forReference]).
     *
     * It says something only about [dataId]: it is a declaration about one
     * configuration, not a rule for whatever discovery reaches.
     */
    val declaredType: String? = null
)

object NacosCodeContextExtractor {
    private val DATA_ID_ATTRIBUTES = listOf("dataId")
    private val GROUP_ATTRIBUTES = listOf("groupId", "group")
    private val NAMESPACE_ATTRIBUTES = listOf("namespaceId", "namespace", "tenant")
    private const val TYPE_ATTRIBUTE = "type"

    fun fromLiteral(literal: PsiLiteralExpression): NacosCodeContext {
        var current: PsiElement? = literal.parent
        var depth = 0
        while (current != null && depth < 12) {
            when (current) {
                is PsiAnnotation -> contextOf(current)?.let { return it }
                is PsiClass -> current.annotations.forEach { annotation ->
                    contextOf(annotation)?.let { return it }
                }
            }
            current = current.parent
            depth++
        }
        return NacosCodeContext()
    }

    /**
     * The context [annotation] declares, or null when it declares no
     * configuration coordinate at all and the walk should keep going outwards.
     *
     * The declared type is read here rather than on its own, because
     * `nacos-spring-context` reads the type and the data id off one
     * `@NacosPropertySource`: lifting a type from some other annotation onto a
     * data id it says nothing about would extract a configuration by rules
     * nobody wrote for it. It is likewise not part of the "declares something"
     * test — a type with no data id names no configuration to apply it to.
     */
    private fun contextOf(annotation: PsiAnnotation): NacosCodeContext? {
        val dataId = firstStringAttribute(annotation, DATA_ID_ATTRIBUTES)
        val group = firstStringAttribute(annotation, GROUP_ATTRIBUTES)
        val namespace = firstStringAttribute(annotation, NAMESPACE_ATTRIBUTES)
        if (dataId == null && group == null && namespace == null) return null
        return NacosCodeContext(dataId, group, namespace, referenceAttribute(annotation, TYPE_ATTRIBUTE))
    }

    private fun firstStringAttribute(annotation: PsiAnnotation, names: List<String>): String? {
        return names.firstNotNullOfOrNull { name ->
            (annotation.parameterList.attributes.firstOrNull { it.name == name }?.value as? PsiLiteralExpression)
                ?.value
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Reads an attribute written as a reference to an enum constant
     * (`ConfigType.JSON`), which the string-literal helper above cannot: the
     * attribute is typed, so its value is an expression rather than a literal,
     * and what identifies it is the constant's own name — the same whether the
     * enum is imported or written out in full.
     */
    private fun referenceAttribute(annotation: PsiAnnotation, name: String): String? =
        (annotation.parameterList.attributes.firstOrNull { it.name == name }?.value as? PsiReferenceExpression)
            ?.referenceName
            ?.takeIf { it.isNotBlank() }
}
